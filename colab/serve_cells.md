# Colab cells: serve the model to the Android app

The easiest way is to open **`colab/ROCOv2_Bangla_API_server.ipynb`** in Colab and run it top to bottom. It already contains sections 1–4 of `ROCOv2_Bangla_Qwen35_4B_inference_app.ipynb` unchanged.

If you prefer your existing inference notebook, run its sections 1–4 (install, settings, Drive + model, prompt + `explain()` + `side_check()`), skip sections 5–7 (upload, image ID, Gradio), and paste these cells after section 4.

**ngrok setup (once):** sign up at ngrok.com, copy your authtoken (*Getting Started › Your Authtoken*) and claim your free static domain (*Domains*). In Colab open **Secrets** (key icon, left sidebar) and add `NGROK_AUTHTOKEN` (required), `NGROK_DOMAIN` (optional, e.g. `something.ngrok-free.app`) and `APP_API_KEY` (optional, 16+ random characters), each with **Notebook access** on.

### 5. Install the server packages and get the server code
Installs `fastapi`, `uvicorn`, `python-multipart`, `qrcode` and `ngrok` (the official ngrok Python SDK) with every package that is already installed pinned to its current version, so torch, transformers, trl and unsloth cannot change. Then it downloads the server code from GitHub.

```python
import re, subprocess, sys, importlib.metadata as md

PINNED_BEFORE = {p: md.version(p) for p in ("torch", "transformers", "trl", "unsloth")}

SERVER_PKGS = ["fastapi>=0.110", "uvicorn>=0.29", "python-multipart>=0.0.9", "qrcode[pil]>=7.4", "ngrok>=1.4"]
UPGRADABLE = {"fastapi", "starlette", "uvicorn", "python-multipart", "qrcode", "ngrok"}
freeze = subprocess.run([sys.executable, "-m", "pip", "freeze"], capture_output=True, text=True).stdout
pins = [line for line in freeze.splitlines()
        if re.match(r"^[A-Za-z0-9_.\-]+==", line)
        and line.split("==")[0].lower().replace("_", "-") not in UPGRADABLE]
with open("/content/server_constraints.txt", "w") as f:
    f.write("\n".join(pins) + "\n")
r = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-c", "/content/server_constraints.txt", *SERVER_PKGS],
                   capture_output=True, text=True)
print(r.stdout[-1500:], r.stderr[-1500:])
assert r.returncode == 0, "pip install failed (nothing was upgraded). See the message above."
after = {p: md.version(p) for p in PINNED_BEFORE}
assert after == PINNED_BEFORE, f"Pinned stack changed! before={PINNED_BEFORE} after={after}"
print("OK. Model stack unchanged:", after)

# Server code (FastAPI app, job queue, model wrapper) from the project repository.
REPO = "https://github.com/fryfahim/Cse499A.git"
REPO_REF = "claude/rocov2-bangla-android-app-1mhf6u"   # change to "main" after the pull request is merged
subprocess.run(["rm", "-rf", "/content/rocov2_app"], check=True)
subprocess.run(["git", "clone", "-q", "--depth", "1", "-b", REPO_REF, REPO, "/content/rocov2_app"], check=True)
if "/content/rocov2_app/server" not in sys.path:
    sys.path.insert(0, "/content/rocov2_app/server")
print("Server code ready in /content/rocov2_app/server")
```

### 6. Check the server code against this notebook and connect the model
The server uses its own byte-for-byte copy of the prompts, `DISCLAIMER_BN` and `side_check()`. This cell stops with an error if they differ from the ones defined above. It then wraps the already-loaded `model` and `tokenizer` (no reload).

Settings:
* `MAX_IMG_SIDE = 512`: the server shrinks images exactly like training did (`convert("RGB")` then `thumbnail((512, 512))`), which is also how the fine-tune notebook evaluated the held-out rows. `None` = full resolution, like the old upload cell.
* `CONTENT_ORDER = IMAGE_FIRST`: the order used by `explain()` above. `TEXT_FIRST` = the order of the training conversations.

