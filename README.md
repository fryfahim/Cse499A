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
Android app ──HTTPS──> ngrok tunnel (your static *.ngrok-free.app domain) ──> FastAPI (background thread in the Colab runtime)
                                                     └─> one GPU worker ──> the notebook's model ──> result
```

---

## How to run it

You need: the Google account whose Drive holds `qwen_lora_bangla_radiology`
(saved by the fine-tune notebook), a free **ngrok** account, a computer with
Android Studio, and an Android phone (Android 8.0 or newer) with Google Play
services.

### Step 0: Set up ngrok (once, 5 min)

1. Sign up free at **ngrok.com** and open the dashboard.
2. *Getting Started › **Your Authtoken*** → copy the token.
3. **Domains** (under *Universal Gateway* in newer dashboards) →
   claim your **free static domain**, e.g. `something-random.ngrok-free.app`.
   With it the server URL is the same every time, so the app keeps working
   after a Colab restart. This step is optional but strongly recommended.
4. Make an API key for the app: any random text of 16+ characters, for example
   from a password generator.
5. In Colab, open the notebook from Step 1 → click the **key icon (Secrets)**
   in the left sidebar → add these secrets and switch **Notebook access** on
   for each:

   | name | value | required? |
   |---|---|---|
   | `NGROK_AUTHTOKEN` | the token from 2 | yes |
   | `NGROK_DOMAIN` | the domain from 3, e.g. `something-random.ngrok-free.app` | recommended |
   | `APP_API_KEY` | the text from 4 | recommended (otherwise a new random key on every start) |

   Colab Secrets belong to your Google account and are not saved in the
   notebook file or in this repository. Never paste the token into a code cell.

### Step 1: Start the server in Colab (about 10–15 min the first time)

1. Download **`colab/ROCOv2_Bangla_API_server.ipynb`** from this repository
   and open it in Google Colab (*File › Upload notebook*).
2. *Runtime › Change runtime type › **T4 GPU*** › Save.
3. Run the cells **from top to bottom**:

   | section | what it does | required? |
   |---|---|---|
   | 1. Install | the same install as your notebooks (torch 2.8.0, transformers 5.2.0, trl 0.22.2, unsloth, causal_conv1d 1.6.0). Silent for a few minutes. | yes |
   | 2. Settings | `ADAPTER_PATH = /content/drive/MyDrive/qwen_lora_bangla_radiology` | yes |
   | 3. Drive + model | mount Drive (allow access), load the adapter | yes |
   | 4. Prompt + `explain()` + `side_check()` | unchanged from your inference notebook | yes |
   | 5. Server packages | installs fastapi/uvicorn/qrcode/ngrok **without changing torch/transformers** (it checks), downloads the server code | yes |
   | 6. Connect the model | checks the server's copy of the prompts/`side_check` is identical to section 4, wraps the loaded model | yes |
   | 7. Demo images | saves 5 ROCOv2 test images + captions to Drive folder `rocov2_demo_images` | optional |
   | 8. Parity + latency | proves the server gives the same text as `explain()`, and measures real seconds per image on your T4 | optional, do it once |
   | 9. Start the API | starts the server and the **ngrok** tunnel; prints the **Server URL** (`https://<your domain>`), **API key** and a **QR code** | yes |
   | 10. Self-test | sends one real job through the public URL | optional |

4. **Leave the Colab tab open** while you use the app.

### Step 2: Build and install the app (once)

1. Install **Android Studio** (stable version) from developer.android.com.
2. Get the code: *File › New › Project from Version Control* →
   `https://github.com/fryfahim/Cse499A.git`, branch
   `claude/rocov2-bangla-android-app-1mhf6u` (or `main` once merged), or
   download the repository ZIP.
3. *File › Open* → choose the **`android/`** folder (not the repo root).
   Wait for "Gradle sync" to finish; Studio downloads the Android SDK and
   libraries automatically. If it offers to update a library or the Android
   Gradle plugin version, accept.
