"""HTTP contract tests. All use the MOCK explain_fn from conftest.py."""

import io
import time

from PIL import Image
from starlette.formparsers import MultiPartParser

from app import DISCLAIMER_BN, MODEL_NAME, ServerConfig
from conftest import AUTH, OOMError, image_bytes, submit


# ---- health -------------------------------------------------------------

def test_health_needs_no_key_and_reports_ready(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ready", "model": MODEL_NAME, "queue_length": 0, "busy": False, "version": "1.0.0"}


def test_health_reports_loading_without_model(make_client):
    c = make_client(explain_fn=None)
    assert c.get("/health").json()["status"] == "loading"


def test_health_counts_queue(client):
    submit(client)
    submit(client)
    assert client.get("/health").json()["queue_length"] == 2


# ---- auth ---------------------------------------------------------------

def test_missing_key_is_401(client):
    r = submit(client, headers={})
    assert r.status_code == 401
    assert r.json() == {"error": {"code": "UNAUTHORIZED", "message": "Missing or wrong API key."}}


def test_wrong_key_is_401(client):
    assert submit(client, headers={"X-API-Key": "nope"}).status_code == 401
    assert client.get("/api/v1/jobs/abc", headers={"X-API-Key": "nope"}).status_code == 401


def test_unknown_job_is_404_with_key(client):
    r = client.get("/api/v1/jobs/00000000-0000-0000-0000-000000000000", headers=AUTH)
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"


def test_docs_are_disabled(client):
    assert client.get("/docs", headers=AUTH).status_code == 404
    assert client.get("/openapi.json", headers=AUTH).status_code == 404


# ---- validation -----------------------------------------------------------

def test_accepts_jpeg_png_webp(client):
    for fmt in ("JPEG", "PNG", "WEBP"):
        r = submit(client, data=image_bytes(fmt))
        assert r.status_code == 202, (fmt, r.text)


def test_rejects_non_image_even_with_jpg_name(client):
    r = submit(client, data=b"this is not an image" * 10, filename="photo.jpg")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_IMAGE"


def test_rejects_gif(client):
    r = submit(client, data=image_bytes("GIF"), filename="a.gif", mime="image/gif")
    assert r.json()["error"]["code"] == "INVALID_IMAGE"


def test_rejects_truncated_jpeg(client):
    data = image_bytes(size=(400, 400), color=(10, 200, 30))
    r = submit(client, data=data[: len(data) // 2])
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_IMAGE"


def test_rejects_missing_image(client):
    r = client.post("/api/v1/jobs", data={"caption": "x"}, headers=AUTH)
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_IMAGE"


def test_rejects_empty_file(client):
    r = submit(client, data=b"")
    assert r.json()["error"]["code"] == "INVALID_IMAGE"


def test_rejects_image_over_limit(make_client):
    c = make_client(config=ServerConfig(max_upload_bytes=2000))
    r = submit(c, data=image_bytes("PNG", size=(300, 300)) + b"\0" * 3000)
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "TOO_LARGE"


def test_rejects_oversized_body_by_content_length(client):
    big = b"\0" * (11 * 1024 * 1024)
    r = submit(client, data=big)
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "TOO_LARGE"


def test_rejects_oversized_chunked_body(make_client):
    c = make_client(config=ServerConfig(max_upload_bytes=50_000))
    boundary = "XyZ"
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.jpg\"\r\n"
            "Content-Type: image/jpeg\r\n\r\n").encode()

    def body():  # a generator makes httpx send Transfer-Encoding: chunked
        yield head
        for _ in range(20):
            yield b"\0" * 10_000
        yield f"\r\n--{boundary}--\r\n".encode()

    r = c.post("/api/v1/jobs", content=body(),
               headers={**AUTH, "Content-Type": f"multipart/form-data; boundary={boundary}"})
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "TOO_LARGE"


def test_rejects_long_caption(client):
    r = submit(client, caption="a" * 1001)
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "TOO_LARGE"
    assert submit(client, caption="a" * 1000).status_code == 202


def test_uploads_stay_in_memory(client):
    # Starlette spools uploads to a temp file above spool_max_size; we raise it
    # above the 10 MB limit so an accepted upload is never written to disk.
    assert MultiPartParser.spool_max_size > 10 * 1024 * 1024


def test_model_not_ready_is_503(make_client):
    c = make_client(explain_fn=None)
    r = submit(c)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "MODEL_NOT_READY"


# ---- queue ----------------------------------------------------------------

def test_queue_full_is_429(client):
    for i in range(5):
        r = submit(client)
        assert r.status_code == 202
        assert r.json()["position"] == i + 1
    r = submit(client)
    assert r.status_code == 429
    assert r.json()["error"]["code"] == "QUEUE_FULL"
    client.queue.run_next()  # one job leaves the waiting line
    assert submit(client).status_code == 202


def test_position_counts_running_job(client, explain):
    first = submit(client).json()["job_id"]
    second = submit(client).json()["job_id"]
    seen = {}

    def peek(image, caption):
        seen["first"] = client.get(f"/api/v1/jobs/{first}", headers=AUTH).json()
        seen["second"] = client.get(f"/api/v1/jobs/{second}", headers=AUTH).json()
        seen["health"] = client.get("/health").json()
        return "ঠিক আছে"

    client.fastapi_app.state.set_model(peek)
    client.queue.run_next()
    assert seen["first"]["status"] == "running" and seen["first"]["position"] == 0
    assert seen["second"]["status"] == "queued" and seen["second"]["position"] == 2
    assert seen["health"]["busy"] is True and seen["health"]["queue_length"] == 1


# ---- job lifecycle ------------------------------------------------------------

def test_lifecycle_with_caption(client, explain):
    r = submit(client, caption="  Chest X-ray showing left pneumothorax  ")
    assert r.status_code == 202
    body = r.json()
    assert body["status"] == "queued" and body["position"] == 1
    job_id = body["job_id"]

    snap = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
    assert snap == {"job_id": job_id, "status": "queued", "position": 1, "result": None, "error": None}

    assert client.queue.run_next() is True
    snap = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
    assert snap["status"] == "completed"
    assert snap["position"] == 0
    assert snap["error"] is None
    res = snap["result"]
    assert res["bangla_text"] == explain.text
    assert res["mode"] == "caption"
    assert res["caption_used"] == "Chest X-ray showing left pneumothorax"
    assert res["side_check"] == {"level": "ok", "message": "Side check passed."}
    assert res["disclaimer_bn"] == DISCLAIMER_BN
    assert isinstance(res["elapsed_ms"], int) and res["elapsed_ms"] >= 0
    assert explain.calls[0]["caption"] == "Chest X-ray showing left pneumothorax"


def test_image_only_mode_is_not_checked(client):
    job_id = submit(client).json()["job_id"]
    client.queue.run_next()
    res = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()["result"]
    assert res["mode"] == "image_only"
    assert res["caption_used"] == ""
    assert res["side_check"]["level"] == "not_checked"


def test_side_check_warning_reaches_client(client, explain):
    explain.text = "এটি বুকের ডান পাশের সমস্যা।"
    job_id = submit(client, caption="left pneumothorax").json()["job_id"]
    client.queue.run_next()
    res = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()["result"]
    assert res["side_check"]["level"] == "warning"
    assert res["side_check"]["message"].startswith("WARNING")


def test_side_check_crash_is_not_checked(make_client):
    def broken(caption, bangla):
        raise KeyError("boom")

    c = make_client(side_check_fn=broken)
    job_id = submit(c, caption="x").json()["job_id"]
    c.queue.run_next()
    res = c.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
    assert res["status"] == "completed"
    assert res["result"]["side_check"]["level"] == "not_checked"


def test_output_is_only_trimmed(client, explain):
    explain.text = "\n  প্রথম বাক্য।  দ্বিতীয় বাক্য।\n\n"
    job_id = submit(client).json()["job_id"]
    client.queue.run_next()
    res = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()["result"]
    assert res["bangla_text"] == "প্রথম বাক্য।  দ্বিতীয় বাক্য।"


def test_empty_output_fails(client, explain):
    explain.text = "   \n "
    job_id = submit(client).json()["job_id"]
    client.queue.run_next()
    snap = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
    assert snap["status"] == "failed"
    assert snap["result"] is None
    assert snap["error"]["code"] == "EMPTY_OUTPUT"


def test_oom_fails_job_clears_cache_and_server_survives(client, explain, gpu):
    explain.error = OOMError("CUDA out of memory. Tried to allocate 2 GiB (secret detail)")
    bad = submit(client).json()["job_id"]
    client.queue.run_next()
    snap = client.get(f"/api/v1/jobs/{bad}", headers=AUTH).json()
    assert snap["status"] == "failed"
    assert snap["error"]["code"] == "INFERENCE_FAILED"
    assert "secret detail" not in snap["error"]["message"]
    assert gpu.empty_cache_calls == 1

    explain.error = None
    good = submit(client).json()["job_id"]
    client.queue.run_next()
    assert client.get(f"/api/v1/jobs/{good}", headers=AUTH).json()["status"] == "completed"
    assert client.get("/health").json()["status"] == "ready"


def test_generic_failure_hides_details(client, explain):
    explain.error = ValueError("Traceback ... /content/drive/secret")
    job_id = submit(client).json()["job_id"]
    client.queue.run_next()
    snap = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()
    assert snap["error"]["code"] == "INFERENCE_FAILED"
    assert "secret" not in snap["error"]["message"] and "Traceback" not in snap["error"]["message"]


def test_image_dropped_after_inference(client):
    job_id = submit(client).json()["job_id"]
    job = client.queue.get(job_id)
    assert job.image_bytes is not None
    client.queue.run_next()
    assert job.image_bytes is None


def test_jobs_expire_after_15_minutes(client, clock):
    job_id = submit(client).json()["job_id"]
    client.queue.run_next()
    clock.now += 14 * 60
    assert client.get(f"/api/v1/jobs/{job_id}", headers=AUTH).status_code == 200
    clock.now += 2 * 60
    r = client.get(f"/api/v1/jobs/{job_id}", headers=AUTH)
    assert r.status_code == 404 and r.json()["error"]["code"] == "NOT_FOUND"


def test_real_worker_thread_processes_jobs(make_client):
    c = make_client(start_worker=True)
    with c:  # runs the lifespan, which starts the worker thread
        job_id = submit(c, caption="CT of the abdomen").json()["job_id"]
        deadline = time.time() + 5
        status = None
        while time.time() < deadline:
            status = c.get(f"/api/v1/jobs/{job_id}", headers=AUTH).json()["status"]
            if status == "completed":
                break
            time.sleep(0.02)
        assert status == "completed"


# ---- preprocessing ---------------------------------------------------------------

def test_image_shrunk_to_512_like_training(client, explain):
    submit(client, data=image_bytes("PNG", size=(2000, 1000)))
    client.queue.run_next()
    assert explain.calls[0]["size"] == (512, 256)
    assert explain.calls[0]["mode"] == "RGB"


def test_shrink_can_be_disabled(make_client, explain):
    c = make_client(config=ServerConfig(max_img_side=None))
    submit(c, data=image_bytes("PNG", size=(2000, 1000)))
    c.queue.run_next()
    assert explain.calls[0]["size"] == (2000, 1000)


def test_small_images_are_not_enlarged(client, explain):
    submit(client, data=image_bytes(size=(300, 200)))
    client.queue.run_next()
    assert explain.calls[0]["size"] == (300, 200)


def test_exif_rotation_applied(client, explain):
    exif = Image.Exif()
    exif[0x0112] = 6  # orientation: rotate 90 degrees clockwise
    submit(client, data=image_bytes(size=(400, 100), exif=exif))
    client.queue.run_next()
    assert explain.calls[0]["size"] == (100, 400)


def test_rgba_png_converted_to_rgb(client, explain):
    buf = io.BytesIO()
    Image.new("RGBA", (40, 40), (0, 0, 0, 0)).save(buf, format="PNG")
    submit(client, data=buf.getvalue())
    client.queue.run_next()
    assert explain.calls[0]["mode"] == "RGB"


# ---- privacy -----------------------------------------------------------------------

def test_no_caption_or_output_in_logs(client, explain, caplog):
    caplog.set_level("DEBUG")
    explain.error = RuntimeError("PRIVATE-OUTPUT")
    submit(client, caption="PRIVATE-CAPTION")
    client.queue.run_next()
    explain.error = None
    explain.text = "PRIVATE-BANGLA"
    submit(client, caption="PRIVATE-CAPTION")
    client.queue.run_next()
    assert "PRIVATE" not in caplog.text
