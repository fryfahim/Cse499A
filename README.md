# ROCOv2 Bangla: radiology image → simple Bangla explanation

A student research prototype for CSE499A. An Android app sends a radiology
image (and, optionally, its English caption) to our fine-tuned
**Qwen3.5-4B + LoRA** model running in a live Google Colab session. The model
returns 2–3 short, simple Bangla sentences saying which body part is shown
and what problem it has.

> **Not a diagnostic tool.** The output is AI-generated and may be wrong. It
> has no clinical validation and makes no compliance claims (HIPAA or
> otherwise). Google Colab and public tunnels are **not suitable for real
> patient data**, so use only public dataset images such as ROCOv2.

```
Android app ──HTTPS──> cloudflared quick tunnel ──> FastAPI (uvicorn thread in the Colab runtime)
                                                     └─> one GPU worker ──> model.generate() ──> result
```

## Repository layout

```
android/                         Android Studio project (Kotlin, Jetpack Compose, Material 3)
  app/src/main/java/edu/cse499a/rocobangla/
    MainActivity.kt              single activity, edge-to-edge, sets the Compose theme + nav host
    RocoBanglaApp.kt             Application; owns the AppContainer
    core/AppConfig.kt            limits and timings (10 MB, 1000 chars, 2 s poll, 5 min timeout)
    core/AppError.kt             AppError, ErrorAction, and the server-code / HTTP / exception mapping
    core/ServerSettings.kt       URL+key model, https-only URL check, QR payload parser
    data/Dtos.kt                 kotlinx.serialization DTOs for the API
    data/Models.kt               app models (Outcome, JobState, AnalysisResult…) and DTO mapping
    data/RocoApi.kt              Retrofit interface, OkHttp client, per-URL Retrofit factory
    data/NetworkAnalysisRepository.kt  calls the API, maps every failure to an AppError
    data/AnalysisRepository.kt   repository interface (faked in tests)
    data/SettingsRepository.kt   settings interface
    data/DataStoreSettingsRepository.kt  URL + key in DataStore (no backup)
    data/ImageInspector.kt       interfaces for reading picked images and streaming uploads
    data/ContentUriImages.kt     ContentResolver metadata + streaming multipart body (no Base64, no resize)
    data/AppContainer.kt         manual dependency injection
    ui/AppNavHost.kt             Navigation Compose: analyze ↔ settings
    ui/ViewModelFactories.kt     ViewModel factories (SavedStateHandle for the analyze flow)
    ui/analyze/AnalyzeUiState.kt state machine types + connection chip states
    ui/analyze/AnalyzeViewModel.kt  Idle → ImageSelected → Submitting → Queued/Running → Success | Error
    ui/analyze/AnalyzeRoute.kt   picks Home / Analyzing / Result from the phase; photo picker; back handling
    ui/analyze/HomeScreen.kt     connection chip, image picker card, caption field, Analyze, disclaimer
    ui/analyze/AnalyzingScreen.kt  real queue position / status, elapsed time, Stop waiting
    ui/analyze/ResultScreen.kt   Bangla text, left/right banner, mode/time/timestamp, Copy / Again / New
    ui/settings/SettingsViewModel.kt  URL/key editing, QR import, Test connection
    ui/settings/SettingsScreen.kt     server, model info, privacy, limitations, version
    ui/components/Components.kt  cards, chip, disclaimer, side-check banner, error card
    ui/theme/                    colours, typography (bundled Noto Sans Bengali), shapes, spacing
  app/src/main/res/font/         Noto Sans Bengali Regular + SemiBold (SIL OFL 1.1, licence in assets/licenses)
  app/src/test/…                 unit tests: ViewModels, error mapping, URL/QR parsing, DTO mapping, repository (MockWebServer)
server/
  app.py                         FastAPI app; explain_fn and side_check_fn are injected (testable without a GPU)
  worker.py                      FIFO queue (cap 5), single GPU worker thread, in-memory job store, 15 min expiry
  inference.py                   wraps the notebook's model/tokenizer into explain_fn (chat template, greedy decode)
  colab_runtime.py               uvicorn-in-a-thread, cloudflared/ngrok tunnel, URL + key + QR printout
  requirements.txt               server packages for Colab (installed with a constraints file)
  requirements-dev.txt           local test dependencies
  tests/                         pytest suite (uses a clearly labelled MOCK explain_fn)
colab/serve_cells.md             the exact cells to paste after section 4 of the inference notebook
API.md                           the API contract
```

