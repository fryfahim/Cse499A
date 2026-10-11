"""Model call used by the server in Colab.

``make_explain_fn`` wraps the already-loaded notebook ``model`` and
``tokenizer`` (the Unsloth processor) into ``explain_fn(image, caption) -> str``.
It mirrors the inference notebook's ``explain()``:

* prompt: the notebook's own ``PROMPT_WITH_CAPTION`` (caption inserted) or
  ``PROMPT_IMAGE_ONLY``. The strings are passed in from the notebook runtime,
  never retyped here, so they stay byte-for-byte identical.
* chat template with ``add_generation_prompt=True, enable_thinking=False``.
* greedy decoding, ``max_new_tokens=512``, ``repetition_penalty=1.05``,
  ``use_cache=True``; only the new tokens are decoded, with
  ``skip_special_tokens=True``, then stripped.

Image preprocessing (EXIF rotation, RGB, 512 shrink) happens in ``app.py``
before this function is called.

Nothing in this module imports torch, so it can be unit-tested with fakes.
"""

from __future__ import annotations

from typing import Any, Callable

IMAGE_FIRST = "image_first"  # inference notebook order: [image, text] (default, tested path)
TEXT_FIRST = "text_first"  # training order: [text, image]


def fill_caption(template: str, caption: str) -> str:
    """Insert the caption into ``PROMPT_WITH_CAPTION``.

    Uses ``str.replace`` on the placeholder rather than ``str.format`` so any
    other braces in the Bangla prompt are left alone.
    """
    if "{caption}" in template:
        return template.replace("{caption}", caption)
    if template.count("{}") == 1:
        return template.replace("{}", caption)
    raise ValueError(
        "PROMPT_WITH_CAPTION has no '{caption}' or single '{}' placeholder. "
        "Pass prompt_fn= to make_explain_fn with the notebook's own way of inserting the caption."
    )


def default_prompt_fn(prompt_with_caption: str, prompt_image_only: str) -> Callable[[str], str]:
    fill_caption(prompt_with_caption, "x")  # fail fast at startup, not on the first request

    def prompt_for(caption: str) -> str:
        return fill_caption(prompt_with_caption, caption) if caption else prompt_image_only

    return prompt_for


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
    prompt_fn: Callable[[str], str],
    *,
    content_order: str = IMAGE_FIRST,
    max_new_tokens: int = 512,
    repetition_penalty: float = 1.05,
    device: str = "cuda",
) -> Callable[[Any, str], str]:
    build_messages("", content_order)  # validate the flag now

    def explain_fn(image: Any, caption: str) -> str:
        messages = build_messages(prompt_fn(caption), content_order)
        input_text = tokenizer.apply_chat_template(
            messages,
            add_generation_prompt=True,
            enable_thinking=False,
        )
        inputs = tokenizer(
            image,
            input_text,
            add_special_tokens=False,
            return_tensors="pt",
        ).to(device)
        output = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            use_cache=True,
            do_sample=False,
            repetition_penalty=repetition_penalty,
        )
        prompt_len = inputs["input_ids"].shape[1]
        new_tokens = output[0][prompt_len:]
        return tokenizer.decode(new_tokens, skip_special_tokens=True).strip()

    return explain_fn