4. On the phone: *Settings › About phone* → tap **Build number** 7 times →
   *Developer options* → turn on **USB debugging**. Connect the phone by USB
   and allow the computer.
5. In Android Studio pick your phone in the device list and press **Run ▶**.
   The app **ROCOv2 Bangla** opens.

   *No cable?* *Build › Build App Bundle(s)/APK(s) › Build APK(s)*, then copy
   `android/app/build/outputs/apk/debug/app-debug.apk` to the phone and open
   it (allow "install unknown apps" when asked). From a terminal:
   `cd android && ./gradlew assembleDebug`.

   *No Android Studio at all?* GitHub Actions builds the app on every push
   (`.github/workflows/ci.yml`). Open the repository's **Actions** tab → the
   latest green **CI** run → *Artifacts* → download
   **rocov2-bangla-debug-apk**, unzip it, copy `app-debug.apk` to the phone
   and open it.

### Step 3: Connect the app to Colab

1. In the app tap the **gear** (or the grey "Not configured" chip) → **Scan QR**
   → point the camera at the QR code from section 9. (No camera permission is
   needed; Google's scanner does it.) Or type the URL and key by hand → **Save**.
2. The app runs **Test connection**: "Connected. Key accepted. Model ready."
3. Go back. The chip on the home screen shows **Ready** (green dot).

### Step 4: Analyze an image

1. **Pick image** → choose a radiology image (JPEG/PNG/WebP, ≤ 10 MB).
   Demo images from section 7: on the phone open the **Google Drive** app →
   `rocov2_demo_images` → ⋮ → **Download**, then pick them in the app.
2. Paste the image's **English caption** (from `captions.txt` in the same
   Drive folder). It is optional but improves accuracy. It is **not** a
   question box.
3. Tap **Analyze**. The app shows the real queue position, then
   "Generating…", with a timer. In float32 on a T4 this takes a while.
4. The result screen shows the Bangla explanation, the left/right check
   (green / **amber** / grey), the mode, the model time and the disclaimer.
   **Copy**, **Analyze again** or **New image**.

Try **`ROCOv2_2023_test_000384`** with its caption *"Focal right upper lobar
opacity on chest X-ray"*. In the fine-tune notebook's held-out results the
model answered বাম (left) for this right-sided finding, so the app should
show the **amber** "Left/right mismatch with caption — do not trust this
answer" banner.

### When Colab stops

Colab disconnects after idling and after ~12 h. The app then shows **Offline**.
Run the notebook again from the top.
* With `NGROK_DOMAIN` **and** `APP_API_KEY` set, the URL and key are the same
  as before: the chip turns **Ready** again by itself within ~10 s. Nothing to scan.
* Without them, the URL and/or key are new: **Settings › Scan QR** again.

---

## What the notebooks say, and how the server follows them

The server was checked against `ROCOv2_Bangla_Qwen35_4B_finetune_232.ipynb`,
`ROCOv2_Bangla_Qwen35_4B_inference_app.ipynb` and `verified_dataset_232.xlsx`:

* **Prompts, `make_prompt`, `DISCLAIMER_BN`, `side_check`** are copied
  byte-for-byte from the inference notebook into `server/notebook_logic.py`.
  Their SHA-256 is pinned in the tests, and notebook section 6 asserts they
  equal the notebook's own definitions. The fine-tune notebook uses the same
  two prompts.
* **`explain()`** is mirrored line for line in `server/inference.py`: image
  first, `enable_thinking=False`, greedy, 512 new tokens, repetition penalty
  1.05, `batch_decode` of the new tokens, `.strip()`. Notebook section 8 checks
  that the server code and `explain()` give identical text.
* **Data:** 231 rows (`ImageID`, `Bangla_meaning`, `Captions`), all matched in
  HF `eltorio/ROCOv2-radiology` split `test`. Seeded shuffle gave 208 train /
  23 held out. 3 epochs, eval loss 0.601 → 0.541 → 0.529.

### Differences between training and inference, handled openly

