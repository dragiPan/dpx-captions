"""High-level orchestration: video -> audio -> transcript -> caption cards -> burned export."""

from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import ffmpeg_util
from .aspect import Aspect, canvas_size
from .caption_builder import CaptionCard, build_captions
from .gpu import ComputeBackend, detect_compute_backend
from .style import AnimationStyle, CaptionFormatting
from .transcribe import flatten_words, transcribe


@dataclass
class GenerateResult:
    cards: list[CaptionCard]
    video_info: ffmpeg_util.VideoInfo
    audio_path: Path


def generate_captions(
    video_path: str,
    formatting: CaptionFormatting,
    model_size: str = "large-v3-turbo",
    backend: ComputeBackend | None = None,
    progress_cb: Callable[[str, float], None] | None = None,
) -> GenerateResult:
    """Runs audio extraction + transcription + caption grouping for a video."""
    backend = backend or detect_compute_backend()
    video_info = ffmpeg_util.probe(video_path)

    tmp_dir = Path(tempfile.gettempdir()) / "dpx_captions"
    tmp_dir.mkdir(exist_ok=True)
    audio_path = tmp_dir / (Path(video_path).stem + ".wav")

    if progress_cb:
        progress_cb("extracting_audio", 0.0)
    ffmpeg_util.extract_audio(video_path, str(audio_path))

    if progress_cb:
        progress_cb("transcribing", 0.0)

    def _tcb(frac: float):
        if progress_cb:
            progress_cb("transcribing", frac)

    segments = transcribe(
        str(audio_path),
        model_size=model_size,
        vocabulary_context=formatting.vocabulary_context,
        backend=backend,
        progress_cb=_tcb,
    )
    words = flatten_words(segments)

    if progress_cb:
        progress_cb("grouping_captions", 1.0)
    cards = build_captions(words, formatting)

    return GenerateResult(cards=cards, video_info=video_info, audio_path=audio_path)


def export_video(
    video_path: str,
    cards: list[CaptionCard],
    style: AnimationStyle,
    video_info: ffmpeg_util.VideoInfo,
    output_path: str,
    aspect: Aspect = Aspect.AUTO,
) -> str:
    if not ffmpeg_util.supports_ass_filter():
        raise RuntimeError(
            "The available ffmpeg build does not include libass, so captions "
            "can't be burned in. Install a 'full' ffmpeg build (e.g. gyan.dev's "
            "ffmpeg-release-full on Windows) and make sure it's on PATH."
        )

    from .ass_writer import write_ass

    canvas = canvas_size(video_info.width, video_info.height, aspect)

    tmp_dir = Path(tempfile.gettempdir()) / "dpx_captions"
    tmp_dir.mkdir(exist_ok=True)
    ass_path = tmp_dir / (Path(video_path).stem + ".ass")
    write_ass(cards, style, canvas[0], canvas[1], ass_path)

    ffmpeg_util.burn_captions(video_path, str(ass_path), output_path, video_info, canvas)
    return output_path
