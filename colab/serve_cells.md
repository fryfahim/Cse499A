# Colab cells: serve the model to the Android app

Paste these cells into `ROCOv2_Bangla_Qwen35_4B_inference_app (2).ipynb`
**after section 4**, i.e. after the cells that:

1. mount Google Drive,
2. load the adapter with
   `FastVisionModel.from_pretrained(ADAPTER_PATH, load_in_4bit=False)` and run
   `FastVisionModel.for_inference(model)`,
3. define `PROMPT_WITH_CAPTION`, `PROMPT_IMAGE_ONLY`, `explain()`,
   `side_check()` and `DISCLAIMER_BN`.

Do **not** launch the notebook's Gradio UI in the same session: two front-ends
would compete for the one T4 GPU.

The cells reuse the model already in memory. They never reload it and never
install, upgrade or reinstall torch or transformers.

Runtime: **T4 GPU**. Run the cells in order: S1 → S2 → S3 → S4 (optional)
→ S5 → S6 (optional).

---

## S1: Check that the notebook objects exist

```python
# S1: check the objects the server reuses from the notebook
import inspect
import importlib.metadata as md

missing = [n for n in ("model", "tokenizer", "PROMPT_WITH_CAPTION", "PROMPT_IMAGE_ONLY", "side_check", "explain")
           if n not in globals()]
assert not missing, f"Run the notebook's model cells first. Missing: {missing}"

print("side_check signature:", inspect.signature(side_check))   # expected: (caption, bangla)
print("explain signature   :", inspect.signature(explain))
print("caption placeholder :", "{caption}" in PROMPT_WITH_CAPTION or PROMPT_WITH_CAPTION.count("{}") == 1)
print("image-only prompt   :", len(PROMPT_IMAGE_ONLY), "chars")

PINNED_BEFORE = {p: md.version(p) for p in ("torch", "transformers", "trl")}
print("pinned stack        :", PINNED_BEFORE)
```

If `caption placeholder` prints `False`, the notebook inserts the caption some
other way. Then, in S3, pass your own `prompt_fn=lambda caption: ...` that
builds the prompt exactly as the notebook's `explain()` does.

---

## S2: Install the server packages and fetch the server code

```python
# S2: install fastapi/uvicorn/python-multipart/qrcode WITHOUT touching torch/transformers
import re, subprocess, sys, importlib.metadata as md

SERVER_PKGS = ["fastapi>=0.110", "uvicorn>=0.29", "python-multipart>=0.0.9", "qrcode[pil]>=7.4"]
UPGRADABLE = {"fastapi", "starlette", "uvicorn", "python-multipart", "qrcode"}

# Pin every package that is already installed to its current version, so pip
# can only ADD packages. torch, transformers, trl, unsloth etc. stay as they are.
freeze = subprocess.run([sys.executable, "-m", "pip", "freeze"], capture_output=True, text=True).stdout
pins = [line for line in freeze.splitlines()
        if re.match(r"^[A-Za-z0-9_.\-]+==", line)
        and line.split("==")[0].lower().replace("_", "-") not in UPGRADABLE]
with open("/content/server_constraints.txt", "w") as f:
    f.write("\n".join(pins) + "\n")

r = subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-c", "/content/server_constraints.txt", *SERVER_PKGS],
                   capture_output=True, text=True)
print(r.stdout[-2000:], r.stderr[-2000:])
assert r.returncode == 0, "pip install failed - see the message above (nothing was upgraded)"

after = {p: md.version(p) for p in PINNED_BEFORE}
assert after == PINNED_BEFORE, f"pinned stack changed! before={PINNED_BEFORE} after={after}"
print("OK, torch/transformers/trl unchanged:", after)

# Fetch the server code (FastAPI app + worker) from the project repository.
REPO = "https://github.com/fryfahim/Cse499A.git"
REPO_REF = "claude/rocov2-bangla-android-app-1mhf6u"   # change to "main" once merged
!rm -rf /content/rocov2_app && git clone -q --depth 1 -b {REPO_REF} {REPO} /content/rocov2_app
sys.path.insert(0, "/content/rocov2_app/server")
print("server code at /content/rocov2_app/server")
```

---

## S3: Configure and bind the model

```python
# S3: server configuration + model binding
import importlib, app as server_app, inference, worker, colab_runtime
for m in (worker, inference, server_app, colab_runtime):
    importlib.reload(m)   # lets you re-run this cell after a `git pull`

from app import ServerConfig, create_app, DISCLAIMER_BN as SERVER_DISCLAIMER_BN
from inference import make_explain_fn, default_prompt_fn, IMAGE_FIRST, TEXT_FIRST

# ---- configuration flags -------------------------------------------------
MAX_IMG_SIDE  = 512          # training used img.thumbnail((512, 512)); None = full resolution like the old notebook
CONTENT_ORDER = IMAGE_FIRST  # IMAGE_FIRST = inference notebook order (tested); TEXT_FIRST = training order
PORT          = 8000
TUNNEL        = "cloudflared"  # or "ngrok" (needs NGROK_AUTHTOKEN in Colab Secrets)
# ---------------------------------------------------------------------------

if "DISCLAIMER_BN" in globals() and DISCLAIMER_BN != SERVER_DISCLAIMER_BN:
    print("WARNING: the notebook's DISCLAIMER_BN differs from the server's copy. Check app.py.")

# The prompts are the notebook's own string objects, never retyped.
prompt_fn = default_prompt_fn(PROMPT_WITH_CAPTION, PROMPT_IMAGE_ONLY)
explain_fn = make_explain_fn(model, tokenizer, prompt_fn, content_order=CONTENT_ORDER)
config = ServerConfig(max_img_side=MAX_IMG_SIDE)
print("bound. image shrink:", MAX_IMG_SIDE, "| content order:", CONTENT_ORDER)
```

