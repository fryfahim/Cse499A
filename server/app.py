"""FastAPI app that serves the ROCOv2 Bangla model from a live Colab runtime.

The model itself is not imported here. The caller passes in:

* ``explain_fn(image: PIL.Image.Image, caption: str) -> str`` - runs the model
  and returns the raw generated Bangla text (see ``inference.py``).
* ``side_check_fn(caption: str, bangla: str) -> str`` - the notebook's
  left/right consistency check.

That keeps this module testable without a GPU (see ``tests/``).

The API contract is documented in ``API.md`` at the repository root.
"""

from __future__ import annotations

import hmac
import io
import json
import logging
import time
import warnings
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Callable, Optional

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image, ImageOps
from starlette.exceptions import HTTPException as StarletteHTTPException

try:  # Keep uploads in memory: never spool an upload to a temp file on disk.
    from starlette.formparsers import MultiPartParser as _MultiPartParser
except ImportError:  # pragma: no cover - python-multipart missing
    _MultiPartParser = None

from notebook_logic import DISCLAIMER_BN
from worker import JobError, JobQueue, QueueFull

log = logging.getLogger("rocov2.app")

API_VERSION = "1.0.0"
MODEL_NAME = "Qwen3.5-4B + LoRA qwen_lora_bangla_radiology"

ALLOWED_FORMATS = {"JPEG", "PNG", "WEBP"}

ExplainFn = Callable[[Image.Image, str], str]
SideCheckFn = Callable[[str, str], str]


@dataclass
class ServerConfig:
    max_upload_bytes: int = 10 * 1024 * 1024
    max_caption_chars: int = 1000
    max_queue: int = 5
    job_ttl_seconds: float = 15 * 60
    # Training shrank every image with img.thumbnail((512, 512)); do the same
    # here so the model sees inputs like the ones it was fine-tuned on.
    # None disables the shrink (the inference notebook sent full resolution).
    max_img_side: Optional[int] = 512


@dataclass
class GpuHooks:
    """How to recognise and recover from CUDA out-of-memory errors."""

    oom_types: tuple = ()
    empty_cache: Callable[[], None] = lambda: None

    @classmethod
    def from_torch(cls) -> "GpuHooks":
        try:
            import torch
        except ImportError:
            return cls()

        def _empty_cache() -> None:
            if torch.cuda.is_available():
                torch.cuda.empty_cache()

        return cls(oom_types=(torch.cuda.OutOfMemoryError,), empty_cache=_empty_cache)


class ApiError(HTTPException):
    """An HTTP error with a contract error code. Subclasses FastAPI's
    HTTPException so FastAPI re-raises it from body parsing unchanged."""

    def __init__(self, status_code: int, code: str, message: str):
        super().__init__(status_code=status_code, detail=message)
        self.code = code
        self.message = message


def error_body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def side_check_level(text: str) -> str:
    """Map the notebook's side_check() sentence to the API level."""
    if text.startswith("WARNING"):
        return "warning"
    if text.startswith("No caption"):
        return "not_checked"
    return "ok"


def preprocess_image(img: Image.Image, max_side: Optional[int]) -> Image.Image:
    """EXIF-rotate, convert to RGB, then shrink like training did."""
    img = ImageOps.exif_transpose(img)
    img = img.convert("RGB")
    if max_side:
        img.thumbnail((max_side, max_side))
    return img


def validate_image(data: bytes) -> None:
    """Open the bytes with Pillow and check they are a real JPEG/PNG/WebP."""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as probe:
                fmt = probe.format
                probe.verify()
            if fmt not in ALLOWED_FORMATS:
                raise ValueError("format")
            with Image.open(io.BytesIO(data)) as full:
                full.load()  # verify() does not decode pixel data
    except Exception:
        raise ApiError(400, "INVALID_IMAGE", "The file is not a readable JPEG, PNG or WebP image.") from None


