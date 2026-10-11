# ROCOv2 Bangla API (v1.0.0)

The FastAPI server in `server/` runs inside the Colab runtime that has the
model loaded. A cloudflared quick tunnel (or ngrok) makes it reachable over
HTTPS. The Android app is its only intended client.

* Every response body is JSON.
* Every endpoint except `GET /health` requires the header `X-API-Key: <key>`.
  The key is random, generated each time the server cell runs, and printed
  in Colab next to the URL and QR code.
* Error bodies never contain stack traces, file paths, captions or model output.
* Jobs are **asynchronous**. Cloudflare cuts proxied requests at about 100 s,
  and float32 inference on a T4 can get close to that, so the client creates
  a job and then polls it.

## `GET /health`

No key needed.

```json
{
  "status": "ready",
  "model": "Qwen3.5-4B + LoRA qwen_lora_bangla_radiology",
  "queue_length": 0,
  "busy": false,
  "version": "1.0.0"
}
```

| field | meaning |
|---|---|
| `status` | `ready` (model bound, accepting jobs), `loading` (server up, model not bound yet), `error` |
| `queue_length` | jobs waiting, **not** counting the one running on the GPU |
| `busy` | `true` while a job is running on the GPU |

## `POST /api/v1/jobs`

`multipart/form-data`:

| part | required | rules |
|---|---|---|
| `image` | yes | JPEG, PNG or WebP, max 10 MB. Checked by opening it with Pillow (and fully decoding it), not by file name or MIME type. |
| `caption` | no | The image's English caption, max 1000 characters after trimming. Empty or missing → image-only mode. |

Response **202**:

```json
{ "job_id": "8a4c…", "status": "queued", "position": 1 }
```

The server keeps the image bytes in memory only (uploads are never spooled to
disk) and drops them as soon as inference finishes.

Before inference the server applies `ImageOps.exif_transpose`, converts to
RGB, then `img.thumbnail((512, 512))` as training did. The last step is
controlled by `ServerConfig.max_img_side` (`MAX_IMG_SIDE` in the Colab cell);
`None` disables it.

## `GET /api/v1/jobs/{job_id}`

```json
{
  "job_id": "8a4c…",
  "status": "completed",
  "position": 0,
  "result": {
    "bangla_text": "<exact model output, whitespace-trimmed>",
    "mode": "caption",
    "caption_used": "Chest X-ray showing left pneumothorax",
    "side_check": { "level": "ok", "message": "<side_check() text>" },
    "disclaimer_bn": "এটি শুধু ছবি বোঝার জন্য সাহায্য। এটি ডাক্তারের পরামর্শ নয়। অনুগ্রহ করে ডাক্তার দেখান।",
    "elapsed_ms": 0
  },
  "error": null
}
```

| field | meaning |
|---|---|
| `status` | `queued` → `running` → `completed` or `failed` |
| `position` | while `queued`: 1-based place in line, counting a job running on the GPU as ahead (1 = starts next, so jobs ahead = `position − 1`). `0` once running or finished. |
| `result` | only when `completed`, otherwise `null` |
| `result.mode` | `caption` or `image_only` |
| `result.side_check.level` | mapped from the notebook's `side_check(caption, bangla)` string: starts with `WARNING` → `warning`; starts with `No caption` → `not_checked`; anything else → `ok`. If `side_check` itself raises, `not_checked`. `message` is the original string. |
| `result.elapsed_ms` | model time on the GPU for this job (preprocessing + generation), measured on the server |
| `error` | only when `failed`: `{ "code": "...", "message": "..." }` |

Jobs (and their results) expire **15 minutes** after creation; after that the
job id answers 404 `NOT_FOUND`. A server restart also forgets every job.

## Errors

Errors on any endpoint use this body:

```json
{ "error": { "code": "QUEUE_FULL", "message": "The server queue is full. Try again in a minute." } }
```

| code | HTTP | when |
|---|---|---|
| `UNAUTHORIZED` | 401 | missing or wrong `X-API-Key` |
| `INVALID_IMAGE` | 400 | no `image` part, empty file, not a real JPEG/PNG/WebP, truncated or corrupt |
| `TOO_LARGE` | 413 | image over 10 MB (checked from `Content-Length`, while streaming, and after reading), or caption over 1000 characters |
| `MODEL_NOT_READY` | 503 | `status` is not `ready` |
| `QUEUE_FULL` | 429 | 5 jobs are already waiting |
| `NOT_FOUND` | 404 | unknown or expired job id (also unknown paths) |
| `EMPTY_OUTPUT` | job `failed` | the model returned only whitespace. The server never invents text. |
| `INFERENCE_FAILED` | job `failed` (500 for an unexpected server error) | the model raised, including CUDA out-of-memory. On OOM the server calls `torch.cuda.empty_cache()` and keeps serving. |

## Checking the key

There is no separate key endpoint. The app's **Test connection** sends
`GET /api/v1/jobs/00000000-0000-0000-0000-000000000000` with the key: `404
NOT_FOUND` means the key was accepted, `401 UNAUTHORIZED` means it was not.

## Limits and behaviour

* One GPU worker, FIFO queue capped at 5 waiting jobs.
* The client polls every 2 s and gives up after 5 minutes.
* `/docs`, `/redoc` and `/openapi.json` are disabled.
* The server does not log images, captions or outputs (uvicorn access logs are off).

## Example (curl)

```bash
URL=https://<random>.trycloudflare.com
KEY=<key printed in Colab>

curl -s $URL/health
curl -s -H "X-API-Key: $KEY" -F image=@ROCOv2_2023_test_000018.jpg \
     -F "caption=<English caption>" $URL/api/v1/jobs
curl -s -H "X-API-Key: $KEY" $URL/api/v1/jobs/<job_id>
```