1. **Image size.** Training ran `convert("RGB")` then `thumbnail((512, 512))`.
   The inference notebook's upload cell sends the full-resolution image (it
   only shrinks the preview). The server shrinks to 512 px by default
   (`MAX_IMG_SIDE = 512`), because those are the only image sizes the adapter
   saw. It also means fewer image tokens, so inference is faster and less
   likely to run out of memory. `None` restores full resolution.
2. **Content order.** Training conversations are `[text, image]`. The
   inference notebook's `explain()` is `[image, text]`. The server keeps
   `[image, text]` (`CONTENT_ORDER = IMAGE_FIRST`) because it is the tested
   path. Note that the fine-tune notebook's own held-out evaluation also used
   `[image, text]` on 512 px images, which is exactly the server's default.
   `TEXT_FIRST` switches to the training order.
3. **EXIF rotation.** Phone photos can carry an EXIF orientation tag. The
   server applies `ImageOps.exif_transpose` before converting to RGB. The
   notebooks did not, which makes no difference for ROCOv2 images.

### Corrections to the original brief

* `ROCOv2_2023_test_000018` (caption "Large left-sided pneumothorax…") is a
  **training** image. In the inference notebook the model answered বাম (left)
  and the left/right check **passed**. It is not a mismatch example.
* The real mismatch in the saved notebooks is the held-out image
  **`ROCOv2_2023_test_000384`**: the caption says *right*, the model said
  *left*, and `side_check` returns `WARNING: caption says RIGHT but the Bangla
  says LEFT`. It is used in the checklist and in the tests.
* The inference notebook's "28 verified samples" text is stale (231 rows).

---

## Repository layout

```
colab/ROCOv2_Bangla_API_server.ipynb  ready-to-run Colab notebook (sections 1-4 of your inference notebook + the API)
colab/serve_cells.md             the API cells alone, to paste after section 4 of the inference notebook
android/                         Android Studio project (Kotlin, Jetpack Compose, Material 3)
  app/src/main/java/edu/cse499a/rocobangla/
    MainActivity.kt              single activity, edge-to-edge, theme + navigation
    RocoBanglaApp.kt             Application; owns the AppContainer
    core/AppConfig.kt            limits and timings (10 MB, 1000 chars, 2 s poll, 5 min timeout)
    core/AppError.kt             error types, next-step actions, server-code / HTTP / exception mapping
    core/ServerSettings.kt       URL + key, https-only URL check, QR payload parser
    data/Dtos.kt, Models.kt      API DTOs and app models (+ mapping)
    data/RocoApi.kt              Retrofit interface, OkHttp client, per-URL Retrofit factory
    data/NetworkAnalysisRepository.kt  API calls; every failure becomes an AppError
    data/AnalysisRepository.kt, SettingsRepository.kt, ImageInspector.kt   interfaces (faked in tests)
    data/DataStoreSettingsRepository.kt  URL + key in DataStore (excluded from backup)
    data/ContentUriImages.kt     ContentResolver metadata + streaming multipart upload (no Base64, no resize)
    data/AppContainer.kt         manual dependency injection
    ui/AppNavHost.kt, ui/ViewModelFactories.kt   navigation and ViewModel factories
    ui/analyze/                  state machine ViewModel + Home / Analyzing / Result screens
    ui/settings/                 server URL/key, Scan QR, Test connection, model info, privacy, limitations
    ui/components/Components.kt  cards, connection chip, disclaimer, left/right banner, error card
    ui/theme/                    colours, Noto Sans Bengali typography, shapes, spacing
  app/src/main/res/font/         Noto Sans Bengali Regular + SemiBold (SIL OFL 1.1)
  app/src/test/…                 unit tests (ViewModels, error mapping, parsing, repository via MockWebServer)
server/
  notebook_logic.py              prompts, make_prompt, DISCLAIMER_BN, side_check: verbatim from the inference notebook
  inference.py                   explain_fn: the notebook's explain(), plus the content-order flag
  app.py                         FastAPI app (explain_fn / side_check_fn injected; testable without a GPU)
  worker.py                      FIFO queue (cap 5), single GPU worker thread, in-memory jobs, 15 min expiry
  colab_runtime.py               uvicorn thread, ngrok tunnel (cloudflared fallback), Colab secrets, URL + key + QR, demo images
  requirements.txt, requirements-dev.txt
  tests/                         pytest (API tests use a clearly labelled MOCK model)
API.md                           the API contract
```

