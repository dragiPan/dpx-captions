from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSplitter,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from ..core import ffmpeg_util
from ..core.gpu import detect_compute_backend
from ..core.history import History
from ..core.project import PROJECT_SUFFIX, load_project, save_project
from ..core.settings import load_settings, save_settings
from ..core.transcribe import MODEL_SIZES
from .caption_editor import CaptionEditorWidget
from .find_replace import FindReplaceDialog, replace_in_cards
from .style_panel import StylePanel
from .timeline_widget import TimelineWidget
from .video_preview import VideoPreviewWidget
from .workers import ExportWorker, GenerateWorker, WaveformWorker, run_in_thread

TIMELINE_SCROLL_HEIGHT = 160


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("DPX Captions")
        self.resize(1280, 820)

        self.video_path: str | None = None
        self.video_info = None
        self.cards: list = []
        self.last_directory = ""
        self.generate_thread = None
        self.generate_worker = None
        self.export_thread = None
        self.export_worker = None
        self.waveform_thread = None
        self.waveform_worker = None
        self.history = History()
        self._applying_history = False

        root = QHBoxLayout(self)
        splitter = QSplitter(Qt.Horizontal)
        root.addWidget(splitter)

        splitter.addWidget(self._build_left_panel())
        splitter.addWidget(self._build_right_panel())
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)

        self._install_shortcuts()
        self._restore_settings()
        self._check_ffmpeg()

    def _install_shortcuts(self) -> None:
        for sequence, handler in (
            (QKeySequence.Undo, self._on_undo),
            (QKeySequence.Redo, self._on_redo),
            (QKeySequence("Ctrl+Y"), self._on_redo),
        ):
            shortcut = QShortcut(QKeySequence(sequence), self)
            shortcut.setContext(Qt.WindowShortcut)
            shortcut.activated.connect(handler)

    # --------------------------------------------------------------- Left side
    def _build_left_panel(self) -> QWidget:
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)

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
        # Wide enough for the controls plus the vertical scrollbar, so the
        # panel never needs to scroll sideways.
        scroll.setFixedWidth(container.sizeHint().width() + scroll.verticalScrollBar().sizeHint().width() + 8)
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

        project_row = QHBoxLayout()
        open_btn = QPushButton("Open Project...")
        open_btn.clicked.connect(self._on_open_project)
        save_btn = QPushButton("Save Project...")
        save_btn.clicked.connect(self._on_save_project)
        project_row.addWidget(open_btn)
        project_row.addWidget(save_btn)
        layout.addLayout(project_row)

        layout.addWidget(self.source_label)

        self.source_info_label = QLabel("")
        self.source_info_label.setStyleSheet("color: #9ab;")
        layout.addWidget(self.source_info_label)
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

        layout.addWidget(QLabel("Preview"))
        self.video_preview = VideoPreviewWidget()
        self.video_preview.setMinimumHeight(320)
        self.video_preview.position_changed.connect(self._on_preview_position_changed)
        layout.addWidget(self.video_preview, 2)

        tools = QHBoxLayout()
        self.undo_btn = QPushButton("Undo")
        self.undo_btn.setEnabled(False)
        self.undo_btn.clicked.connect(self._on_undo)
        self.redo_btn = QPushButton("Redo")
        self.redo_btn.setEnabled(False)
        self.redo_btn.clicked.connect(self._on_redo)
        tools.addWidget(self.undo_btn)
        tools.addWidget(self.redo_btn)

        self.split_btn = QPushButton("Cut at playhead (Ctrl+B)")
        self.split_btn.clicked.connect(lambda: self.timeline.split_at_playhead())
        self.delete_card_btn = QPushButton("Delete (Del)")
        self.delete_card_btn.clicked.connect(lambda: self.timeline.delete_selected())
        self.find_btn = QPushButton("Find && Replace")
        self.find_btn.clicked.connect(self._on_find_replace)
        zoom_out_btn = QPushButton("−")
        zoom_out_btn.setFixedWidth(32)
        zoom_out_btn.clicked.connect(lambda: self.timeline.zoom_by(1 / 1.3))
        zoom_in_btn = QPushButton("+")
        zoom_in_btn.setFixedWidth(32)
        zoom_in_btn.clicked.connect(lambda: self.timeline.zoom_by(1.3))
        fit_btn = QPushButton("Fit")
        fit_btn.setFixedWidth(48)
        fit_btn.clicked.connect(lambda: self.timeline.zoom_fit())
        for widget in (self.split_btn, self.delete_card_btn, self.find_btn):
            tools.addWidget(widget)
        tools.addStretch(1)
        tools.addWidget(QLabel("Zoom"))
        for widget in (zoom_out_btn, zoom_in_btn, fit_btn):
            tools.addWidget(widget)
        layout.addLayout(tools)

        timeline_scroll = QScrollArea()
        timeline_scroll.setWidgetResizable(False)
        timeline_scroll.setFixedHeight(TIMELINE_SCROLL_HEIGHT)
        timeline_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        timeline_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.timeline = TimelineWidget()
        self.timeline.cards_changed.connect(self._on_timeline_cards_changed)
        self.timeline.seek_requested.connect(self.video_preview.seek)
        self.timeline.card_selected.connect(self._on_timeline_card_selected)
        self.timeline.play_pause_requested.connect(self.video_preview.toggle_play)
        timeline_scroll.setWidget(self.timeline)
        self.timeline.scroll_area = timeline_scroll
        layout.addWidget(timeline_scroll)

        layout.addWidget(QLabel("Subtitles"))
        self.caption_editor = CaptionEditorWidget()
        self.caption_editor.cards_changed.connect(self._on_editor_cards_changed)
        self.caption_editor.add_card_requested.connect(self._on_add_card)
        layout.addWidget(self.caption_editor, 1)

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
            self, "Choose Video", self.last_directory, "Video Files (*.mp4 *.mov *.mkv *.avi)"
        )
        if not path:
            return
        self._load_video(path)

    def _load_video(self, path: str) -> None:
        self.video_path = path
        self.last_directory = str(Path(path).parent)
        self.source_label.setText(Path(path).name)
        self.generate_btn.setEnabled(True)
        self.video_preview.load_video(path)

        self.video_info = ffmpeg_util.probe(path)
        info = self.video_info
        minutes, seconds = divmod(info.duration, 60)
        self.source_info_label.setText(
            f"{int(minutes)}:{seconds:04.1f}  •  {info.width}×{info.height}  •  {info.fps:.2f} fps"
        )
        self.timeline.set_duration(info.duration)
        self.video_preview.set_style(self.style_panel.style)
        self._load_waveform(path)

    def _load_waveform(self, path: str) -> None:
        self.waveform_worker = WaveformWorker(path)
        self.waveform_worker.finished.connect(self.timeline.set_peaks)
        self.waveform_thread = run_in_thread(self.waveform_worker)

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
        self.cards = result.cards
        self.history.reset(self.cards)
        self._update_history_buttons()
        self._push_cards_everywhere()
        self.timeline.set_duration(result.video_info.duration)
        self.video_preview.set_style(self.style_panel.style)
        self.progress_bar.setVisible(False)
        self.generate_btn.setEnabled(True)
        self.export_btn.setEnabled(True)

    def _on_generate_failed(self, message: str) -> None:
        self.progress_bar.setVisible(False)
        self.generate_btn.setEnabled(True)
        QMessageBox.critical(self, "Transcription failed", message)

    def _push_cards_everywhere(self, skip: str | None = None) -> None:
        formatting = self.style_panel.formatting
        if skip != "editor":
            self.caption_editor.set_cards(list(self.cards), formatting)
        if skip != "timeline":
            self.timeline.set_cards(self.cards, formatting)
        self.video_preview.set_cards(self.cards)

    def _on_editor_cards_changed(self) -> None:
        if self._applying_history:
            return
        self.cards = self.caption_editor.cards
        self.history.record(self.cards)
        self._push_cards_everywhere(skip="editor")
        self._update_history_buttons()

    def _on_timeline_cards_changed(self) -> None:
        if self._applying_history:
            return
        self.cards = self.timeline.cards
        self.history.record(self.cards)
        self._push_cards_everywhere(skip="timeline")
        self._update_history_buttons()

    # ---------------------------------------------------------------- history
    def _on_undo(self) -> None:
        if self._delegate_to_focused_editor("undo"):
            return
        self._apply_history(self.history.undo())

    def _on_redo(self) -> None:
        if self._delegate_to_focused_editor("redo"):
            return
        self._apply_history(self.history.redo())

    @staticmethod
    def _delegate_to_focused_editor(action: str) -> bool:
        """Lets a focused text field handle its own undo/redo, so editing a
        caption's text doesn't get rolled back by the timeline's history."""
        widget = QApplication.focusWidget()
        if isinstance(widget, (QLineEdit, QPlainTextEdit, QTextEdit)):
            getattr(widget, action)()
            return True
        return False

    def _apply_history(self, cards) -> None:
        if cards is None:
            return
        self._applying_history = True
        try:
            self.cards = cards
            self._push_cards_everywhere()
        finally:
            self._applying_history = False
        self._update_history_buttons()

    def _update_history_buttons(self) -> None:
        self.undo_btn.setEnabled(self.history.can_undo())
        self.redo_btn.setEnabled(self.history.can_redo())

    def _on_timeline_card_selected(self, index: int) -> None:
        self.caption_editor.select_row(index)

    def _on_add_card(self) -> None:
        self.timeline.add_card_at_playhead()
        if self.timeline.selected_index is not None:
            self.caption_editor.select_row(self.timeline.selected_index)

    def _on_preview_position_changed(self, t: float) -> None:
        self.timeline.set_playhead(t)

    def _on_style_or_formatting_changed(self) -> None:
        self.video_preview.set_style(self.style_panel.style)

    # ---------------------------------------------------------------- project
    def _on_save_project(self) -> None:
        if not self.video_path:
            QMessageBox.information(self, "Nothing to save", "Load a video first.")
            return
        default = str(Path(self.video_path).with_suffix(PROJECT_SUFFIX))
        path, _ = QFileDialog.getSaveFileName(
            self, "Save Project", default, f"DPX Captions project (*{PROJECT_SUFFIX})"
        )
        if not path:
            return
        save_project(
            path, self.video_path, MODEL_SIZES[self.model_combo.currentText()],
            self.style_panel.formatting, self.style_panel.style, self.cards,
            self.video_preview.aspect(),
        )

    def _on_open_project(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Open Project", self.last_directory, f"DPX Captions project (*{PROJECT_SUFFIX})"
        )
        if not path:
            return

        try:
            data = load_project(path)
        except (OSError, ValueError, KeyError) as exc:
            QMessageBox.critical(self, "Could not open project", str(exc))
            return

        video_path = data["video_path"]
        if not Path(video_path).exists():
            QMessageBox.warning(
                self, "Video not found",
                f"The project refers to a video that isn't there any more:\n{video_path}\n\n"
                "Captions were loaded; pick the video again to preview or export.",
            )
        else:
            self._load_video(video_path)

        self.style_panel.apply_settings(data["style"], data["formatting"])
        self.video_preview.set_aspect(data["aspect"])
        for label, size in MODEL_SIZES.items():
            if size == data["model_size"]:
                self.model_combo.setCurrentText(label)

        self.cards = data["cards"]
        self.history.reset(self.cards)
        self._update_history_buttons()
        self._push_cards_everywhere()
        self.export_btn.setEnabled(bool(self.video_info))

    # ----------------------------------------------------------- find/replace
    def _on_find_replace(self) -> None:
        if not self.cards:
            QMessageBox.information(self, "No captions", "Generate captions first.")
            return

        dialog = FindReplaceDialog(self)
        if dialog.exec() != QDialog.Accepted:
            return

        count = replace_in_cards(
            self.cards,
            dialog.find_edit.text(),
            dialog.replace_edit.text(),
            dialog.match_case.isChecked(),
            dialog.whole_word.isChecked(),
        )
        if count:
            self.history.record(self.cards)
            self._update_history_buttons()
            self._push_cards_everywhere()
        QMessageBox.information(self, "Find and replace", f"Replaced {count} occurrence(s).")

    # --------------------------------------------------------------- settings
    def _restore_settings(self) -> None:
        saved = load_settings()
        if not saved:
            return
        self.style_panel.apply_settings(saved["style"], saved["formatting"])
        if saved["model_label"] in MODEL_SIZES:
            self.model_combo.setCurrentText(saved["model_label"])
        self.force_cpu_check.setChecked(saved["force_cpu"])
        self.last_directory = saved["last_directory"]
        self.video_preview.set_aspect(saved["aspect"])
        self.video_preview.set_style(self.style_panel.style)

    def _save_settings(self) -> None:
        save_settings(
            self.style_panel.style,
            self.style_panel.formatting,
            self.model_combo.currentText(),
            self.force_cpu_check.isChecked(),
            self.last_directory,
            self.video_preview.aspect(),
        )

    def closeEvent(self, event) -> None:  # noqa: N802
        self._save_settings()
        super().closeEvent(event)

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
            self.video_path, self.cards, self.style_panel.style, self.video_info, out_path,
            self.video_preview.aspect(),
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