class _GuardMiddleware:
    """Pure ASGI middleware: API key check and a hard body-size cap.

    Runs before FastAPI parses the multipart body, so an unauthenticated or
    oversized upload is refused without being buffered.
    """

    def __init__(self, app, api_key: str, max_body_bytes: int):
        self.app = app
        self.api_key = api_key.encode()
        self.max_body_bytes = max_body_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] == "/health":
            await self.app(scope, receive, send)
            return
        headers = dict(scope.get("headers") or [])
        supplied = headers.get(b"x-api-key", b"")
        if not supplied or not hmac.compare_digest(supplied, self.api_key):
            await _send_json(send, 401, error_body("UNAUTHORIZED", "Missing or wrong API key."))
            return
        declared = headers.get(b"content-length")
        if declared is not None:
            try:
                too_big = int(declared) > self.max_body_bytes
            except ValueError:
                too_big = False
            if too_big:
                await _send_json(send, 413, error_body("TOO_LARGE", "The upload is larger than 10 MB."))
                return

        received = 0
        limit = self.max_body_bytes

        async def capped_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise ApiError(413, "TOO_LARGE", "The upload is larger than 10 MB.")
            return message

        await self.app(scope, capped_receive, send)


async def _send_json(send, status: int, body: dict) -> None:
    raw = json.dumps(body).encode()
    await send(
        {
            "type": "http.response.start",
            "status": status,
            "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(raw)).encode())],
        }
    )
    await send({"type": "http.response.body", "body": raw})


_STATUS_TO_CODE = {
    400: "INVALID_IMAGE",
    401: "UNAUTHORIZED",
    404: "NOT_FOUND",
    405: "NOT_FOUND",
    413: "TOO_LARGE",
    429: "QUEUE_FULL",
    503: "MODEL_NOT_READY",
}


