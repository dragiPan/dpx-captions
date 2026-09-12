"""Dialog for downloading caption fonts into the app's fonts folder."""

from __future__ import annotations

from PySide6.QtCore import QObject, QThread, Qt, Signal
from PySide6.QtGui import QFontDatabase
from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QLabel,
    QMessageBox,
    QProgressBar,
    QVBoxLayout,
)

from ..core.font_packs import FONT_PACKS, FontPack, download_packs
from ..core.fonts import app_fonts_dir, font_files


class _DownloadWorker(QObject):
    progress = Signal(str)
    finished = Signal(list)
    failed = Signal(str)

    def __init__(self, packs: list[FontPack]):
        super().__init__()
        self.packs = packs

    def run(self) -> None:
        try:
            self.finished.emit(download_packs(self.packs, self.progress.emit))
        except Exception as exc:
            self.failed.emit(str(exc))


class FontDownloadDialog(QDialog):
    fonts_installed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Download caption fonts")
        self.setMinimumWidth(460)

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(f"Fonts are installed into:\n{app_fonts_dir()}"))

        installed = {p.name.lower() for p in font_files()}
        self.checks: list[tuple[QCheckBox, FontPack]] = []
        for pack in FONT_PACKS:
            have = all(name.lower() in installed for name in pack.files)
            check = QCheckBox(f"{pack.name} — {pack.description}" + ("  [installed]" if have else ""))
            check.setChecked(not have)
            layout.addWidget(check)
            self.checks.append((check, pack))

        self.status = QLabel("")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)

        self.progress = QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setVisible(False)
        layout.addWidget(self.progress)

        self.buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        self.buttons.button(QDialogButtonBox.Ok).setText("Download")
        self.buttons.accepted.connect(self._start_download)
        self.buttons.rejected.connect(self.reject)
        layout.addWidget(self.buttons)

        self._thread: QThread | None = None
        self._worker: _DownloadWorker | None = None

    def _start_download(self) -> None:
        selected = [pack for check, pack in self.checks if check.isChecked()]
        if not selected:
            self.accept()
            return

        self.buttons.setEnabled(False)
        self.progress.setVisible(True)

        self._worker = _DownloadWorker(selected)
        self._worker.progress.connect(self.status.setText)
        self._worker.finished.connect(self._on_finished)
        self._worker.failed.connect(self._on_failed)

        self._thread = QThread(self)
        self._worker.moveToThread(self._thread)
        self._thread.started.connect(self._worker.run)
        self._worker.finished.connect(self._thread.quit)
        self._worker.failed.connect(self._thread.quit)
        self._thread.start()

    def _on_finished(self, paths: list) -> None:
        for path in paths:
            QFontDatabase.addApplicationFont(path)
        self.progress.setVisible(False)
        self.fonts_installed.emit()
        QMessageBox.information(
            self, "Fonts installed",
            f"Installed {len(paths)} font file(s). They're now selectable in the Font list "
            "and will be used for the burned-in export too.",
        )
        self.accept()

    def _on_failed(self, message: str) -> None:
        self.progress.setVisible(False)
        self.buttons.setEnabled(True)
        QMessageBox.critical(self, "Download failed", message)
