# Builds the standalone DPX Captions executable.
# Run from the project root: powershell -ExecutionPolicy Bypass -File scripts\build_exe.ps1

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".venv")) {
    Write-Host "No .venv found. Run scripts\setup_env.ps1 first."
    exit 1
}

& ".venv\Scripts\pip.exe" install --quiet pyinstaller
& ".venv\Scripts\pyinstaller.exe" --noconfirm --clean dpx_captions.spec

Write-Host ""
Write-Host "Build complete: dist\DPX Captions\DPX Captions.exe"
Write-Host "Copy the whole 'DPX Captions' folder to the other machine - the exe needs the files next to it."
Write-Host "That machine also needs ffmpeg with libass on PATH (winget install Gyan.FFmpeg)."