```python
import importlib
import notebook_logic, worker, inference, app as server_app, colab_runtime
for _m in (notebook_logic, worker, inference, server_app, colab_runtime):
    importlib.reload(_m)   # picks up a fresh `git clone` when you re-run cell 5
from inference import make_explain_fn, IMAGE_FIRST, TEXT_FIRST
from app import ServerConfig, create_app

assert notebook_logic.PROMPT_WITH_CAPTION == PROMPT_WITH_CAPTION, "PROMPT_WITH_CAPTION differs"
assert notebook_logic.PROMPT_IMAGE_ONLY == PROMPT_IMAGE_ONLY, "PROMPT_IMAGE_ONLY differs"
assert notebook_logic.DISCLAIMER_BN == DISCLAIMER_BN, "DISCLAIMER_BN differs"
for _cap in ["", "  Large left-sided pneumothorax ", "Focal right upper lobar opacity on chest X-ray"]:
    assert notebook_logic.make_prompt(_cap) == make_prompt(_cap)
    for _bn in ["বাম ফুসফুসে সমস্যা", "ডান ফুসফুসে সমস্যা", "বুকের ছবি"]:
        assert notebook_logic.side_check(_cap, _bn) == side_check(_cap, _bn)
print("Server prompts, disclaimer and side_check() are identical to this notebook.")

# ---- server settings --------------------------------------------------------
MAX_IMG_SIDE  = 512           # None = full resolution
CONTENT_ORDER = IMAGE_FIRST   # or TEXT_FIRST
PORT          = 8000
TUNNEL        = "ngrok"       # "ngrok" (Colab secrets NGROK_AUTHTOKEN, optional NGROK_DOMAIN) or "cloudflared" (no account)
# -----------------------------------------------------------------------------

explain_fn = make_explain_fn(model, tokenizer, content_order=CONTENT_ORDER)
config = ServerConfig(max_img_side=MAX_IMG_SIDE)
print("Model connected. Image shrink:", MAX_IMG_SIDE, "| content order:", CONTENT_ORDER)
```

### 7. (Optional) Get demo images onto your phone
Downloads a few ROCOv2 test images by ID, plus their English captions, into Google Drive folder **`rocov2_demo_images`**. It reads the dataset one shard (~450 MB) at a time and stops as soon as it has them, so the full dataset (tens of GB) is not downloaded.

On the phone, open the Google Drive app → `rocov2_demo_images` → ⋮ → **Download** for each image. The captions are in `captions.txt`; copy the matching one into the app.

`ROCOv2_2023_test_000384` is a real left/right failure from the fine-tune notebook's held-out results. The caption says RIGHT, the model answered বাম (LEFT), and the app should show the **amber** warning.

```python
from colab_runtime import DEMO_SAMPLES, fetch_rocov2_samples

DEMO_DIR = "/content/drive/MyDrive/rocov2_demo_images"
found = fetch_rocov2_samples(list(DEMO_SAMPLES), DEMO_DIR)
for image_id, (path, caption) in found.items():
    print(f"{image_id}  ({DEMO_SAMPLES[image_id]})\n   file:    {path}\n   caption: {caption}\n")
missing = set(DEMO_SAMPLES) - set(found)
if missing:
    print("Not found:", missing)
```

### 8. (Optional, recommended once) Same answer as `explain()`? How long does it take?
Runs the model three times on one image: the notebook's own `explain()`, the server code at full resolution, and the server code with the 512 px shrink. The first two must give **identical** text. The times are real measurements on this GPU; nothing about speed is assumed. In float32 on a T4 this can take a few minutes.

It uses `ROCOv2_2023_test_000384` from section 7 if you ran it, otherwise it asks you to upload an image.

```python
import io, time
from PIL import Image

if "found" in globals() and "ROCOv2_2023_test_000384" in found:
    _path, PARITY_CAPTION = found["ROCOv2_2023_test_000384"]
    PARITY_BYTES = open(_path, "rb").read()
else:
    from google.colab import files
    _up = files.upload()
    PARITY_BYTES = _up[next(iter(_up))]
    PARITY_CAPTION = input("English caption (optional, press Enter to skip): ")

t0 = time.time()
theirs = explain(Image.open(io.BytesIO(PARITY_BYTES)), PARITY_CAPTION)
t1 = time.time()
ours_full = make_explain_fn(model, tokenizer, content_order=IMAGE_FIRST)(Image.open(io.BytesIO(PARITY_BYTES)), PARITY_CAPTION)
t2 = time.time()
ours_server = explain_fn(server_app.preprocess_image(Image.open(io.BytesIO(PARITY_BYTES)), MAX_IMG_SIDE), PARITY_CAPTION)
t3 = time.time()

print("Server code == notebook explain() (full resolution):", ours_full == theirs)
print(f"Seconds: explain() {t1 - t0:.1f} | server code full-res {t2 - t1:.1f} | server code {MAX_IMG_SIDE}px {t3 - t2:.1f}")
print("\nWhat the app will show:\n", ours_server)
print("\nside_check:", side_check(PARITY_CAPTION, ours_server))
assert ours_full == theirs, "Server code does not match explain(). Do not serve until this is fixed."
```

