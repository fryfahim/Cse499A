"""Shared fixtures.

MOCK NOTICE: every test here uses a FAKE explain_fn instead of the real
Qwen3.5-4B + LoRA model. These tests check the HTTP contract, validation,
queueing and error handling only - not model quality. Real end-to-end runs
need a live Colab GPU session (see colab/serve_cells.md).
"""

import io
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import GpuHooks, ServerConfig, create_app  # noqa: E402

API_KEY = "test-key-123"
AUTH = {"X-API-Key": API_KEY}


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class MockExplain:
    """MOCK model: records what it was called with and returns canned text."""

    def __init__(self, text="মক উত্তর: এটি বুকের এক্স-রে।"):
        self.text = text
        self.calls = []
        self.error = None

    def __call__(self, image, caption):
        self.calls.append({"size": image.size, "mode": image.mode, "caption": caption})
        if self.error is not None:
            raise self.error
        return self.text


def mock_side_check(caption, bangla):
    if not caption:
        return "No caption provided - side check skipped."
    if "left" in caption.lower() and "ডান" in bangla:
        return "WARNING: caption says left but the answer says right."
    return "Side check passed."


def image_bytes(fmt="JPEG", size=(64, 48), color=(120, 120, 120), exif=None):
    buf = io.BytesIO()
    img = Image.new("RGB", size, color)
    kwargs = {}
    if exif is not None:
        kwargs["exif"] = exif
    img.save(buf, format=fmt, **kwargs)
    return buf.getvalue()


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def explain():
    return MockExplain()


class OOMError(RuntimeError):
    """Stands in for torch.cuda.OutOfMemoryError (no torch in the test env)."""


@pytest.fixture
def gpu():
    hooks = GpuHooks(oom_types=(OOMError,))
    hooks.empty_cache_calls = 0

    def empty_cache():
        hooks.empty_cache_calls += 1

    hooks.empty_cache = empty_cache
    return hooks


@pytest.fixture
def make_client(explain, clock, gpu):
    def _make(explain_fn=explain, config=None, side_check_fn=mock_side_check, start_worker=False):
        app = create_app(
            explain_fn,
            side_check_fn,
            api_key=API_KEY,
            config=config or ServerConfig(),
            gpu=gpu,
            clock=clock,
            start_worker=start_worker,
        )
        client = TestClient(app, raise_server_exceptions=False)
        client.queue = app.state.queue
        client.fastapi_app = app
        return client

    return _make


@pytest.fixture
def client(make_client):
    return make_client()


def submit(client, data=None, caption=None, filename="x.jpg", mime="image/jpeg", headers=AUTH):
    files = {"image": (filename, data if data is not None else image_bytes(), mime)}
    form = {"caption": caption} if caption is not None else {}
    return client.post("/api/v1/jobs", files=files, data=form, headers=headers)
