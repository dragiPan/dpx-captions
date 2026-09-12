"""Caption style schema.

`AnimationStyle` mirrors AutoSubs' `.autosubs-preset.json` `macroSettings`
field-for-field, so existing AutoSubs presets (like the "OpenSans Condensed
Yellow Text Fill" one) load and render the same way here. `CaptionFormatting`
covers the density/case/punctuation/censor controls from the app's own
"Options" panel, which live outside the AutoSubs preset format.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from enum import Enum
from pathlib import Path


@dataclass
class AnimationStyle:
    # Entrance animation
    AnimationLength: float = 0.2
    AnimationLevel: int = 0
    AnimationMode: int = 0
    PopInEnabled: int = 0
    SlideUpEnabled: int = 0
    FadeEnabled: int = 0

    # Font
    Font: str = "Open Sans Condensed"
    Style: str = "Bold"
    TextSize: float = 0.1          # fraction of frame height
    TextPosition: list[float] = field(default_factory=lambda: [0.5, 0.2, 0.0])

    # Fill (base/unsung) color
    FillEnabled: int = 1
    FillColorRed: float = 1.0
    FillColorGreen: float = 1.0
    FillColorBlue: float = 1.0

    # Highlight (active-word/sung) color
    HighlightEnabled: int = 1
    HighlightStyle: int = 0        # 0 = text-color fill (karaoke); 1 = box (not yet implemented)
    HighlightColorRed: float = 1.0
    HighlightColorGreen: float = 1.0
    HighlightColorBlue: float = 0.164
    HighlightExtendHorizontal: float = -0.04
    HighlightExtendVertical: float = 0.0
    HighlightRound: float = 0.0

    # Outline
    OutlineEnabled: int = 1
    OutlineThickness: float = 0.08
    OutlineColorRed: float = 0.0
    OutlineColorGreen: float = 0.0
    OutlineColorBlue: float = 0.0

    # Shadow
    ShadowEnabled: int = 1
    ShadowColorRed: float = 0.0
    ShadowColorGreen: float = 0.0
    ShadowColorBlue: float = 0.0

    @classmethod
    def from_preset_file(cls, path: str | Path) -> "AnimationStyle":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        settings = data.get("macroSettings", data)
        known = {f for f in cls.__dataclass_fields__}
        filtered = {k: v for k, v in settings.items() if k in known}
        return cls(**filtered)

    def to_preset_dict(self, name: str = "Custom", description: str = "") -> dict:
        return {
            "description": description,
            "macroSettings": asdict(self),
            "name": name,
            "version": 1,
        }

    def save_preset_file(self, path: str | Path, name: str = "Custom", description: str = "") -> None:
        Path(path).write_text(
            json.dumps(self.to_preset_dict(name, description), indent=2, ensure_ascii=False),
            encoding="utf-8",
        )


class Density(Enum):
    SINGLE_WORD = "single_word"
    STANDARD = "standard"
    MORE = "more"
    CUSTOM = "custom"


class TextCase(Enum):
    UPPERCASE = "uppercase"
    LOWERCASE = "lowercase"
    TITLE_CASE = "title_case"
    NORMAL = "normal"


@dataclass
class CaptionFormatting:
    density: Density = Density.CUSTOM
    max_chars_per_line: int = 17
    line_count: int = 1
    text_case: TextCase = TextCase.UPPERCASE
    remove_punctuation: bool = False
    censor_words: bool = False
    censor_word_list: list[str] = field(default_factory=list)
    vocabulary_context: str = ""   # fed to Whisper as initial_prompt

    def to_dict(self) -> dict:
        d = asdict(self)
        d["density"] = self.density.value
        d["text_case"] = self.text_case.value
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "CaptionFormatting":
        d = dict(d)
        if "density" in d:
            d["density"] = Density(d["density"])
        if "text_case" in d:
            d["text_case"] = TextCase(d["text_case"])
        return cls(**d)
