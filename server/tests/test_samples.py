"""fetch_rocov2_samples against small synthetic parquet shards (no network)."""

import io
import shutil

import pyarrow as pa
import pyarrow.parquet as pq
from PIL import Image

from colab_runtime import fetch_rocov2_samples


def _jpeg(color):
    buf = io.BytesIO()
    Image.new("RGB", (8, 8), color).save(buf, format="JPEG")
    return buf.getvalue()


def _shard(path, rows):
    table = pa.table({
        "image": [{"bytes": b, "path": None} for _, _, b in rows],
        "image_id": [i for i, _, _ in rows],
        "caption": [c for _, c, _ in rows],
        "cui": [["C0"] for _ in rows],
    })
    pq.write_table(table, path)


def test_fetches_ids_and_stops_early(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _shard(src / "test-0.parquet", [("A", "cap a", _jpeg("red")), ("B", "cap b", _jpeg("blue"))])
    _shard(src / "test-1.parquet", [("C", "cap c", _jpeg("green"))])
    _shard(src / "test-2.parquet", [("D", "cap d", _jpeg("white"))])
    names = {"data/test-00000-of-00003.parquet": "test-0", "data/test-00001-of-00003.parquet": "test-1",
             "data/test-00002-of-00003.parquet": "test-2", "data/train-00000-of-00001.parquet": "x", "README.md": "x"}
    downloaded = []

    def download(name):
        downloaded.append(name)
        copy = tmp_path / f"dl-{len(downloaded)}.parquet"
        shutil.copy(src / f"{names[name]}.parquet", copy)
        return str(copy)

    out = tmp_path / "out"
    found = fetch_rocov2_samples(["C", "A"], str(out), list_files=lambda: list(names), download=download)

    assert set(found) == {"A", "C"}
    assert found["C"][1] == "cap c"
    assert (out / "A.jpg").read_bytes() == _jpeg("red")  # original bytes, unchanged
    assert downloaded == ["data/test-00000-of-00003.parquet", "data/test-00001-of-00003.parquet"]  # stopped early
    assert not list(tmp_path.glob("dl-*.parquet"))  # shards deleted after scanning
    assert (out / "captions.txt").read_text(encoding="utf-8") == "C\ncap c\n\nA\ncap a\n\n"