## Running the tests

```bash
cd server && python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt && pytest -q

cd android && ./gradlew assembleDebug testDebugUnitTest     # needs the Android SDK
```

GitHub Actions (`.github/workflows/ci.yml`) runs both on every push and pull
request, and keeps the debug APK as a downloadable artifact.

## Verification status

| what | status |
|---|---|
| Server `pytest`: 53 tests (**mock** model; real notebook `side_check` and prompts) | passing |
| Notebook `ROCOv2_Bangla_API_server.ipynb`: every code cell from section 4 on, executed locally with a **fake** model, fake Colab Secrets and a fake ngrok SDK pointed at localhost | ran end to end: identical-output check, 512 px shrink, amber warning, QR, real HTTP job |
| Android logic: 50 unit tests | passing in a JVM-only harness (Kotlin 2.2.21, the app's Retrofit/OkHttp/serialization versions, small `androidx.lifecycle` stand-ins) |
| `./gradlew assembleDebug testDebugUnitTest` | runs in GitHub Actions (job **Android build + unit tests**); see the latest CI run. The environments this was written in could not reach Google's Maven repository, so it has not been run locally. Compose UI code was reviewed by hand (APIs, icons, resource references) but CI is the first real compile. |
| Real model on a Colab GPU | **needs your live Colab session**: sections 8 and 10, then the checklist below |

## Troubleshooting

| symptom | cause | fix |
|---|---|---|
| Chip **Offline**, "Cannot reach the server" | Colab stopped (ngrok answers `ERR_NGROK_3200` "endpoint is offline"), or the URL changed (no `NGROK_DOMAIN`) | Re-run the notebook; scan again only if the URL/key changed |
| Section 9: "Add your ngrok authtoken…" | secret `NGROK_AUTHTOKEN` missing or Notebook access off | Step 0, item 5 |
| Section 9: ngrok error "authentication failed" / `ERR_NGROK_105` | wrong or revoked token | Copy the token again from the ngrok dashboard |
| Section 9: "domain is already in use" / `ERR_NGROK_334` | an earlier session still holds your static domain (e.g. a second Colab tab) | Run *Stop the server* or close the other runtime, wait ~10 s, run section 9 again |
| Section 9: domain not allowed / not found | `NGROK_DOMAIN` has a typo or is not claimed on this account | Copy it exactly from *Domains* (no `https://` needed) |
| Many errors after heavy use | ngrok free-plan monthly request/data limit reached (see your ngrok dashboard) | Wait for the reset, or use `TUNNEL = "cloudflared"` in section 6 |
| **401** / "API key was not accepted" | section 9 was re-run (new key) or a typo | Scan the new QR code |
| **429** / "queue full" | 5 jobs already waiting on the one GPU | Wait a minute, retry |
| "No answer after 5 minutes" | very slow generation, or Colab stopped mid-job | Check the Colab tab, retry. Section 8 shows real times on your GPU. |
| "Model is still loading" (503) | the server is up but the model is not connected yet | Finish sections 6 and 9 |
| "The server no longer has this job" | jobs expire after 15 min, or the server restarted | Retry (creates a new job) |
| GPU out of memory | another GPU user (e.g. Gradio) or `MAX_IMG_SIDE = None` | The server frees the cache and keeps running. Keep 512 and close other GPU users; restart the runtime if it repeats. |
| Section 5 pip error | a server package needs a newer version of something Colab has | Nothing was changed. Read the conflict and pin an older `fastapi` in `SERVER_PKGS`. |
| Section 6 assertion "… differs" | the notebook's prompts were edited | Use the same prompts as the trained model, or update `notebook_logic.py` and its test hashes on purpose |
| Section 8 `Same answer … False` | the server code no longer matches `explain()` | Do not serve; compare `inference.py` with section 4 |
| "QR scanner unavailable" | Play services' scanner module missing | Type the URL (`https://<your domain>`) and key by hand |
| Gradle sync fails on a version | a library version is not available on your machine | Accept Android Studio's suggested version in `gradle/libs.versions.toml` |

## Manual end-to-end checklist (Colab + phone)

1. [ ] Section 9 prints `public /health: {'status': 'ready', …}` and a QR code.
2. [ ] App: Settings → Scan QR → "Connected. Key accepted. Model ready." The home chip is **Ready**.
3. [ ] Pick `ROCOv2_2023_test_000216` with its caption → **Analyze**. The
   Analyzing screen shows "In queue: next" → "Generating…" and a timer.
   Write down the time.
4. [ ] The result shows Bangla text (Noto Sans Bengali), "With caption", the
   model time, a timestamp, the disclaimer and a **green** left/right banner.
   **Copy** works.
5. [ ] `ROCOv2_2023_test_000384` with *"Focal right upper lobar opacity on
   chest X-ray"* → **amber** banner "Left/right mismatch with caption — do not
   trust this answer". (Section 8 shows the same answer and `WARNING` in Colab.
   If the answer comes out consistent instead, write down the exact text.)
