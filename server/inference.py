"""Model call used by the server in Colab.

``make_explain_fn(model, tokenizer)`` returns ``explain_fn(image, caption) -> str``,
which does exactly what ``explain()`` in the inference notebook does:

* prompt from ``make_prompt(caption)`` (``notebook_logic.py``, copied verbatim);
* user turn ``[{"type": "image"}, {"type": "text", ...}]`` (image first);
* ``apply_chat_template(..., add_generation_prompt=True, enable_thinking=False)``;
* ``tokenizer(image.convert("RGB"), input_text, add_special_tokens=False, return_tensors="pt")``;
* ``generate(max_new_tokens=512, use_cache=True, do_sample=False, repetition_penalty=1.05)``;
* ``batch_decode(out[:, prompt_len:], skip_special_tokens=True)[0].strip()``.

The only addition is the ``content_order`` flag. ``TEXT_FIRST`` switches to the
order used in the training conversations. The default ``IMAGE_FIRST`` is the
order of the inference notebook, and of the fine-tune notebook's own
held-out evaluation.

Image preprocessing (EXIF rotation, RGB, 512 px shrink) happens in ``app.py``
before this function is called. Nothing here imports torch, so it can be
unit-tested with fakes.
"""

from __future__ import annotations

from typing import Any, Callable

from notebook_logic import make_prompt

IMAGE_FIRST = "image_first"  # inference notebook (and fine-tune eval) order: [image, text]
TEXT_FIRST = "text_first"  # training conversations: [text, image]


def build_messages(prompt_text: str, content_order: str = IMAGE_FIRST) -> list[dict]:
    image_part = {"type": "image"}
    text_part = {"type": "text", "text": prompt_text}
    if content_order == IMAGE_FIRST:
        content = [image_part, text_part]
    elif content_order == TEXT_FIRST:
        content = [text_part, image_part]
    else:
        raise ValueError(f"content_order must be {IMAGE_FIRST!r} or {TEXT_FIRST!r}")
    return [{"role": "user", "content": content}]


def make_explain_fn(
    model: Any,
    tokenizer: Any,
    *,
    prompt_fn: Callable[[str], str] = make_prompt,
    content_order: str = IMAGE_FIRST,
    max_new_tokens: int = 512,
    repetition_penalty: float = 1.05,
    device: str = "cuda",
) -> Callable[[Any, str], str]:
    build_messages("", content_order)  # validate the flag at startup

    def explain_fn(image: Any, caption: str = "") -> str:
        messages = build_messages(prompt_fn(caption), content_order)
        input_text = tokenizer.apply_chat_template(messages, add_generation_prompt=True, enable_thinking=False)
        inputs = tokenizer(
            image.convert("RGB"),
            input_text,
            add_special_tokens=False,
            return_tensors="pt",
        ).to(device)
        out = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            use_cache=True,
            do_sample=False,  # greedy: same answer every time
            repetition_penalty=repetition_penalty,
        )
        new_tokens = out[:, inputs["input_ids"].shape[1]:]
        return tokenizer.batch_decode(new_tokens, skip_special_tokens=True)[0].strip()

    return explain_fn