---

## S4 (optional, recommended once): parity check and latency measurement

This proves the server path produces **the same text** as the notebook's own
`explain()`, and it measures real latency on this GPU. Upload one ROCOv2 test
image and paste its English caption. It runs the model three times, which on a
T4 in float32 can take several minutes.

```python
# S4: compare the server path with the notebook's explain(), and time both
import io, time
from PIL import Image, ImageOps
from google.colab import files

uploaded = files.upload()                       # pick e.g. ROCOv2_2023_test_000018.jpg
PARITY_NAME = next(iter(uploaded))
PARITY_BYTES = uploaded[PARITY_NAME]
PARITY_CAPTION = ""                             # paste the image's English caption here

img_full = Image.open(io.BytesIO(PARITY_BYTES)).convert("RGB")

t0 = time.time()
theirs = explain(img_full, PARITY_CAPTION)       # adjust this call if the S1 signature differs
t1 = time.time()
ours_fullres = make_explain_fn(model, tokenizer, prompt_fn, content_order=IMAGE_FIRST)(img_full, PARITY_CAPTION)
t2 = time.time()
img_512 = server_app.preprocess_image(Image.open(io.BytesIO(PARITY_BYTES)), MAX_IMG_SIDE)
ours_server = explain_fn(img_512, PARITY_CAPTION)
t3 = time.time()

theirs_text = theirs if isinstance(theirs, str) else " ".join(x for x in theirs if isinstance(x, str))
print("Same output as notebook explain() (full res, image-first):",
      ours_fullres == theirs_text.strip() or ours_fullres in theirs_text)
print(f"notebook explain(): {t1 - t0:.1f}s | server path full-res: {t2 - t1:.1f}s | server path {MAX_IMG_SIDE}px: {t3 - t2:.1f}s")
print("\nserver output (what the app will show):\n", ours_server)
print("\nside_check:", side_check(PARITY_CAPTION, ours_server))
```

If `Same output` prints `False`, **stop** and compare `prompt_fn(PARITY_CAPTION)`
with the prompt that `explain()` builds (`inspect.getsource(explain)`). Fix
the difference before serving. The outputs at 512 px and at full resolution
are allowed to differ; that difference is the point of the `MAX_IMG_SIDE` flag.

---

## S5: Start the API, the tunnel and print URL + key + QR

```python
# S5: start uvicorn (background thread) + public tunnel, print URL, key and QR code
from colab_runtime import (new_api_key, start_server, local_health, start_cloudflared,
                           start_ngrok, public_health, show_connection)

# Re-running this cell: stop the previous server and tunnel first.
for _name in ("SERVER", "TUNNEL_PROC"):
    try:
        globals()[_name].stop()
    except (KeyError, AttributeError, NameError):
        pass

API_KEY = new_api_key()                      # new random key for every server start
fastapi_app = create_app(explain_fn, side_check, api_key=API_KEY, config=config)
SERVER = start_server(fastapi_app, port=PORT)
print("local /health:", local_health(PORT))

if TUNNEL == "ngrok":
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-c", "/content/server_constraints.txt", "pyngrok"], check=True)
    PUBLIC_URL = start_ngrok(PORT)
else:
    TUNNEL_PROC = start_cloudflared(PORT)
    PUBLIC_URL = TUNNEL_PROC.url

print("public /health:", public_health(PUBLIC_URL))
show_connection(PUBLIC_URL, API_KEY)
print("\nKeep this tab open. The URL and key change every time you re-run this cell.")
```

In the app: **Settings → Scan QR** (or paste the URL and key) → **Test connection**.

---

## S6 (optional): one real job through the public URL

```python
# S6: submit one real job through the tunnel and measure end-to-end latency
import requests, time

if "PARITY_BYTES" not in globals():
    from google.colab import files
    _up = files.upload(); PARITY_NAME = next(iter(_up)); PARITY_BYTES = _up[PARITY_NAME]; PARITY_CAPTION = ""

hdr = {"X-API-Key": API_KEY}
t0 = time.time()
r = requests.post(f"{PUBLIC_URL}/api/v1/jobs", headers=hdr,
                  files={"image": (PARITY_NAME, PARITY_BYTES)}, data={"caption": PARITY_CAPTION}, timeout=60)
print(r.status_code, r.json())
job_id = r.json()["job_id"]
while True:
    s = requests.get(f"{PUBLIC_URL}/api/v1/jobs/{job_id}", headers=hdr, timeout=30).json()
    if s["status"] in ("completed", "failed"):
        break
    time.sleep(2)
print(f"end-to-end: {time.time() - t0:.1f}s")
print(s["status"], s["error"] or s["result"]["side_check"])
if s["result"]:
    print(f"model time (elapsed_ms): {s['result']['elapsed_ms']} ms")
    print(s["result"]["bangla_text"])
```

---

## Stop the server

```python
SERVER.stop()
try:
    TUNNEL_PROC.stop()
except NameError:
    pass
```

## Notes

* **Disconnects:** Colab stops idle runtimes and caps sessions at about 12 h.
  After a disconnect, re-run the notebook's model cells and then S1–S5. The
  tunnel URL and API key will be new, so scan the new QR code in the app.
* **Privacy:** uploads are held in memory only and dropped after inference.
  The server does not log images, captions or outputs. cloudflared's quick
  tunnel still routes traffic through Cloudflare, so do not use real patient
  images.
* **cloudflared** is downloaded from Cloudflare's GitHub releases to
  `/content/cloudflared`; its log is in `/content/cloudflared.log`.
