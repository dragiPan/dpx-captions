"""Left-hand controls: vocabulary/context, caption density/case/punctuation/
censor options, and the AutoSubs-compatible animation style editor."""

from __future__ import annotations

from PySide6.QtWidgets import (
    QCheckBox,
    QColorDialog,
    QComboBox,
    QDoubleSpinBox,
    QFileDialog,
    QFontComboBox,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPlainTextEdit,
    QPushButton,
    QSlider,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)
from PySide6.QtCore import QEvent, QObject, Qt, Signal
from PySide6.QtGui import QColor, QFont, QFontInfo

from ..core.fonts import app_fonts_dir

from ..core.style import AnimationStyle, CaptionFormatting, Density, TextCase


class _WheelGuard(QObject):
    """Stops the mouse wheel from silently changing spin boxes/sliders that
    the cursor merely passes over while scrolling the panel."""

    def eventFilter(self, obj, event) -> bool:
        if event.type() == QEvent.Wheel and not obj.hasFocus():
            event.ignore()
            return True
        return False


def _color_button(get_rgb, set_rgb) -> tuple[QPushButton, callable]:
    btn = QPushButton()
    btn.setFixedWidth(48)

    def refresh():
        r, g, b = get_rgb()
        btn.setStyleSheet(f"background-color: rgb({round(r*255)},{round(g*255)},{round(b*255)});")

    def pick():
        r, g, b = get_rgb()
        color = QColorDialog.getColor(QColor.fromRgbF(r, g, b))
        if color.isValid():
            set_rgb(color.redF(), color.greenF(), color.blueF())
            refresh()

    btn.clicked.connect(pick)
    refresh()
    return btn, refresh