## Setup

### 1. Colab (server)

1. Open `ROCOv2_Bangla_Qwen35_4B_inference_app (2).ipynb` in Colab with a
   **T4 GPU** runtime.
2. Run the notebook up to and including **section 4**: install the pinned
   stack (torch 2.8.0, transformers 5.2.0, trl 0.22.2, unsloth, causal_conv1d
   1.6.0), mount Drive, load the adapter from
   `/content/drive/MyDrive/qwen_lora_bangla_radiology` with
   `FastVisionModel.from_pretrained(ADAPTER_PATH, load_in_4bit=False)` and
   `FastVisionModel.for_inference(model)`, and define the prompts,
   `explain()`, `side_check()` and `DISCLAIMER_BN`. Do not launch the Gradio UI.
3. Paste and run the cells from [`colab/serve_cells.md`](colab/serve_cells.md):
   * **S1** checks that the notebook objects exist and records the torch /
     transformers / trl versions.
   * **S2** installs `fastapi`, `uvicorn`, `python-multipart` and `qrcode`
     with a constraints file built from `pip freeze`, so pip cannot upgrade or
     reinstall torch, transformers or anything else. It then asserts the
     versions did not change and clones this repository for the server code.
   * **S3** binds the already-loaded model. The prompts are the notebook's
     own `PROMPT_WITH_CAPTION` / `PROMPT_IMAGE_ONLY` objects, passed in rather
     than retyped, so they stay byte-for-byte identical. Flags: `MAX_IMG_SIDE`
     (default 512) and `CONTENT_ORDER` (default image first).
   * **S4** (optional, recommended once) proves the server path gives the
     same text as the notebook's `explain()`, and **measures real latency**
     on your GPU.
   * **S5** starts uvicorn in a background thread, starts a cloudflared quick
     tunnel (no account needed), checks `/health` locally and through the
     public URL, and prints the **URL, a new random API key and a QR code**.
   * **S6** (optional) sends one real job through the public URL and prints
     the end-to-end time.

### 2. Phone (app)

1. Open `android/` in Android Studio (a recent stable version with JDK 17+),
   let Gradle sync, and run on a phone (Android 8.0 / API 26 or newer), or run
   `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.
2. In the app: **Settings → Scan QR**, then point the camera at the QR code in
   Colab. You can also paste the URL and key by hand. The app saves both and
   runs **Test connection**, which checks `/health` and that the key is accepted.
3. Back on Home the chip should say **Ready**. Pick an image, paste its
   English caption (optional, but it improves accuracy), and tap **Analyze**.

The URL and key are entered at runtime and stored only on the phone (DataStore,
excluded from backup). They are never compiled into the APK.

## Why the server shrinks images to 512 px (and other differences between the notebooks)

These differences between the fine-tune notebook and the inference notebook
are handled openly:

1. **Image size.** Training ran `img.convert("RGB")` and then
   `img.thumbnail((512, 512))`. The inference notebook sent full-resolution
   images. The model was fine-tuned only on images no larger than 512 px, so
   the server applies the same shrink by default (`MAX_IMG_SIDE = 512`).
   A side benefit is fewer vision tokens, so inference is faster and uses less
   memory. Set `MAX_IMG_SIDE = None` in S3 to reproduce the old notebook
   behaviour exactly.
2. **Content order.** Training put `[text, image]` in the user turn, while
   the inference notebook uses `[image, text]`. The server keeps the
   inference notebook's order (the tested path). `CONTENT_ORDER = TEXT_FIRST`
   in S3 switches to the training order.
3. **EXIF rotation.** Phone photos can carry an EXIF orientation tag, so the
   server applies `ImageOps.exif_transpose` before converting to RGB.

The app uploads the original file bytes. It never resizes, recompresses or
Base64-encodes them, so all preprocessing happens in one place (the server).

## Running the tests

```bash
# server (no GPU, no torch needed)
cd server
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q

