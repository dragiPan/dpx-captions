"""Serbian Cyrillic -> Latin transliteration.

Whisper's multilingual model was trained on Serbian text scraped from the
open web, which is a mix of both scripts, so "sr" transcripts can come back
in Cyrillic even though the user only wants Latin. The mapping is 1:1 and
lossless (unlike Russian/other Cyrillic scripts), so we can safely
normalize every transcript to Latin after the fact instead of relying on
the model to pick a script.
"""

from __future__ import annotations

# Order matters: multi-character digraphs (Nj/Lj/Dž) must be matched before
# their component letters.
_DIGRAPHS = {
    "Љ": "Lj", "љ": "lj",   # Љ љ
    "Њ": "Nj", "њ": "nj",   # Њ њ
    "Џ": "Dž", "џ": "dž",   # Џ џ
}

_SINGLE = {
    "А": "A", "а": "a",
    "Б": "B", "б": "b",
    "В": "V", "в": "v",
    "Г": "G", "г": "g",
    "Д": "D", "д": "d",
    "Ђ": "Đ", "ђ": "đ",     # Ђ ђ
    "Е": "E", "е": "e",
    "Ж": "Ž", "ж": "ž",     # Ж ж
    "З": "Z", "з": "z",
    "И": "I", "и": "i",
    "Ј": "J", "ј": "j",     # Ј ј
    "К": "K", "к": "k",
    "Л": "L", "л": "l",
    "М": "M", "м": "m",
    "Н": "N", "н": "n",
    "О": "O", "о": "o",
    "П": "P", "п": "p",
    "Р": "R", "р": "r",
    "С": "S", "с": "s",
    "Т": "T", "т": "t",
    "Ѓ": "Ć", "ѓ": "ć",     # Ћ ћ
    "У": "U", "у": "u",
    "Ф": "F", "ф": "f",
    "Х": "H", "х": "h",
    "Ц": "C", "ц": "c",
    "Ч": "Č", "ч": "č",     # Ч ч
    "Ш": "Š", "ш": "š",     # Ш ш
}

_TRANSLIT_TABLE = {**_DIGRAPHS, **_SINGLE}


def is_cyrillic(text: str) -> bool:
    return any(0x0400 <= ord(ch) <= 0x04FF for ch in text)


def cyrillic_to_latin(text: str) -> str:
    """Transliterate Serbian Cyrillic to Serbian Latin. Non-Cyrillic text
    (already Latin, punctuation, numbers) passes through unchanged."""
    if not is_cyrillic(text):
        return text

    out = []
    i = 0
    n = len(text)
    while i < n:
        matched = False
        for cyr, lat in _DIGRAPHS.items():
            if text.startswith(cyr, i):
                out.append(lat)
                i += len(cyr)
                matched = True
                break
        if matched:
            continue
        ch = text[i]
        out.append(_SINGLE.get(ch, ch))
        i += 1
    return "".join(out)
