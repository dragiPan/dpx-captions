"""Persists the user's last-used style, caption options, and model choice
so a new session starts where the previous one left off."""

from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path

from .aspect import Aspect
from .style import AnimationStyle, CaptionFormatting


def settings_path() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    path = Path(base) / "DPX Captions" / "settings.json"

    legacy = Path(base) / "CaptionsApp" / "settings.json"
    if legacy.exists() and not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(legacy.read_bytes())

    return path


def save_settings(
    style: AnimationStyle,
    formatting: CaptionFormatting,
    model_label: str,
    force_cpu: bool,
    last_directory: str | None = None,
    aspect: Aspect = Aspect.AUTO,
) -> None:
    path = settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "version": 1,
        "style": asdict(style),
        "formatting": formatting.to_dict(),
        "model_label": model_label,
        "force_cpu": force_cpu,
        "last_directory": last_directory or "",
        "aspect": aspect.value,
    }
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def load_settings() -> dict | None:
    path = settings_path()
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        known_style = {f for f in AnimationStyle.__dataclass_fields__}
        style_data = {k: v for k, v in data.get("style", {}).items() if k in known_style}
        return {
            "style": AnimationStyle(**style_data),
            "formatting": CaptionFormatting.from_dict(data["formatting"]),
            "model_label": data.get("model_label", ""),
            "force_cpu": bool(data.get("force_cpu", False)),
            "last_directory": data.get("last_directory", ""),
            "aspect": Aspect(data.get("aspect", Aspect.AUTO.value)),
        }
    except (json.JSONDecodeError, KeyError, TypeError, ValueError):
        return None