def create_app(
    explain_fn: Optional[ExplainFn],
    side_check_fn: SideCheckFn,
    *,
    api_key: str,
    config: Optional[ServerConfig] = None,
    gpu: Optional[GpuHooks] = None,
    clock: Callable[[], float] = time.monotonic,
    start_worker: bool = True,
) -> FastAPI:
    """Build the app. ``explain_fn=None`` means the model is still loading."""
    if not api_key:
        raise ValueError("api_key must be a non-empty string")
    config = config or ServerConfig()
    gpu = gpu or GpuHooks.from_torch()

    if _MultiPartParser is not None:
        # Large enough that a 10 MB image never rolls over to a temp file.
        spool = config.max_upload_bytes + 1024 * 1024
        _MultiPartParser.spool_max_size = spool
        if hasattr(_MultiPartParser, "max_file_size"):  # older Starlette name
            _MultiPartParser.max_file_size = spool

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if start_worker:
            _app.state.queue.start()
        yield
        _app.state.queue.stop()

    app = FastAPI(
        title="ROCOv2 Bangla API",
        version=API_VERSION,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.status = "ready" if explain_fn is not None else "loading"
    app.state.explain_fn = explain_fn

    def classify(exc: BaseException) -> tuple:
        if gpu.oom_types and isinstance(exc, gpu.oom_types):
            try:
                gpu.empty_cache()
            except Exception:  # noqa: BLE001
                pass
            return ("INFERENCE_FAILED", "The GPU ran out of memory. Please try again.")
        return ("INFERENCE_FAILED", "The model failed while generating the explanation.")

    def process(job) -> dict:
        fn = app.state.explain_fn
        if fn is None:
            raise JobError("MODEL_NOT_READY", "The model is not loaded yet.")
        started = time.perf_counter()
        with Image.open(io.BytesIO(job.image_bytes)) as raw:
            raw.load()
            img = preprocess_image(raw, config.max_img_side)
        try:
            text = (fn(img, job.caption) or "").strip()
        finally:
            del img
        if not text:
            raise JobError("EMPTY_OUTPUT", "The model returned an empty answer.")
        try:
            check = side_check_fn(job.caption, text)
            check = check if isinstance(check, str) else str(check)
            check_info = {"level": side_check_level(check), "message": check}
        except Exception:  # noqa: BLE001
            check_info = {"level": "not_checked", "message": "The left/right check could not run."}
        return {
            "bangla_text": text,
            "mode": "caption" if job.caption else "image_only",
            "caption_used": job.caption,
            "side_check": check_info,
            "disclaimer_bn": DISCLAIMER_BN,
            "elapsed_ms": int((time.perf_counter() - started) * 1000),
        }

    queue = JobQueue(
        process_fn=process,
        max_queue=config.max_queue,
        ttl_seconds=config.job_ttl_seconds,
        clock=clock,
        classify_error=classify,
    )
    app.state.queue = queue
    app.state.config = config

    def set_model(fn: Optional[ExplainFn], status: Optional[str] = None) -> None:
        app.state.explain_fn = fn
        app.state.status = status or ("ready" if fn is not None else "loading")

    app.state.set_model = set_model

    # ---- error handlers: always JSON, never a stack trace ---------------

    @app.exception_handler(ApiError)
    async def _api_error(_request: Request, exc: ApiError):
        return JSONResponse(error_body(exc.code, exc.message), status_code=exc.status_code)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_request: Request, exc: StarletteHTTPException):
        if isinstance(exc.__cause__, ApiError):  # raised while parsing the body
            cause = exc.__cause__
            return JSONResponse(error_body(cause.code, cause.message), status_code=cause.status_code)
        code = _STATUS_TO_CODE.get(exc.status_code, "INFERENCE_FAILED")
        message = "Not found." if code == "NOT_FOUND" else "The request could not be processed."
        return JSONResponse(error_body(code, message), status_code=exc.status_code)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_request: Request, _exc: RequestValidationError):
        return JSONResponse(error_body("INVALID_IMAGE", "The request is missing a valid image."), status_code=400)

    @app.exception_handler(Exception)
    async def _unexpected(_request: Request, exc: Exception):
        log.warning("unhandled %s", type(exc).__name__)
        return JSONResponse(error_body("INFERENCE_FAILED", "Internal server error."), status_code=500)

    # ---- routes ----------------------------------------------------------

    @app.get("/health")
    def health():
        return {
            "status": app.state.status,
            "model": MODEL_NAME,
            "queue_length": queue.queue_length,
            "busy": queue.busy,
            "version": API_VERSION,
        }

    @app.post("/api/v1/jobs", status_code=202)
    async def create_job(image: Optional[UploadFile] = File(None), caption: Optional[str] = Form(None)):
        if app.state.status != "ready" or app.state.explain_fn is None:
            raise ApiError(503, "MODEL_NOT_READY", "The model is not ready yet. Try again in a minute.")
        if image is None:
            raise ApiError(400, "INVALID_IMAGE", "No image was uploaded.")
        caption = (caption or "").strip()
        if len(caption) > config.max_caption_chars:
            raise ApiError(413, "TOO_LARGE", f"The caption is longer than {config.max_caption_chars} characters.")
        data = await image.read(config.max_upload_bytes + 1)
        await image.close()
        if len(data) > config.max_upload_bytes:
            raise ApiError(413, "TOO_LARGE", "The image is larger than 10 MB.")
        if not data:
            raise ApiError(400, "INVALID_IMAGE", "The uploaded file is empty.")
        validate_image(data)
        try:
            job = queue.submit(data, caption)
        except QueueFull:
            raise ApiError(429, "QUEUE_FULL", "The server queue is full. Try again in a minute.") from None
        return {"job_id": job.job_id, "status": "queued", "position": queue.position(job.job_id)}

    @app.get("/api/v1/jobs/{job_id}")
    def get_job(job_id: str):
        snap = queue.snapshot(job_id)
        if snap is None:
            raise ApiError(404, "NOT_FOUND", "No job with this id (it may have expired or the server restarted).")
        return snap

    app.add_middleware(_GuardMiddleware, api_key=api_key, max_body_bytes=config.max_upload_bytes + 64 * 1024)
    return app
