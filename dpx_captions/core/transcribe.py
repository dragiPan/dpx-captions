"""Word-level transcription via faster-whisper, normalized to Serbian Latin."""

from __future__ import annotations

import threading
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
    # Normalized Whisper phrase this word was rewritten from by a learned
    # correction (core.corrections), or None for words as Whisper wrote them.
    source: str | None = None


@dataclass
class TranscriptSegment:
    text: str
    start: float
    end: float
    words: list[Word]


WHISPER_SAMPLE_RATE = 16000


def load_wav_samples(audio_path: str):
    """Reads the 16 kHz mono s16le wav from ffmpeg_util.extract_audio as float32.

    faster-whisper's own decoder goes through PyAV, and newer PyAV releases
    dropped the `metadata_errors` argument it passes to av.open(), so handing
    it a path fails. A ready-made sample array skips that decoder entirely.
    """
    import wave

    import numpy as np

    with wave.open(audio_path, "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (WHISPER_SAMPLE_RATE, 1, 2):
            raise ValueError(
                f"{audio_path} is not 16 kHz mono 16-bit PCM "
                f"({wav.getframerate()} Hz, {wav.getnchannels()} ch, {wav.getsampwidth() * 8}-bit)"
            )
        raw = wav.readframes(wav.getnframes())
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


_models: dict[tuple[str, str, str], object] = {}
_models_lock = threading.Lock()


def load_model(model_size: str = "large-v3-turbo", backend: ComputeBackend | None = None):
    """The Whisper model, kept in memory after the first load.

    Loading takes 5-40 s (the first time after boot, ~1.6 GB of model plus
    the CUDA libraries come off disk) against ~7 s to transcribe a 90 s clip,
    so it is loaded once per session and shared. The lock makes a Generate
    that arrives during the startup preload wait for it instead of loading a
    second copy into GPU memory.
    """
    from faster_whisper import WhisperModel

    backend = backend or detect_compute_backend()
    key = (model_size, backend.device, backend.compute_type)
    with _models_lock:
        model = _models.get(key)
        if model is None:
            _models.clear()  # one model at a time: switching frees the previous one's GPU memory
            model = WhisperModel(model_size, device=backend.device, compute_type=backend.compute_type)
            _models[key] = model
        return model


def preload_model(model_size: str, backend: ComputeBackend | None = None) -> bool:
    """Loads the model ahead of the first Generate, if it is already
    downloaded - never starts a download on its own. Returns whether it did."""
    from faster_whisper.utils import download_model

    try:
        download_model(model_size, local_files_only=True)
    except Exception:  # noqa: BLE001 - not downloaded yet (or no cache): leave it to Generate
        return False
    load_model(model_size, backend)
    return True


def transcribe(
    audio_path: str,
    model_size: str = "large-v3-turbo",
    vocabulary_context: str = "",
    backend: ComputeBackend | None = None,
    progress_cb: Callable[[float], None] | None = None,
    model_loaded_cb: Callable[[], None] | None = None,
) -> list[TranscriptSegment]:
    """Transcribe Serbian audio and return word-level segments, Latin script.

    `vocabulary_context` is passed through as Whisper's initial_prompt hint,
    matching AutoSubs' vocabulary/context box (helps with names, gym/brand
    terminology, etc.).
    """
    model = load_model(model_size, backend)
    if model_loaded_cb:
        model_loaded_cb()
    segments_iter, info = model.transcribe(
        load_wav_samples(audio_path),
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
