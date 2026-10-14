"""Prompt, disclaimer and left/right check, copied verbatim from
``ROCOv2_Bangla_Qwen35_4B_inference_app.ipynb`` (section 4: cells
"PROMPT_WITH_CAPTION ... make_prompt ... DISCLAIMER_BN" and "side_check").

Do not edit these by hand. ``tests/test_notebook_logic.py`` pins their SHA-256,
and the Colab server notebook asserts that its own copies are identical.
The fine-tune notebook uses the same two prompt strings.
"""

import re

PROMPT_WITH_CAPTION = """তুমি একজন অভিজ্ঞ ডাক্তার।

এই মেডিকেল ছবি এবং ক্যাপশন দেখে গ্রামের সাধারণ মানুষের জন্য সহজ বাংলায় মাত্র ২-৩টি ছোট বাক্যে লিখো।

ক্যাপশন:
{caption}

নিয়ম:

১। কঠিন মেডিকেল শব্দ লিখবে না।

২। শুধু বলবে—

• শরীরের কোন অংশ

• কী সমস্যা

৩। চিকিৎসা লিখবে না।

৪। শুধুই বাংলা লিখবে।
"""

PROMPT_IMAGE_ONLY = """তুমি একজন অভিজ্ঞ ডাক্তার।

এই মেডিকেল ছবি দেখে গ্রামের সাধারণ মানুষের জন্য সহজ বাংলায় মাত্র ২-৩টি ছোট বাক্যে লিখো।

নিয়ম:

১। কঠিন মেডিকেল শব্দ লিখবে না।

২। শুধু বলবে—

• শরীরের কোন অংশ

• কী সমস্যা

৩। চিকিৎসা লিখবে না।

৪। শুধুই বাংলা লিখবে।
"""

def make_prompt(caption):
    caption = (caption or "").strip()
    if caption:
        return PROMPT_WITH_CAPTION.format(caption = caption)
    return PROMPT_IMAGE_ONLY

DISCLAIMER_BN = "এটি শুধু ছবি বোঝার জন্য সাহায্য। এটি ডাক্তারের পরামর্শ নয়। অনুগ্রহ করে ডাক্তার দেখান।"


def side_check(caption, bangla):
    """Simple safety check: does left/right in the Bangla answer match the English caption?"""
    if not caption:
        return "No caption given, so left/right could not be checked."
    cap_left  = bool(re.search(r"\bleft\b",  caption, re.I))
    cap_right = bool(re.search(r"\bright\b", caption, re.I))
    out_left  = any(w in bangla for w in ["বাম", "বাঁ"])
    out_right = "ডান" in bangla
    if cap_left and not cap_right and out_right and not out_left:
        return "WARNING: caption says LEFT but the Bangla says RIGHT. Do not trust this answer."
    if cap_right and not cap_left and out_left and not out_right:
        return "WARNING: caption says RIGHT but the Bangla says LEFT. Do not trust this answer."
    return "Left/right check passed (or no side mentioned). This does not check anything else."