6. [ ] Remove the caption → Analyze again → tag "Image only", **grey** banner.
7. [ ] Rotate the phone during "Generating…": polling continues, and no second
   job is created.
8. [ ] **Stop waiting** during a job: back to Home with "the server may still finish".
9. [ ] Colab: *Runtime › Disconnect and delete runtime*. Within ~10 s the chip
   shows **Offline**, Analyze is disabled, and a retry explains what to do.
10. [ ] Re-run the notebook. With `NGROK_DOMAIN` + `APP_API_KEY` set, the chip turns
    **Ready** again **without scanning**; otherwise scan the new QR code.

## Known limitations

* The three training/inference differences above (512 px shrink on by default,
  image-first order, EXIF rotation added).
* **Colab lifetime:** idle disconnects and a ~12 h cap. With an ngrok static
  domain and a fixed `APP_API_KEY` the app reconnects by itself after a restart.
* **ngrok free plan:** monthly request/data limits and one static domain. The
  app checks `/health` every 10 s while open and polls every 2 s during a job.
* **Latency:** Unsloth logs "Using float16 precision for qwen3_5 won't work!
  Using float32." on the T4, so inference is slow. No figure is claimed here;
  section 8/10 and the app's "Model time" show the real value.
* **Small training set:** 208 training rows. The model mainly learned the
  style of the Bangla answers, not medical knowledge. Image-only mode is
  weaker (it was trained with captions).
* **Not a chat / VQA model:** the caption box takes the image's English caption.
* **Left/right check** is a simple word match and checks nothing else.
* **No ROCOv2 Image-ID mode** in the app (see future work).
* **Not a diagnostic tool.**

## Future work

* Image-ID mode in the app (the notebook's section 6). It needs the dataset
  in the runtime; `colab_runtime.fetch_rocov2_samples` already shows how to
  pull single IDs shard by shard.
* A persistent GPU host so the URL stays fixed.
* Compare image-first vs text-first and 512 px vs full resolution on the 23
  held-out rows (chrF + human review).

## Privacy

Images go to a temporary Colab server through ngrok's public tunnel. The server
keeps them in memory only, never writes them to disk, deletes them right
after inference, and does not log images, captions or outputs. Jobs expire
after 15 minutes. **Do not use real patient images.**

## Credits and licences

* Base model `unsloth/Qwen3.5-4B`; dataset ROCOv2 (`eltorio/ROCOv2-radiology`).
* Noto Sans Bengali © The Noto Project Authors, SIL OFL 1.1
  (`android/app/src/main/assets/licenses/NotoSansBengali-OFL.txt`).
