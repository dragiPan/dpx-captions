# Sets up the captions-app Python environment.
# Run from the project root: powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".venv")) {
    python -m venv .venv
}

& ".venv\Scripts\python.exe" -m pip install --upgrade pip
& ".venv\Scripts\pip.exe" install -e .

$hasNvidia = $false
try {
    $null = & nvidia-smi -L 2>$null
    if ($LASTEXITCODE -eq 0) { $hasNvidia = $true }
} catch {}

if ($hasNvidia) {
    Write-Host "NVIDIA GPU detected - installing CUDA runtime libraries for faster-whisper GPU acceleration..."
    & ".venv\Scripts\pip.exe" install "nvidia-cublas-cu12" "nvidia-cudnn-cu12"
    Write-Host "NOTE: if transcription falls back to CPU despite this, add the cuDNN/cuBLAS DLL folders"
    Write-Host "(inside .venv\Lib\site-packages\nvidia\...\bin) to PATH, or install cuDNN 9 system-wide."
} else {
    Write-Host "No NVIDIA GPU detected on this machine - will use CPU (int8) transcription."
}

$ffmpegOk = $false
try {
    $out = & ffmpeg -hide_banner -filters 2>$null
    if ($out -match "\bass\b") { $ffmpegOk = $true }
} catch {}

if (-not $ffmpegOk) {
    Write-Host ""
    Write-Host "WARNING: no system ffmpeg with libass found. Caption burn-in needs a 'full' ffmpeg build."
    Write-Host "Install one (e.g. 'winget install Gyan.FFmpeg' on Windows) and ensure it's on PATH."
    Write-Host "(The app also bundles a fallback ffmpeg via imageio-ffmpeg, but that build may lack libass.)"
}

Write-Host ""
Write-Host "Setup complete. Run the app with:"
Write-Host "  .venv\Scripts\python.exe -m captions_app.main"
