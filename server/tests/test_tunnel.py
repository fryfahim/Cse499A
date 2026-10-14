"""Tunnel helpers. The ngrok SDK is replaced by a FAKE module (no network)."""

import pytest

from colab_runtime import choose_api_key, colab_secret, start_ngrok


class FakeListener:
    def __init__(self, url):
        self._url = url

    def url(self):
        return self._url


class FakeNgrok:
    def __init__(self, url="https://abc-123.ngrok-free.app"):
        self.calls = []
        self.disconnected = []
        self._url = url

    def forward(self, addr, **options):
        self.calls.append((addr, options))
        return FakeListener(self._url)

    def disconnect(self, url):
        self.disconnected.append(url)


def test_start_ngrok_with_static_domain():
    fake = FakeNgrok("https://my-demo.ngrok-free.app")
    tunnel = start_ngrok(8000, authtoken="tok", domain=" https://my-demo.ngrok-free.app/ ", ngrok_module=fake)
    assert fake.calls == [("127.0.0.1:8000", {"authtoken": "tok", "domain": "my-demo.ngrok-free.app"})]
    assert tunnel.url == "https://my-demo.ngrok-free.app"
    tunnel.stop()
    assert fake.disconnected == ["https://my-demo.ngrok-free.app"]


def test_start_ngrok_random_domain_forces_https():
    fake = FakeNgrok("http://random.ngrok-free.app")
    tunnel = start_ngrok(8001, authtoken="tok", ngrok_module=fake)
    assert fake.calls == [("127.0.0.1:8001", {"authtoken": "tok"})]
    assert tunnel.url == "https://random.ngrok-free.app"


def test_start_ngrok_needs_token():
    with pytest.raises(ValueError):
        start_ngrok(8000, authtoken=None, ngrok_module=FakeNgrok())


def test_api_key_choice():
    assert choose_api_key("a-fixed-key-that-is-long-enough") == "a-fixed-key-that-is-long-enough"
    short = choose_api_key("short")
    assert short != "short" and len(short) >= 24
    assert len(choose_api_key(None)) >= 24


def test_colab_secret_outside_colab_is_none():
    assert colab_secret("NGROK_AUTHTOKEN") is None


class LoopAwareFakeNgrok(FakeNgrok):
    """Mimics the real SDK: inside a running event loop forward() returns an
    unfinished asyncio.Task (what broke section 9 in Colab)."""

    def forward(self, addr, **options):
        import asyncio

        self.calls.append((addr, options))
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return FakeListener(self._url)

        async def _open():
            return FakeListener(self._url)

        return loop.create_task(_open())


def test_start_ngrok_inside_running_event_loop_like_colab():
    import asyncio

    fake = LoopAwareFakeNgrok("https://my-demo.ngrok-free.app")

    async def cell():  # a Colab cell runs while an event loop is running
        return start_ngrok(8000, authtoken="tok", domain="my-demo.ngrok-free.app", ngrok_module=fake)

    tunnel = asyncio.run(cell())
    assert tunnel.url == "https://my-demo.ngrok-free.app"


def test_start_ngrok_awaits_coroutine_result():
    class CoroFakeNgrok(FakeNgrok):
        def forward(self, addr, **options):
            async def _open():
                return FakeListener(self._url)
            return _open()

    tunnel = start_ngrok(8000, authtoken="tok", ngrok_module=CoroFakeNgrok("https://x.ngrok-free.app"))
    assert tunnel.url == "https://x.ngrok-free.app"


def test_start_ngrok_errors_propagate():
    class FailingNgrok(FakeNgrok):
        def forward(self, addr, **options):
            raise RuntimeError("ERR_NGROK_334 domain is already in use")

    with pytest.raises(RuntimeError, match="ERR_NGROK_334"):
        start_ngrok(8000, authtoken="tok", ngrok_module=FailingNgrok())