class StylePanel(QWidget):
    changed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.formatting = CaptionFormatting()
        self.style = AnimationStyle()
        self._wheel_guard = _WheelGuard(self)

        outer = QVBoxLayout(self)
        outer.addWidget(self._build_options_group())
        outer.addWidget(self._build_style_group())
        outer.addWidget(self._build_preset_group())
        outer.addStretch(1)
        self._install_wheel_guards()

    def _install_wheel_guards(self) -> None:
        for widget_type in (QSpinBox, QDoubleSpinBox, QComboBox, QSlider):
            for widget in self.findChildren(widget_type):
                widget.setFocusPolicy(Qt.StrongFocus)
                widget.installEventFilter(self._wheel_guard)

    # ---------------------------------------------------------------- Options
    def _build_options_group(self) -> QGroupBox:
        box = QGroupBox("Options")
        form = QFormLayout(box)

        self.vocab_edit = QPlainTextEdit()
        self.vocab_edit.setPlaceholderText("Names, brand/gym terms, context hints for the model...")
        self.vocab_edit.setFixedHeight(60)
        self.vocab_edit.textChanged.connect(self._on_vocab_changed)
        form.addRow("Vocabulary / Context", self.vocab_edit)

        self.density_combo = QComboBox()
        self.density_combo.addItem("Single word or less", Density.SINGLE_WORD)
        self.density_combo.addItem("Standard", Density.STANDARD)
        self.density_combo.addItem("More", Density.MORE)
        self.density_combo.addItem("Custom", Density.CUSTOM)
        self.density_combo.setCurrentIndex(3)
        self.density_combo.currentIndexChanged.connect(self._on_density_changed)
        form.addRow("Text density", self.density_combo)

        self.max_chars_spin = QSpinBox()
        self.max_chars_spin.setRange(1, 200)
        self.max_chars_spin.setValue(17)
        self.max_chars_spin.valueChanged.connect(self._on_formatting_field)
        form.addRow("Max characters / line", self.max_chars_spin)

        self.line_count_spin = QSpinBox()
        self.line_count_spin.setRange(1, 5)
        self.line_count_spin.setValue(1)
        self.line_count_spin.valueChanged.connect(self._on_formatting_field)
        form.addRow("Line count", self.line_count_spin)

        self.text_case_combo = QComboBox()
        self.text_case_combo.addItem("UPPERCASE", TextCase.UPPERCASE)
        self.text_case_combo.addItem("lowercase", TextCase.LOWERCASE)
        self.text_case_combo.addItem("Title Case", TextCase.TITLE_CASE)
        self.text_case_combo.addItem("Normal", TextCase.NORMAL)
        self.text_case_combo.currentIndexChanged.connect(self._on_formatting_field)
        form.addRow("Text case", self.text_case_combo)

        self.remove_punct_check = QCheckBox("Remove punctuation")
        self.remove_punct_check.toggled.connect(self._on_formatting_field)
        form.addRow("", self.remove_punct_check)

        self.censor_check = QCheckBox("Censor words")
        self.censor_check.toggled.connect(self._on_formatting_field)
        form.addRow("", self.censor_check)

        self.censor_words_edit = QLineEdit()
        self.censor_words_edit.setPlaceholderText("comma, separated, words")
        self.censor_words_edit.editingFinished.connect(self._on_formatting_field)
        form.addRow("Censor list", self.censor_words_edit)

        return box

    def _on_vocab_changed(self) -> None:
        self.formatting.vocabulary_context = self.vocab_edit.toPlainText()
        self.changed.emit()

    def _on_density_changed(self) -> None:
        self.formatting.density = self.density_combo.currentData()
        custom = self.formatting.density == Density.CUSTOM
        self.max_chars_spin.setEnabled(custom)
        self.line_count_spin.setEnabled(custom)
        self.changed.emit()

    def _on_formatting_field(self, *_args) -> None:
        self.formatting.max_chars_per_line = self.max_chars_spin.value()
        self.formatting.line_count = self.line_count_spin.value()
        self.formatting.text_case = self.text_case_combo.currentData()
        self.formatting.remove_punctuation = self.remove_punct_check.isChecked()
        self.formatting.censor_words = self.censor_check.isChecked()
        self.formatting.censor_word_list = [
            w.strip() for w in self.censor_words_edit.text().split(",") if w.strip()
        ]
        self.changed.emit()

    # ------------------------------------------------------------------ Style
    def _build_style_group(self) -> QGroupBox:
        box = QGroupBox("Caption Style (AutoSubs-compatible)")
        form = QFormLayout(box)

        self.font_combo = QFontComboBox()
        self.font_combo.setCurrentFont(QFont(self.style.Font))
        self.font_combo.currentFontChanged.connect(self._on_font_picked)
        form.addRow("Font", self.font_combo)

        self.font_warning = QLabel("")
        self.font_warning.setWordWrap(True)
        self.font_warning.setStyleSheet("color: #e0a030;")
        self.font_warning.setVisible(False)
        form.addRow("", self.font_warning)

        self.download_fonts_btn = QPushButton("Download caption fonts...")
        self.download_fonts_btn.clicked.connect(self._open_font_downloader)
        form.addRow("", self.download_fonts_btn)

        self.text_size_slider = self._slider(1, 40, int(self.style.TextSize * 100))
        form.addRow("Text size", self.text_size_slider)

        pos_row = QHBoxLayout()
        self.pos_x_slider = self._slider(0, 100, int(self.style.TextPosition[0] * 100))
        self.pos_y_slider = self._slider(0, 100, int(self.style.TextPosition[1] * 100))
        pos_row.addWidget(QLabel("X"))
        pos_row.addWidget(self.pos_x_slider)
        pos_row.addWidget(QLabel("Y"))
        pos_row.addWidget(self.pos_y_slider)
        form.addRow("Position", pos_row)

        self.fill_color_btn, self._refresh_fill_color = _color_button(
            lambda: (self.style.FillColorRed, self.style.FillColorGreen, self.style.FillColorBlue),
            self._set_fill_color,
        )
        form.addRow("Fill color (unsung)", self.fill_color_btn)

        self.highlight_color_btn, self._refresh_highlight_color = _color_button(
            lambda: (self.style.HighlightColorRed, self.style.HighlightColorGreen, self.style.HighlightColorBlue),
            self._set_highlight_color,
        )
        form.addRow("Highlight color (sung)", self.highlight_color_btn)

        self.outline_check = QCheckBox("Outline")
        self.outline_check.setChecked(bool(self.style.OutlineEnabled))
        self.outline_check.toggled.connect(self._on_style_field)
        form.addRow("", self.outline_check)

        self.shadow_check = QCheckBox("Shadow")
        self.shadow_check.setChecked(bool(self.style.ShadowEnabled))
        self.shadow_check.toggled.connect(self._on_style_field)
        form.addRow("", self.shadow_check)

        self.pop_in_check = QCheckBox("Pop in")
        self.pop_in_check.toggled.connect(self._on_style_field)
        self.slide_up_check = QCheckBox("Slide up")
        self.slide_up_check.toggled.connect(self._on_style_field)
        self.fade_check = QCheckBox("Fade")
        self.fade_check.toggled.connect(self._on_style_field)
        anim_row = QHBoxLayout()
        anim_row.addWidget(self.pop_in_check)
        anim_row.addWidget(self.slide_up_check)
        anim_row.addWidget(self.fade_check)
        form.addRow("Entrance animation", anim_row)

        self.anim_length_spin = QDoubleSpinBox()
        self.anim_length_spin.setRange(0.0, 2.0)
        self.anim_length_spin.setSingleStep(0.05)
        self.anim_length_spin.setValue(self.style.AnimationLength)
        self.anim_length_spin.valueChanged.connect(self._on_style_field)
        form.addRow("Animation length (s)", self.anim_length_spin)

        for slider in (self.text_size_slider, self.pos_x_slider, self.pos_y_slider):
            slider.valueChanged.connect(self._on_style_field)

        return box

    @staticmethod
    def _slider(lo: int, hi: int, val: int) -> QSlider:
        s = QSlider(Qt.Horizontal)
        s.setRange(lo, hi)
        s.setValue(val)
        return s

    def _set_fill_color(self, r, g, b) -> None:
        self.style.FillColorRed, self.style.FillColorGreen, self.style.FillColorBlue = r, g, b
        self.changed.emit()

    def _set_highlight_color(self, r, g, b) -> None:
        self.style.HighlightColorRed, self.style.HighlightColorGreen, self.style.HighlightColorBlue = r, g, b
        self.changed.emit()

    def _open_font_downloader(self) -> None:
        from .font_dialog import FontDownloadDialog

        dialog = FontDownloadDialog(self)
        dialog.fonts_installed.connect(self._on_fonts_installed)
        dialog.exec()

    def _on_fonts_installed(self) -> None:
        # Re-select the configured family: it may exist now that new font
        # files have been registered with Qt.
        self.font_combo.blockSignals(True)
        self.font_combo.setCurrentFont(QFont(self.style.Font))
        self.font_combo.blockSignals(False)
        self._update_font_warning()
        self.changed.emit()

    def _on_font_picked(self, font: QFont) -> None:
        self.style.Font = font.family()
        self._update_font_warning()
        self.changed.emit()

    def _update_font_warning(self) -> None:
        resolved = QFontInfo(QFont(self.style.Font)).family()
        missing = resolved.lower() != self.style.Font.lower()
        if missing:
            self.font_warning.setText(
                f"'{self.style.Font}' is not installed — preview and export will use "
                f"'{resolved}'. Drop the font file into {app_fonts_dir()} to use it."
            )
        self.font_warning.setVisible(missing)

    def _on_style_field(self, *_args) -> None:
        self.style.TextSize = self.text_size_slider.value() / 100.0
        self.style.TextPosition = [self.pos_x_slider.value() / 100.0, self.pos_y_slider.value() / 100.0, 0.0]
        self.style.OutlineEnabled = int(self.outline_check.isChecked())
        self.style.ShadowEnabled = int(self.shadow_check.isChecked())
        self.style.PopInEnabled = int(self.pop_in_check.isChecked())
        self.style.SlideUpEnabled = int(self.slide_up_check.isChecked())
        self.style.FadeEnabled = int(self.fade_check.isChecked())
        self.style.AnimationLength = self.anim_length_spin.value()
        self.changed.emit()

    # ----------------------------------------------------------------- Preset
    def _build_preset_group(self) -> QGroupBox:
        box = QGroupBox("AutoSubs Preset")
        row = QHBoxLayout(box)
        load_btn = QPushButton("Load .autosubs-preset.json")
        save_btn = QPushButton("Save preset")
        load_btn.clicked.connect(self._load_preset)
        save_btn.clicked.connect(self._save_preset)
        row.addWidget(load_btn)
        row.addWidget(save_btn)
        return box

    def _load_preset(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Load AutoSubs Preset", "", "AutoSubs Preset (*.json)")
        if not path:
            return
        self.style = AnimationStyle.from_preset_file(path)
        self.apply_style_to_widgets()
        self.changed.emit()

    def _save_preset(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Save AutoSubs Preset", "", "AutoSubs Preset (*.json)")
        if not path:
            return
        self.style.save_preset_file(path)

    def _editable_widgets(self) -> list:
        return [
            self.vocab_edit, self.density_combo, self.max_chars_spin, self.line_count_spin,
            self.text_case_combo, self.remove_punct_check, self.censor_check, self.censor_words_edit,
            self.font_combo, self.text_size_slider, self.pos_x_slider, self.pos_y_slider,
            self.outline_check, self.shadow_check, self.pop_in_check, self.slide_up_check,
            self.fade_check, self.anim_length_spin,
        ]

    def apply_style_to_widgets(self) -> None:
        for widget in self._editable_widgets():
            widget.blockSignals(True)
        try:
            self._write_style_widgets()
        finally:
            for widget in self._editable_widgets():
                widget.blockSignals(False)

    def apply_settings(self, style: AnimationStyle, formatting: CaptionFormatting) -> None:
        self.style = style
        self.formatting = formatting
        for widget in self._editable_widgets():
            widget.blockSignals(True)
        try:
            self._write_style_widgets()
            self._write_formatting_widgets()
        finally:
            for widget in self._editable_widgets():
                widget.blockSignals(False)
        custom = self.formatting.density == Density.CUSTOM
        self.max_chars_spin.setEnabled(custom)
        self.line_count_spin.setEnabled(custom)
        self.changed.emit()

    def _write_style_widgets(self) -> None:
        self.font_combo.setCurrentFont(QFont(self.style.Font))
        self._update_font_warning()
        self.text_size_slider.setValue(int(self.style.TextSize * 100))
        self.pos_x_slider.setValue(int(self.style.TextPosition[0] * 100))
        self.pos_y_slider.setValue(int(self.style.TextPosition[1] * 100))
        self.outline_check.setChecked(bool(self.style.OutlineEnabled))
        self.shadow_check.setChecked(bool(self.style.ShadowEnabled))
        self.pop_in_check.setChecked(bool(self.style.PopInEnabled))
        self.slide_up_check.setChecked(bool(self.style.SlideUpEnabled))
        self.fade_check.setChecked(bool(self.style.FadeEnabled))
        self.anim_length_spin.setValue(self.style.AnimationLength)
        self._refresh_fill_color()
        self._refresh_highlight_color()

    def _write_formatting_widgets(self) -> None:
        self.vocab_edit.setPlainText(self.formatting.vocabulary_context)
        self.density_combo.setCurrentIndex(max(0, self.density_combo.findData(self.formatting.density)))
        self.max_chars_spin.setValue(self.formatting.max_chars_per_line)
        self.line_count_spin.setValue(self.formatting.line_count)
        self.text_case_combo.setCurrentIndex(max(0, self.text_case_combo.findData(self.formatting.text_case)))
        self.remove_punct_check.setChecked(self.formatting.remove_punctuation)
        self.censor_check.setChecked(self.formatting.censor_words)
        self.censor_words_edit.setText(", ".join(self.formatting.censor_word_list))
