"""GPU vendor detection for picking a faster-whisper compute backend.

faster-whisper (CTranslate2) only accelerates on NVIDIA GPUs via CUDA/cuDNN.
AMD and Intel GPUs have no CTranslate2 GPU backend on Windows, so anything
non-NVIDIA falls back to a quantized CPU path.
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from enum import Enum


class GpuVendor(Enum):
    NVIDIA = "nvidia"
    OTHER = "other"
    NONE = "none"


@dataclass(frozen=True)
class ComputeBackend:
    vendor: GpuVendor
    device: str  # "cuda" or "cpu", as accepted by faster-whisper's WhisperModel
    compute_type: str  # e.g. "float16", "int8_float16", "int8"
    label: str  # human-readable, for the UI

    @property
    def uses_gpu(self) -> bool:
        return self.device == "cuda"


def detect_nvidia_gpu() -> tuple[bool, str | None]:
    """Return (has_nvidia, gpu_name) by shelling out to nvidia-smi.

    nvidia-smi ships with every NVIDIA driver install (not just the CUDA
    toolkit), so its presence is a reliable, dependency-free vendor check.
    """
    exe = shutil.which("nvidia-smi")
    if not exe:
        return False, None
    try:
        result = subprocess.run(
            [exe, "--query-gpu=name", "--format=csv,noheader"],
            capture_output=True,
            text=True,
            timeout=5,
            creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0,
        )
    except (subprocess.SubprocessError, OSError):
        return False, None
    if result.returncode != 0:
        return False, None
    name = result.stdout.strip().splitlines()[0].strip() if result.stdout.strip() else None
    return True, name


def detect_compute_backend(prefer_cpu: bool = False) -> ComputeBackend:
    """Auto-detect the best available backend.

    `prefer_cpu` lets the UI offer a manual "force CPU" override even when
    an NVIDIA GPU is present.
    """
    if prefer_cpu:
        return ComputeBackend(GpuVendor.NONE, "cpu", "int8", "CPU (forced)")

    has_nvidia, name = detect_nvidia_gpu()
    if has_nvidia:
        label = f"NVIDIA GPU ({name})" if name else "NVIDIA GPU"
        return ComputeBackend(GpuVendor.NVIDIA, "cuda", "float16", label)

    # Non-NVIDIA (AMD/Intel) or no discrete GPU at all: CTranslate2 has no
    # ROCm/DirectML backend, so we quantize hard for the best CPU throughput.
    return ComputeBackend(GpuVendor.OTHER, "cpu", "int8", "CPU (no CUDA GPU detected)")
