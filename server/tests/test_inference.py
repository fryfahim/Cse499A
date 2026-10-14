"""Tests for inference.py using a MOCK tokenizer and model (no GPU, no torch)."""

import pytest

from inference import IMAGE_FIRST, TEXT_FIRST, build_messages, make_explain_fn
from notebook_logic import PROMPT_IMAGE_ONLY, PROMPT_WITH_CAPTION


def test_build_messages_order():
    assert build_messages("p")[0]["content"] == [{"type": "image"}, {"type": "text", "text": "p"}]
    assert build_messages("p", TEXT_FIRST)[0]["content"] == [{"type": "text", "text": "p"}, {"type": "image"}]
    with pytest.raises(ValueError):
        build_messages("p", "sideways")


class FakeTensor2D:
    """Stands in for the (1, n) output tensor; supports out[:, k:]."""

    def __init__(self, ids):
        self.ids = ids
        self.shape = (1, len(ids))

    def __getitem__(self, key):
        rows, cols = key
        assert rows == slice(None)
        return FakeTensor2D(self.ids[cols])


class FakeBatch(dict):
    def to(self, device):
        self["device"] = device
        return self


class FakeImage:
    def __init__(self):
        self.converted_to = None

    def convert(self, mode):
        self.converted_to = mode
        return f"IMG-{mode}"


class FakeTokenizer:
    def __init__(self):
        self.calls = {}

    def apply_chat_template(self, messages, **kwargs):
        self.calls["template"] = (messages, kwargs)
        return "<prompt>"

    def __call__(self, image, text, **kwargs):
        self.calls["call"] = (image, text, kwargs)
        return FakeBatch(input_ids=FakeTensor2D([101, 102, 103]))

    def batch_decode(self, tokens, **kwargs):
        self.calls["decode"] = (tokens.ids, kwargs)
        return ["  নতুন টোকেন  \n"]


class FakeModel:
    def generate(self, **kwargs):
        self.kwargs = kwargs
        return FakeTensor2D([101, 102, 103, 7, 8, 9])  # 3 prompt tokens + 3 new tokens


def test_explain_fn_mirrors_notebook_explain():
    tok, model, img = FakeTokenizer(), FakeModel(), FakeImage()
    out = make_explain_fn(model, tok)(img, "  left lung  ")
    assert out == "নতুন টোকেন"

    messages, kwargs = tok.calls["template"]
    assert kwargs == {"add_generation_prompt": True, "enable_thinking": False}
    content = messages[0]["content"]
    assert content[0] == {"type": "image"}
    # the notebook's make_prompt strips the caption and uses str.format
    assert content[1]["text"] == PROMPT_WITH_CAPTION.format(caption="left lung")

    image, text, kw = tok.calls["call"]
    assert img.converted_to == "RGB" and image == "IMG-RGB" and text == "<prompt>"
    assert kw == {"add_special_tokens": False, "return_tensors": "pt"}

    gen = {k: v for k, v in model.kwargs.items() if k not in ("input_ids", "device")}
    assert gen == {"max_new_tokens": 512, "use_cache": True, "do_sample": False, "repetition_penalty": 1.05}
    assert model.kwargs["device"] == "cuda"

    tokens, dkw = tok.calls["decode"]
    assert tokens == [7, 8, 9]  # only the new tokens are decoded
    assert dkw == {"skip_special_tokens": True}


def test_image_only_prompt_and_text_first_flag():
    tok = FakeTokenizer()
    make_explain_fn(FakeModel(), tok, content_order=TEXT_FIRST)(FakeImage(), "   ")
    content = tok.calls["template"][0][0]["content"]
    assert content == [{"type": "text", "text": PROMPT_IMAGE_ONLY}, {"type": "image"}]


def test_bad_order_rejected_at_startup():
    with pytest.raises(ValueError):
        make_explain_fn(FakeModel(), FakeTokenizer(), content_order="x")
    assert IMAGE_FIRST == "image_first"
