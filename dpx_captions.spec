# PyInstaller build spec for DPX Captions.
#
# Produces a one-folder build rather than a single file: the bundle carries
# Qt, CTranslate2 and ONNX Runtime binaries, and a one-file build would
# unpack all of that to a temp folder on every launch.
#
# Whisper models are NOT bundled - faster-whisper downloads them to the
# Hugging Face cache on first use, so the first run needs a connection.

import os

from PyInstaller.utils.hooks import collect_all, collect_data_files, collect_submodules

# Set DPX_CONSOLE=1 to build with a console window, which is the only way
# to see a traceback if the packaged app fails to start.
CONSOLE = os.environ.get("DPX_CONSOLE") == "1"

datas = [("dpx_captions/presets", "dpx_captions/presets")]
binaries = []
hiddenimports = []

for package in ("faster_whisper", "ctranslate2", "onnxruntime", "tokenizers", "av"):
    pkg_datas, pkg_binaries, pkg_hidden = collect_all(package)
    datas += pkg_datas
    binaries += pkg_binaries
    hiddenimports += pkg_hidden

datas += collect_data_files("imageio_ffmpeg")

# Several GUI modules are imported lazily inside functions, so the import
# graph alone would miss them.
hiddenimports += collect_submodules("dpx_captions")

a = Analysis(
    ["run_dpx_captions.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "pytest", "PySide6.QtWebEngineCore"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="DPX Captions",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=CONSOLE,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="DPX Captions",
)
