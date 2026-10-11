"""Job store, FIFO queue and the single GPU worker thread.

Only one job runs on the GPU at a time. Waiting jobs sit in a FIFO queue whose
length is capped; when the queue is full, ``submit`` raises ``QueueFull`` and the
API answers 429.

Images live only in memory (``Job.image_bytes``) and are dropped as soon as the
job finishes, whether it succeeded or failed. Nothing here logs image bytes,
captions or model output.
"""

from __future__ import annotations

import logging
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from typing import Callable, Optional

log = logging.getLogger("rocov2.worker")

QUEUED = "queued"
RUNNING = "running"
COMPLETED = "completed"
FAILED = "failed"


class QueueFull(Exception):
    """Raised by ``JobQueue.submit`` when the waiting line is at its cap."""


class JobError(Exception):
    """A failure with an API error code that is safe to show to the user."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Job:
    job_id: str
    created_at: float
    image_bytes: Optional[bytes]
    caption: str
    status: str = QUEUED
    result: Optional[dict] = None
    error: Optional[dict] = None
    finished_at: Optional[float] = None


# process_fn(job) -> result dict. It may raise JobError for a known failure.
ProcessFn = Callable[[Job], dict]


@dataclass
class JobQueue:
    process_fn: ProcessFn
    max_queue: int = 5
    ttl_seconds: float = 15 * 60
    clock: Callable[[], float] = time.monotonic
    # Called for any exception that is not a JobError. Returns (code, message).
    classify_error: Callable[[BaseException], tuple] = field(
        default=lambda exc: ("INFERENCE_FAILED", "The model failed while generating the explanation.")
    )

    def __post_init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._waiting: deque[str] = deque()
        self._running_id: Optional[str] = None
        self._cond = threading.Condition()
        self._stop = False
        self._thread: Optional[threading.Thread] = None

    # ---- public API used by the HTTP layer -------------------------------

    def submit(self, image_bytes: bytes, caption: str) -> Job:
        with self._cond:
            self._purge_expired_locked()
            if len(self._waiting) >= self.max_queue:
                raise QueueFull()
            job = Job(
                job_id=str(uuid.uuid4()),
                created_at=self.clock(),
                image_bytes=image_bytes,
                caption=caption,
            )
            self._jobs[job.job_id] = job
            self._waiting.append(job.job_id)
            self._cond.notify_all()
            return job

    def get(self, job_id: str) -> Optional[Job]:
        with self._cond:
            self._purge_expired_locked()
            return self._jobs.get(job_id)

    def position(self, job_id: str) -> int:
        """1-based place in line for a queued job, counting a job that is
        currently running on the GPU as ahead of it. 0 if not queued."""
        with self._cond:
            return self._position_locked(job_id)

    def snapshot(self, job_id: str) -> Optional[dict]:
        """Everything the API needs about a job, read under one lock."""
        with self._cond:
            self._purge_expired_locked()
            job = self._jobs.get(job_id)
            if job is None:
                return None
            return {
                "job_id": job.job_id,
                "status": job.status,
                "position": self._position_locked(job_id),
                "result": job.result,
                "error": job.error,
            }

    def _position_locked(self, job_id: str) -> int:
        try:
            index = self._waiting.index(job_id)
        except ValueError:
            return 0
        return index + 1 + (1 if self._running_id is not None else 0)

    @property
    def queue_length(self) -> int:
        with self._cond:
            return len(self._waiting)

    @property
    def busy(self) -> bool:
        with self._cond:
            return self._running_id is not None

    # ---- worker ----------------------------------------------------------

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop = False
        self._thread = threading.Thread(target=self._loop, name="gpu-worker", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        with self._cond:
            self._stop = True
            self._cond.notify_all()
        if self._thread is not None:
            self._thread.join(timeout)

    def run_next(self) -> bool:
        """Process one waiting job synchronously. Returns False if none waited.
        The worker thread calls this in a loop; tests call it directly."""
        with self._cond:
            self._purge_expired_locked()
            if not self._waiting:
                return False
            job = self._jobs[self._waiting.popleft()]
            job.status = RUNNING
            self._running_id = job.job_id
        try:
            result = self.process_fn(job)
            outcome = (COMPLETED, result, None)
        except JobError as exc:
            outcome = (FAILED, None, {"code": exc.code, "message": exc.message})
        except BaseException as exc:  # noqa: BLE001 - the worker must survive any failure
            code, message = self.classify_error(exc)
            # Log the exception type only: the message could echo inputs.
            log.warning("job failed with %s", type(exc).__name__)
            outcome = (FAILED, None, {"code": code, "message": message})
        with self._cond:
            job.status, job.result, job.error = outcome
            job.image_bytes = None  # drop the image as soon as inference is over
            job.finished_at = self.clock()
            self._running_id = None
            self._cond.notify_all()
        return True

    def _loop(self) -> None:
        while True:
            with self._cond:
                while not self._waiting and not self._stop:
                    self._cond.wait(timeout=30)
                    self._purge_expired_locked()
                if self._stop:
                    return
            self.run_next()

    # ---- expiry ----------------------------------------------------------

    def _purge_expired_locked(self) -> None:
        now = self.clock()
        expired = [
            jid
            for jid, job in self._jobs.items()
            if jid != self._running_id and now - job.created_at > self.ttl_seconds
        ]
        for jid in expired:
            job = self._jobs.pop(jid)
            job.image_bytes = None
            try:
                self._waiting.remove(jid)
            except ValueError:
                pass

