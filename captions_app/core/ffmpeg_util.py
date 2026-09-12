"""Locates and wraps the ffmpeg/ffprobe binaries used for audio extraction,
probing source video properties, and burning in the final captions.

Prefers a system ffmpeg (in case the user has a newer/preferred build) and
falls back to the one bundled by imageio-ffmpeg, so the packaged .exe works
with zero external setup.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

_NO_WINDOW = subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0


def ffmpeg_path() -> str:
    system = shutil.which("ffmpeg")
    if system:
        return system
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def ffprobe_path() -> str:
    system = shutil.which("ffprobe")
    if system:
        return system
    # imageio-ffmpeg only bundles ffmpeg; ask it to report the probe info instead.
    return ""


@dataclass
class VideoInfo:
    width: int
    height: int
    fps: float
    duration: float
    video_codec: str
    audio_codec: str | None
    bit_rate: int | None


def probe(video_path: str) -> VideoInfo:
    probe_exe = ffprobe_path()
    if probe_exe:
        result = subprocess.run(
            [
                probe_exe, "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", video_path,
            ],
            capture_output=True, text=True, creationflags=_NO_WINDOW,
        )
        data = json.loads(result.stdout)
        v_stream = next(s for s in data["streams"] if s["codec_type"] == "video")
        a_stream = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)
        num, den = (v_stream.get("r_frame_rate") or "30/1").split("/")
        fps = float(num) / float(den) if float(den) else 30.0
        return VideoInfo(
            width=int(v_stream["width"]),
            height=int(v_stream["height"]),
            fps=fps,
            duration=float(data["format"].get("duration", 0.0)),
            video_codec=v_stream.get("codec_name", "h264"),
            audio_codec=a_stream.get("codec_name") if a_stream else None,
            bit_rate=int(data["format"]["bit_rate"]) if data["format"].get("bit_rate") else None,
        )

    # Fallback without ffprobe: use imageio_ffmpeg's reader metadata.
    import imageio_ffmpeg

    meta = imageio_ffmpeg.get_ffmpeg_version()  # sanity import
    reader = imageio_ffmpeg.read_frames(video_path)
    info = next(reader)
    reader.close()
    w, h = info["size"]
    return VideoInfo(
        width=w, height=h, fps=info.get("fps", 30.0), duration=info.get("duration", 0.0),
        video_codec="h264", audio_codec="aac", bit_rate=None,
    )


def supports_ass_filter() -> bool:
    """Checks whether the located ffmpeg was built with libass.

    Some minimal static ffmpeg builds (including some imageio-ffmpeg
    downloads) omit libass, which silently breaks caption burn-in. Callers
    should check this once at startup and tell the user to install a
    "full"/"with libass" ffmpeg build (e.g. gyan.dev's release-full on
    Windows) if it comes back False.
    """
    try:
        result = subprocess.run(
            [ffmpeg_path(), "-hide_banner", "-filters"],
            capture_output=True, text=True, creationflags=_NO_WINDOW,
        )
    except (subprocess.SubprocessError, OSError):
        return False
    return " ass " in result.stdout or "\nass" in result.stdout


def extract_audio(video_path: str, output_wav_path: str) -> str:
    cmd = [
        ffmpeg_path(), "-y", "-i", video_path,
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
        output_wav_path,
    ]
    subprocess.run(cmd, check=True, capture_output=True, creationflags=_NO_WINDOW)
    return output_wav_path


def burn_captions(
    video_path: str,
    ass_path: str,
    output_path: str,
    info: VideoInfo,
) -> None:
    """Burns the .ass captions into the video, re-encoding at a bitrate
    matched to the source so exported quality stays close to the original."""
    ass_escaped = str(Path(ass_path).resolve()).replace("\\", "/").replace(":", "\\:")
    target_bitrate = info.bit_rate or int(info.width * info.height * info.fps * 0.08)

    cmd = [
        ffmpeg_path(), "-y", "-i", video_path,
        "-vf", f"ass='{ass_escaped}'",
        "-c:v", "libx264", "-preset", "medium",
        "-b:v", str(target_bitrate), "-maxrate", str(int(target_bitrate * 1.5)),
        "-bufsize", str(int(target_bitrate * 2)),
        "-c:a", "copy",
        output_path,
    ]
    subprocess.run(cmd, check=True, capture_output=True, creationflags=_NO_WINDOW)