# Android (needs the Android SDK)
cd android
./gradlew assembleDebug testDebugUnitTest
```

## Verification status

| what | status |
|---|---|
| Server `pytest` (44 tests, **mock** model) | passing on FastAPI 0.143 / Starlette 1.7 and on FastAPI 0.115 / Starlette 0.38 |
| Server under real uvicorn in a background thread + curl (**mock** model) | worked: upload → queued → running → completed, 512 px shrink applied, 401 without key |
| Android logic: ViewModels, error mapping, URL/QR parsing, DTO mapping, repository against MockWebServer (49 tests) | passing, compiled with Kotlin 2.2.21 and the app's Retrofit/OkHttp/serialization versions in a JVM-only harness with small `androidx.lifecycle` stand-ins |
| `./gradlew assembleDebug testDebugUnitTest` | **not run yet**: the environment it was written in could not reach Google's Maven repository or download the Android SDK. Compose UI code has not been compiled. |
| Real model through the API (Colab GPU) | **needs a live Colab session**: use S4 (parity + latency), S6 and the checklist below |
| The original notebooks | were **not available** while this was written. Nothing is retyped from them: the server reuses the notebook's own prompt objects, model and `side_check` at runtime, and S4 checks that its output matches the notebook's `explain()`. |

If Gradle cannot resolve a version from `gradle/libs.versions.toml` on your
machine, accept Android Studio's suggested update for that entry.

## Troubleshooting

| symptom | cause | fix |
|---|---|---|
| Chip says **Offline**; error "Cannot reach the server" | Colab disconnected, the runtime was stopped, or the **tunnel URL changed** (a new URL comes with every S5 run) | Re-run the model cells and S1–S5 in Colab, then **Scan QR** again in Settings |
| **401** / "API key was not accepted" | S5 was re-run, which made a new key, or the key was mistyped | Scan the new QR code |
| **429** / "queue full" | 5 jobs are already waiting on the single GPU | Wait a minute, then retry |
| "No answer after 5 minutes" (timeout) | GPU overloaded, very slow generation, or Colab stopped mid-job | Check the Colab tab is still running, then retry. S4/S6 show the real per-job time on your GPU. |
| "Model is still loading" (503) | the server is up but `status` is not `ready` | Wait for S3/S5 to finish |
| "The server no longer has this job" | jobs expire after 15 min, or the server restarted | Retry: a new job is created |
| GPU out of memory | another process (e.g. Gradio) is using the T4, or a very large image with `MAX_IMG_SIDE = None` | The server frees the cache and keeps running. Close other GPU users, keep `MAX_IMG_SIDE = 512`, retry. If it repeats, restart the runtime. |
| Colab disconnected | idle timeout or the ~12 h session limit | Reconnect, re-run all cells, scan the new QR code |
| "QR scanner unavailable" | Google Play services' scanner module not installed or no Play services | Paste the URL and key by hand |
| S4 prints `Same output … False` | the prompt the server builds differs from the notebook's | Compare `prompt_fn(PARITY_CAPTION)` with `inspect.getsource(explain)` and fix before serving |
| S2 fails with a pip conflict | a server package needs a newer version of something Colab already has | Nothing was changed. Read the conflict; usually pinning an older `fastapi` in S2 solves it. |

## Manual end-to-end checklist (Colab + phone)

Real end-to-end results can only come from a live Colab GPU. Tick each step:

1. [ ] Colab: S5 prints `public /health: {'status': 'ready', …}` and a QR code.
2. [ ] Phone: Settings → Scan QR → "Connected. Key accepted. Model ready."
3. [ ] Home chip shows **Ready** (green dot).
4. [ ] Pick a ROCOv2 test image, paste its English caption, tap **Analyze**.
   The Analyzing screen shows the queue status ("In queue: next" →
   "Generating…") and an elapsed timer. Note the time it takes.
5. [ ] The Result screen shows Bangla text in Noto Sans Bengali, the mode tag
   "With caption", the model time, a timestamp and the disclaimer. **Copy**
   works. Compare the text with Colab S4/S6 for the same image: it must be
   identical (greedy decoding).
6. [ ] Left/right warning: use `ROCOv2_2023_test_000018` with its caption. If
   the answer contradicts the caption's side, the **amber** banner "Left/right
   mismatch with caption — do not trust this answer" appears. Because greedy
   decoding is deterministic, the notebook's mismatch should reproduce with
   the same preprocessing. If the 512 px path gives a consistent answer
   instead, set `MAX_IMG_SIDE = None` in S3, re-run S3 and S5, re-scan, and
   try again. Write down which setting produced the warning.
7. [ ] Image only: remove the caption and analyze again. The tag says
   "Image only" and the banner is grey ("Left/right not checked").
8. [ ] Rotate the phone while it says "Generating…". Polling continues and no
   second job is created (the Colab queue length stays at 0–1).
9. [ ] Tap **Stop waiting** during a job. The app returns to Home and says
   the server may still finish.
10. [ ] In Colab, **Runtime → Disconnect and delete runtime**. Within about
    10 s the chip turns **Offline**. Analyze is disabled. A retry shows
    "Cannot reach the server…" with **Retry** and **Open Settings**.
11. [ ] Re-run all cells, scan the new QR code, and check the chip is Ready again.

## Known limitations

* **Notebook differences** (above): 512 px shrink on by default, image-first
  order kept from the inference notebook, EXIF rotation added.
* **Colab lifetime:** idle disconnects and a ~12 h cap. Every restart means a
  new tunnel URL and a new key (scan the QR code again).
* **Latency:** Unsloth reports that float16 does not work for qwen3_5 and
  falls back to float32, so a T4 is slow. No latency figure is claimed here.
  Measure it with S4/S6 (the app also shows the server's model time per result).
* **Small training set:** 231 verified rows, about 208 train / 23 validation.
  (The "28 samples" text in the inference notebook is stale.)
* **Captions:** the model was trained with captions, so image-only mode is weaker.
* **Not a chat / VQA model:** it does not answer questions. The caption box
  takes the image's English caption only.
* **Left/right check** is a simple word match (বাম/বাঁ vs ডান against
  "left"/"right" in the caption).
* **No ROCOv2 Image-ID mode** in the app (see future work).
* **Not a diagnostic tool**, with no clinical validation.

## Future work

* "Fetch by ROCOv2 Image ID" mode from the notebook. It needs the
  `eltorio/ROCOv2-radiology` dataset in the runtime (tens of GB), so it was
  left out.
* A persistent GPU host instead of Colab, so the URL stays fixed.
* Evaluate image-first vs text-first order and 512 px vs full resolution on
  the validation split.

## Privacy

Images go to a temporary Colab server through a public tunnel. The server
keeps them in memory only, never writes them to disk, deletes them right
after inference, and does not log images, captions or outputs. Jobs expire
after 15 minutes. **Do not use real patient images.**

## Credits and licences

* Base model: `unsloth/Qwen3.5-4B`. Dataset: ROCOv2 (`eltorio/ROCOv2-radiology`).
* Noto Sans Bengali © The Noto Project Authors, SIL Open Font License 1.1
  (`android/app/src/main/assets/licenses/NotoSansBengali-OFL.txt`).
