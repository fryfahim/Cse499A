"""The prompts, disclaimer and side_check() must stay identical to the notebooks."""

import hashlib

from app import side_check_level
from notebook_logic import DISCLAIMER_BN, PROMPT_IMAGE_ONLY, PROMPT_WITH_CAPTION, make_prompt, side_check


def sha(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def test_strings_match_the_notebooks_byte_for_byte():
    # Hashes computed from the cell sources of ROCOv2_Bangla_Qwen35_4B_inference_app.ipynb
    # (the fine-tune notebook's two prompts are identical).
    assert sha(PROMPT_WITH_CAPTION) == "4e3354ae3733ab4ecc02cc0752f7e310ca53e71ebbe8cad6ed79605c5ce4d493"
    assert sha(PROMPT_IMAGE_ONLY) == "fee56e8ba87389abc20f863d290f6e75a7a5be27ae63fff910033be237bed7b5"
    assert sha(DISCLAIMER_BN) == "f6bdb7dd98db4d329555b2464c81e6519d56e777e92da0bc9dc78d737671dffb"


def test_make_prompt_modes():
    assert make_prompt("  CT of the head  ") == PROMPT_WITH_CAPTION.format(caption="CT of the head")
    assert make_prompt("") == PROMPT_IMAGE_ONLY
    assert make_prompt(None) == PROMPT_IMAGE_ONLY


def test_real_heldout_mismatch_is_a_warning():
    # ROCOv2_2023_test_000384 (held out, never trained on): the fine-tuned model's
    # output in the fine-tune notebook says LEFT while the caption says RIGHT.
    caption = "Focal right upper lobar opacity on chest X-ray"
    tuned = "এটি বুকের একটি এক্স-রে ছবি। এতে বাম ফুসফুসের উপরের অংশে একটি ছোট অস্বাভাবিক দাগ বা ঝাপসা অঞ্চল দেখা যাচ্ছে।"
    message = side_check(caption, tuned)
    assert message.startswith("WARNING: caption says RIGHT")
    assert side_check_level(message) == "warning"


def test_real_consistent_answer_is_ok():
    # ROCOv2_2023_test_000018 in the inference notebook: caption LEFT, answer বাম.
    caption = "Large left-sided pneumothorax with typical COVID-19 lungs infiltrate"
    answer = "এটি বুকের একটি সিটি স্ক্যান ছবি। এখানে বাম ফুসফুসে বাতাস জমে গেছে।"
    assert side_check_level(side_check(caption, answer)) == "ok"


def test_no_caption_is_not_checked():
    assert side_check_level(side_check("", "যেকোনো উত্তর")) == "not_checked"
