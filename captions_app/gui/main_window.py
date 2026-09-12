from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from ..core import ffmpeg_util
from ..core.gpu import detect_compute_backend
from ..core.transcribe import MODEL_SIZES
from .caption_editor import CaptionEditorWidget
from .style_panel import StylePanel
from .workers import ExportWorker, GenerateWorker, run_in_thread


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Captions")
        self.resize(1280, 820)

        self.video_path: str | None = None
        self.video_info = None
        self.generate_thread = None
        self.generate_worker = None
        self.export_thread = None
        self.export_worker = None

        root = QHBoxLayout(self)
        splitter = QSplitter(Qt.Horizontal)
        root.addWidget(splitter)

        splitter.addWidget(self._build_left_panel())
        splitter.addWidget(self._build_right_panel())
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)

        self._check_ffmpeg()

    # --------------------------------------------------------------- Left side
    def _build_left_panel(self) -> QWidget:
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFixedWidth(420)

        container = QWidget()
        layout = QVBoxLayout(container)

        layout.addWidget(self._build_source_group())
        layout.addWidget(self._build_model_group())

        self.style_panel = StylePanel()
        self.style_panel.changed.connect(self._on_style_or_formatting_changed)
        layout.addWidget(self.style_panel)

        self.generate_btn = QPushButton("Generate")
        self.generate_btn.setEnabled(False)
        self.generate_btn.clicked.connect(self._on_generate_clicked)
        layout.addWidget(self.generate_btn)

        self.progress_bar = QProgressBar()
        self.progress_bar.setVisible(False)
        layout.addWidget(self.progress_bar)
        self.progress_label = QLabel("")
        layout.addWidget(self.progress_label)

        layout.addStretch(1)
        scroll.setWidget(container)
        return scroll

    def _build_source_group(self) -> QGroupBox:
        box = QGroupBox("Source")
        layout = QVBoxLayout(box)
        row = QHBoxLayout()
        self.source_label = QLabel("No video selected")
        self.source_label.setWordWrap(True)
        pick_btn = QPushButton("Choose Video File...")
        pick_btn.clicked.connect(self._on_choose_video)
        row.addWidget(pick_btn)
        layout.addLayout(row)
        layout.addWidget(self.source_label)
        return box

    def _build_model_group(self) -> QGroupBox:
        box = QGroupBox("Model")
        layout = QVBoxLayout(box)

        self.model_combo = QComboBox()
        for label in MODEL_SIZES:
            self.model_combo.addItem(label)
        self.model_combo.setCurrentText("Whisper Large v3 Turbo")
        layout.addWidget(self.model_combo)

        self.backend = detect_compute_backend()
        self.gpu_label = QLabel(f"Compute: {self.backend.label}")
        layout.addWidget(self.gpu_label)

        self.force_cpu_check = QCheckBox("Force CPU")
        self.force_cpu_check.toggled.connect(self._on_force_cpu_toggled)
        layout.addWidget(self.force_cpu_check)

        return box

    def _on_force_cpu_toggled(self, checked: bool) -> None:
        self.backend = detect_compute_backend(prefer_cpu=checked)
        self.gpu_label.setText(f"Compute: {self.backend.label}")

    # -------------------------------------------------------------- Right side
    def _build_right_panel(self) -> QWidget:
        container = QWidget()
        layout = QVBoxLayout(container)

        layout.addWidget(QLabel("Subtitles"))
        self.caption_editor = CaptionEditorWidget()
        self.caption_editor.cards_changed.connect(self._on_cards_changed)
        layout.addWidget(self.caption_editor)

        export_row = QHBoxLayout()
        self.export_btn = QPushButton("Export Video with Captions")
        self.export_btn.setEnabled(False)
        self.export_btn.clicked.connect(self._on_export_clicked)
        export_row.addStretch(1)
        export_row.addWidget(self.export_btn)
        layout.addLayout(export_row)

        return container

    # ------------------------------------------------------------------ Logic
    def _check_ffmpeg(self) -> None:
        if not ffmpeg_util.supports_ass_filter():
            QMessageBox.warning(
                self,
                "ffmpeg missing libass",
                "The ffmpeg found on this system doesn't include libass support, "
                "which is required to burn in animated captions. Please install a "
                "'full' ffmpeg build (e.g. gyan.dev's ffmpeg-release-full on Windows) "
                "and make sure it's on PATH before exporting.",
            )

    def _on_choose_video(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Choose Video", "", "Video Files (*.mp4 *.mov *.mkv *.avi)"
        )
        if not path:
            return
        self.video_path = path
        self.source_label.setText(Path(path).name)
        self.generate_btn.setEnabled(True)

    def _on_generate_clicked(self) -> None:
        if not self.video_path:
            return
        model_size = MODEL_SIZES[self.model_combo.currentText()]
        formatting = self.style_panel.formatting

        self.generate_btn.setEnabled(False)
        self.progress_bar.setVisible(True)
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)

        self.generate_worker = GenerateWorker(self.video_path, formatting, model_size, self.backend)
        self.generate_worker.progress.connect(self._on_generate_progress)
        self.generate_worker.finished.connect(self._on_generate_finished)
        self.generate_worker.failed.connect(self._on_generate_failed)
        self.generate_thread = run_in_thread(self.generate_worker)

    def _on_generate_progress(self, stage: str, frac: float) -> None:
        self.progress_bar.setValue(int(frac * 100))
        self.progress_label.setText(stage.replace("_", " ").title())

    def _on_generate_finished(self, result) -> None:
        self.video_info = result.video_info
        self.caption_editor.set_cards(result.cards, self.style_panel.formatting)
        self.progress_bar.setVisible(False)
        self.generate_btn.setEnabled(True)
        self.export_btn.setEnabled(True)

    def _on_generate_failed(self, message: str) -> None:
        self.progress_bar.setVisible(False)
        self.generate_btn.setEnabled(True)
        QMessageBox.critical(self, "Transcription failed", message)

    def _on_cards_changed(self) -> None:
        pass  # cards are edited in-place on caption_editor.cards

    def _on_style_or_formatting_changed(self) -> None:
        pass  # style is read live from style_panel at export time

    def _on_export_clicked(self) -> None:
        if not self.video_path or not self.video_info:
            return
        default_out = str(Path(self.video_path).with_stem(Path(self.video_path).stem + "_captioned"))
        out_path, _ = QFileDialog.getSaveFileName(self, "Export Video", default_out, "MP4 Video (*.mp4)")
        if not out_path:
            return

        self.export_btn.setEnabled(False)
        self.progress_bar.setVisible(True)
        self.progress_bar.setRange(0, 0)  # indeterminate; ffmpeg burn-in has no easy progress hook here

        self.export_worker = ExportWorker(
            self.video_path, self.caption_editor.cards, self.style_panel.style, self.video_info, out_path
        )
        self.export_worker.finished.connect(self._on_export_finished)
        self.export_worker.failed.connect(self._on_export_failed)
        self.export_thread = run_in_thread(self.export_worker)

    def _on_export_finished(self, out_path: str) -> None:
        self.progress_bar.setVisible(False)
        self.progress_bar.setRange(0, 100)
        self.export_btn.setEnabled(True)
        QMessageBox.information(self, "Export complete", f"Saved to:\n{out_path}")

    def _on_export_failed(self, message: str) -> None:
        self.progress_bar.setVisible(False)
        self.progress_bar.setRange(0, 100)
        self.export_btn.setEnabled(True)
        QMessageBox.critical(self, "Export failed", message)