### 9. Start the API and show the QR code
Starts the server in the background (the notebook stays usable), opens the **ngrok** HTTPS tunnel, checks `/health` locally and through the public URL, and prints the **URL**, the **API key** and a **QR code**.

In the app: **Settings → Scan QR** (or paste the URL and key) → **Test connection**.

* URL: your `NGROK_DOMAIN` if set (the same every time), otherwise a new random ngrok URL on every run.
* API key: your `APP_API_KEY` secret if set (the same every time), otherwise a new random key on every run.
* With both secrets set you scan the QR code **once**, and after a Colab restart the app reconnects by itself.

```python
import subprocess, sys
from colab_runtime import (choose_api_key, colab_secret, start_server, local_health,
                           start_cloudflared, start_ngrok, public_health, show_connection)

for _name in ("SERVER", "TUNNEL_PROC"):   # re-running: stop the previous server and tunnel
    try:
        globals()[_name].stop()
    except (KeyError, AttributeError):
        pass

API_KEY = choose_api_key(colab_secret("APP_API_KEY"))
fastapi_app = create_app(explain_fn, side_check, api_key=API_KEY, config=config)
SERVER = start_server(fastapi_app, port=PORT)
print("local /health:", local_health(PORT))

if TUNNEL == "ngrok":
    _token = colab_secret("NGROK_AUTHTOKEN")
    if not _token:
        SERVER.stop()
        raise RuntimeError("Add your ngrok authtoken as the Colab secret NGROK_AUTHTOKEN (key icon, left sidebar) "
                           "and turn on Notebook access. See the first cell of this notebook.")
    TUNNEL_PROC = start_ngrok(PORT, authtoken=_token, domain=colab_secret("NGROK_DOMAIN"))
    PUBLIC_URL = TUNNEL_PROC.url
else:
    TUNNEL_PROC = start_cloudflared(PORT)
    PUBLIC_URL = TUNNEL_PROC.url

print("public /health:", public_health(PUBLIC_URL))
show_connection(PUBLIC_URL, API_KEY)
print("\nKeep this tab open while you use the app.")
```

### 10. (Optional) Send one real job through the public URL
The same request the app makes. Prints the end-to-end time and the server's model time.

```python
import requests, time

if "PARITY_BYTES" not in globals():
    from google.colab import files
    _up = files.upload(); PARITY_BYTES = _up[next(iter(_up))]
    PARITY_CAPTION = input("English caption (optional): ")

hdr = {"X-API-Key": API_KEY, "ngrok-skip-browser-warning": "true"}
t0 = time.time()
r = requests.post(f"{PUBLIC_URL}/api/v1/jobs", headers=hdr, timeout=60,
                  files={"image": ("image.jpg", PARITY_BYTES)}, data={"caption": PARITY_CAPTION})
print(r.status_code, r.json())
job_id = r.json()["job_id"]
while True:
    s = requests.get(f"{PUBLIC_URL}/api/v1/jobs/{job_id}", headers=hdr, timeout=30).json()
    if s["status"] in ("completed", "failed"):
        break
    time.sleep(2)
print(f"end-to-end: {time.time() - t0:.1f} s")
if s["result"]:
    res = s["result"]
    print(f"model time: {res['elapsed_ms'] / 1000:.1f} s | mode: {res['mode']} | side check: {res['side_check']['level']}")
    print(res["bangla_text"])
else:
    print("failed:", s["error"])
```

### Stop the server

```python
SERVER.stop()
try:
    TUNNEL_PROC.stop()
except NameError:
    pass
print("Server and tunnel stopped. The app will show Offline.")
```

### Notes
* **Colab disconnects** after idling and after about 12 hours. Then run everything again from the top. The adapter on Google Drive stays. With `NGROK_DOMAIN` and `APP_API_KEY` set, the app reconnects by itself; otherwise scan the new QR code.
* **ngrok free plan** has monthly limits on requests and data, shown in your ngrok dashboard. The app checks `/health` every 10 s while it is open and polls every 2 s during a job. Close the app when you are not using it.
* **One tunnel per domain:** if section 9 fails with "domain is already in use", the old session is still connected. Run *Stop the server*, wait a few seconds and run section 9 again (or restart the runtime).
* **Privacy:** uploads are held in memory only and dropped after inference. The server does not log images, captions or answers. Traffic still passes through ngrok's public servers, so do not use real patient images.
* Alternative without an account: set `TUNNEL = "cloudflared"` in section 6 (random URL on every run; log in `/content/cloudflared.log`).
