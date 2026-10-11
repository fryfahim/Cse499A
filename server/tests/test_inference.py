"""Tests for inference.py using a MOCK tokenizer and model (no GPU, no torch)."""

import pytest

from inference import IMAGE_FIRST, TEXT_FIRST, build_messages, default_prompt_fn, fill_caption, make_explain_fn


def test_fill_caption_placeholder_styles():
    assert fill_caption("ক্যাপশন: {caption} শেষ", "left lung") == "ক্যাপশন: left lung শেষ"
    assert fill_caption("ক্যাপশন: {} শেষ", "x") == "ক্যাপশন: x শেষ"
    # other braces in the prompt are left alone (no str.format)
    assert fill_caption("{caption} {json}", "a") == "a {json}"
    with pytest.raises(ValueError):
        fill_caption("no placeholder", "x")


def test_default_prompt_fn_picks_mode():
    fn = default_prompt_fn("WITH {caption}", "IMAGE ONLY")
    assert fn("cap") == "WITH cap"
    assert fn("") == "IMAGE ONLY"


def test_build_messages_order():
    assert build_messages("p")[0]["content"] == [{"type": "image"}, {"type": "text", "text": "p"}]
    assert build_messages("p", TEXT_FIRST)[0]["content"] == [{"type": "text", "text": "p"}, {"type": "image"}]
    with pytest.raises(ValueError):
        build_messages("p", "sideways")


class FakeShape:
    def __init__(self, n):
        self.shape = (1, n)


class FakeBatch(dict):
    def to(self, device):
        self["device"] = device
        return self


class FakeTokenizer:
    def __init__(self):
        self.calls = {}

    def apply_chat_template(self, messages, **kwargs):
        self.calls["template"] = (messages, kwargs)
        return "<prompt>"

    def __call__(self, image, text, **kwargs):
        self.calls["call"] = (image, text, kwargs)
        return FakeBatch(input_ids=FakeShape(3))

    def decode(self, tokens, **kwargs):
        self.calls["decode"] = (list(tokens), kwargs)
        return "  নতুন টোকেন  \n"


class FakeModel:
    def generate(self, **kwargs):
        self.kwargs = kwargs
        return [[101, 102, 103, 7, 8, 9]]  # 3 prompt tokens + 3 new tokens


def test_explain_fn_mirrors_notebook_settings():
    tok, model = FakeTokenizer(), FakeModel()
    fn = make_explain_fn(model, tok, default_prompt_fn("WITH {caption}", "ONLY"))
    out = fn("IMG", "left lung")
    assert out == "নতুন টোকেন"
    messages, kwargs = tok.calls["template"]
    assert kwargs == {"add_generation_prompt": True, "enable_thinking": False}
    assert messages[0]["content"][0] == {"type": "image"}
    assert messages[0]["content"][1]["text"] == "WITH left lung"
    image, text, kw = tok.calls["call"]
    assert (image, text) == ("IMG", "<prompt>")
    assert kw == {"add_special_tokens": False, "return_tensors": "pt"}
    gen = {k: v for k, v in model.kwargs.items() if k not in ("input_ids", "device")}
    assert gen == {"max_new_tokens": 512, "use_cache": True, "do_sample": False, "repetition_penalty": 1.05}
    tokens, dkw = tok.calls["decode"]
    assert tokens == [7, 8, 9]  # only the new tokens are decoded
    assert dkw == {"skip_special_tokens": True}


def test_explain_fn_image_only_and_text_first():
    tok = FakeTokenizer()
    fn = make_explain_fn(FakeModel(), tok, default_prompt_fn("WITH {caption}", "ONLY"), content_order=TEXT_FIRST)
    fn("IMG", "")
    content = tok.calls["template"][0][0]["content"]
    assert content == [{"type": "text", "text": "ONLY"}, {"type": "image"}]


def test_bad_order_rejected_at_startup():
    with pytest.raises(ValueError):
        make_explain_fn(FakeModel(), FakeTokenizer(), lambda c: "", content_order="x")
    assert IMAGE_FIRST == "image_first"
