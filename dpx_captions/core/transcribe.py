"""Word-level transcription via faster-whisper, normalized to Serbian Latin."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Iterable

from .gpu import ComputeBackend, detect_compute_backend
from .serbian import cyrillic_to_latin

# faster-whisper is imported lazily inside functions that need it, so the
# rest of the app (GUI, style/caption logic) can be imported and unit tested
# without pulling in ctranslate2 / torch at all.

MODEL_SIZES = {
    "Whisper Large v3": "large-v3",
    "Whisper Large v3 Turbo": "large-v3-turbo",
}


@dataclass
class Word:
    text: str
    start: float
    end: float
    probability: float = 1.0


@dataclass
class TranscriptSegment:
    text: str
    start: float
    end: float
    words: list[Word]


def load_model(model_size: str = "large-v3-turbo", backend: ComputeBackend | None = None):
    from faster_whisper import WhisperModel

    backend = backend or detect_compute_backend()
    return WhisperModel(model_size, device=backend.device, compute_type=backend.compute_type)


def transcribe(
    audio_path: str,
    model_size: str = "large-v3-turbo",
    vocabulary_context: str = "",
    backend: ComputeBackend | None = None,
    progress_cb: Callable[[float], None] | None = None,
) -> list[TranscriptSegment]:
    """Transcribe Serbian audio and return word-level segments, Latin script.

    `vocabulary_context` is passed through as Whisper's initial_prompt hint,
    matching AutoSubs' vocabulary/context box (helps with names, gym/brand
    terminology, etc.).
    """
    model = load_model(model_size, backend)
    segments_iter, info = model.transcribe(
        audio_path,
        language="sr",
        word_timestamps=True,
        initial_prompt=vocabulary_context or None,
        vad_filter=True,
    )

    duration = info.duration or 0.0
    results: list[TranscriptSegment] = []
    for seg in segments_iter:
        words = [
            Word(
                text=cyrillic_to_latin(w.word.strip()),
                start=w.start,
                end=w.end,
                probability=w.probability,
            )
            for w in (seg.words or [])
            if w.word.strip()
        ]
        results.append(
            TranscriptSegment(
                text=cyrillic_to_latin(seg.text.strip()),
                start=seg.start,
                end=seg.end,
                words=words,
            )
        )
        if progress_cb and duration:
            progress_cb(min(seg.end / duration, 1.0))

    return results


def flatten_words(segments: Iterable[TranscriptSegment]) -> list[Word]:
    return [w for seg in segments for w in seg.words]
