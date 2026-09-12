"""Audio peak extraction for the timeline waveform."""

from __future__ import annotations

import tempfile
import wave
from pathlib import Path

import numpy as np

from . import ffmpeg_util

BUCKETS_PER_SECOND = 100


def audio_cache_path(video_path: str) -> Path:
    tmp_dir = Path(tempfile.gettempdir()) / "dpx_captions"
    tmp_dir.mkdir(exist_ok=True)
    return tmp_dir / (Path(video_path).stem + ".wav")


def compute_peaks(video_path: str) -> np.ndarray:
    """Returns a normalized 0..1 peak per 1/BUCKETS_PER_SECOND of audio.

    Reuses the same extracted wav the transcription pipeline produces, so
    generating captions afterwards doesn't re-extract.
    """
    wav_path = audio_cache_path(video_path)
    if not wav_path.exists():
        ffmpeg_util.extract_audio(video_path, str(wav_path))

    with wave.open(str(wav_path), "rb") as wav:
        frame_rate = wav.getframerate()
        n_frames = wav.getnframes()
        raw = wav.readframes(n_frames)

    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    if samples.size == 0:
        return np.zeros(0, dtype=np.float32)

    samples_per_bucket = max(1, frame_rate // BUCKETS_PER_SECOND)
    usable = (samples.size // samples_per_bucket) * samples_per_bucket
    if usable == 0:
        return np.zeros(0, dtype=np.float32)

    reshaped = np.abs(samples[:usable]).reshape(-1, samples_per_bucket)
    peaks = reshaped.max(axis=1)
    peak_max = peaks.max()
    if peak_max > 0:
        peaks /= peak_max
    return peaks
