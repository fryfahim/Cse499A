"""Helpers the Colab cells call: run uvicorn in a background thread, start a
cloudflared quick tunnel, and print the URL + API key + QR code.

Nothing here touches torch or transformers.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import stat
import subprocess
import threading
import time
import urllib.request
from typing import Optional

CLOUDFLARED_URL = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
CLOUDFLARED_BIN = "/content/cloudflared"
TUNNEL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")


def new_api_key() -> str:
    return secrets.token_urlsafe(24)


class ServerHandle:
    def __init__(self, server, thread, port):
        self.server = server
        self.thread = thread
        self.port = port

    def stop(self, timeout: float = 10) -> None:
        self.server.should_exit = True
        self.thread.join(timeout)


def start_server(app, port: int = 8000, wait_seconds: float = 30) -> ServerHandle:
    """Start uvicorn in a daemon thread inside this Python process, so the
    already-loaded model is reused (no reload per request)."""
    import uvicorn

    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", access_log=False)
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, name="uvicorn", daemon=True)
    thread.start()
    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        if server.started:
            return ServerHandle(server, thread, port)
        if not thread.is_alive():
            break
        time.sleep(0.1)
    raise RuntimeError(f"uvicorn did not start on port {port} (is the port already in use?)")


def local_health(port: int = 8000) -> dict:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=10) as r:
        return json.loads(r.read().decode())


def ensure_cloudflared(path: str = CLOUDFLARED_BIN) -> str:
    found = shutil.which("cloudflared")
    if found:
        return found
    if not os.path.exists(path):
        urllib.request.urlretrieve(CLOUDFLARED_URL, path)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
    return path


class Tunnel:
    def __init__(self, process, url, log_path):
        self.process = process
        self.url = url
        self.log_path = log_path

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()


def start_cloudflared(port: int = 8000, timeout: float = 60, log_path: str = "/content/cloudflared.log") -> Tunnel:
    """Start a cloudflared quick tunnel (no account) and return its public URL."""
    binary = ensure_cloudflared()
    log = open(log_path, "w")
    proc = subprocess.Popen(
        [binary, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"],
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    deadline = time.time() + timeout
    while time.time() < deadline:
        with open(log_path) as f:
            match = TUNNEL_RE.search(f.read())
        if match:
            return Tunnel(proc, match.group(0), log_path)
        if proc.poll() is not None:
            break
        time.sleep(0.5)
    proc.terminate()
    raise RuntimeError(f"cloudflared did not report a URL; see {log_path}")


def start_ngrok(port: int = 8000) -> str:
    """Alternative tunnel. Reads NGROK_AUTHTOKEN from Colab Secrets."""
    from google.colab import userdata  # type: ignore
    from pyngrok import ngrok  # type: ignore

    ngrok.set_auth_token(userdata.get("NGROK_AUTHTOKEN"))
    return ngrok.connect(port, "http").public_url.replace("http://", "https://")


def public_health(url: str, timeout: float = 90) -> dict:
    """Poll the public URL until /health answers (DNS for a fresh quick
    tunnel can take a few seconds)."""
    deadline = time.time() + timeout
    last_error: Optional[Exception] = None
    while time.time() < deadline:
        try:
            req = urllib.request.Request(f"{url}/health", headers={"User-Agent": "rocov2-selftest"})
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read().decode())
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            time.sleep(3)
    raise RuntimeError(f"public /health did not answer: {type(last_error).__name__}")


def connection_payload(url: str, key: str) -> str:
    return json.dumps({"url": url, "key": key}, separators=(",", ":"))


def show_connection(url: str, key: str) -> None:
    """Print URL and key, and display a QR code holding {"url", "key"}."""
    import qrcode

    payload = connection_payload(url, key)
    print("Server URL :", url)
    print("API key    :", key)
    print("Scan this QR code in the app (Settings -> Scan QR):")
    qr = qrcode.QRCode(border=2, box_size=6)
    qr.add_data(payload)
    qr.make(fit=True)
    try:
        from IPython.display import display

        display(qr.make_image(fill_color="black", back_color="white").get_image())
    except Exception:  # noqa: BLE001 - not in a notebook: fall back to text
        qr.print_ascii(invert=True)


# ---- demo images -------------------------------------------------------------

# Test-split images for the demo. All are in verified_dataset_232.xlsx.
# "held out" = in the fine-tune notebook's 23 held-out rows (never trained on).
DEMO_SAMPLES = {
    "ROCOv2_2023_test_000384": "held out; in the fine-tune run the model said LEFT for this RIGHT caption -> amber warning expected",
    "ROCOv2_2023_test_000216": "held out; left pulmonary artery, answer was consistent",
    "ROCOv2_2023_test_000006": "held out; CT abdomen",
    "ROCOv2_2023_test_000010": "held out; very short caption",
    "ROCOv2_2023_test_000018": "seen in training; left pneumothorax, answer was consistent",
}


def fetch_rocov2_samples(
    image_ids,
    out_dir: str,
    repo_id: str = "eltorio/ROCOv2-radiology",
    split: str = "test",
    list_files=None,
    download=None,
) -> dict:
    """Save a few ROCOv2 images (original bytes) and their captions to ``out_dir``.

    Reads the split's parquet shards one at a time and stops as soon as every
    id is found, instead of downloading the whole dataset (tens of GB).
    Each shard is deleted after it is scanned. Returns {image_id: (path, caption)}.
    ``list_files`` / ``download`` are injectable for tests.
    """
    import io
    import pyarrow.parquet as pq
    from PIL import Image

    if list_files is None or download is None:
        from huggingface_hub import hf_hub_download, list_repo_files

        list_files = list_files or (lambda: list_repo_files(repo_id, repo_type="dataset"))
        download = download or (
            lambda name: hf_hub_download(repo_id, name, repo_type="dataset", local_dir="/content/_rocov2_shards")
        )

    wanted = set(image_ids)
    found: dict = {}
    os.makedirs(out_dir, exist_ok=True)
    shards = sorted(f for f in list_files() if f.startswith(f"data/{split}-") and f.endswith(".parquet"))
    for shard in shards:
        path = download(shard)
        try:
            parquet = pq.ParquetFile(path)
            for batch in parquet.iter_batches(columns=["image_id", "caption", "image"], batch_size=64):
                cols = batch.to_pydict()
                for image_id, caption, image in zip(cols["image_id"], cols["caption"], cols["image"]):
                    if image_id not in wanted or image_id in found:
                        continue
                    data = image["bytes"]
                    with Image.open(io.BytesIO(data)) as probe:
                        ext = {"JPEG": "jpg", "PNG": "png", "WEBP": "webp"}.get(probe.format, "img")
                    target = os.path.join(out_dir, f"{image_id}.{ext}")
                    with open(target, "wb") as f:
                        f.write(data)
                    found[image_id] = (target, caption or "")
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
        if wanted <= found.keys():
            break
    with open(os.path.join(out_dir, "captions.txt"), "w", encoding="utf-8") as f:
        for image_id in image_ids:
            if image_id in found:
                f.write(f"{image_id}\n{found[image_id][1]}\n\n")
    return found
