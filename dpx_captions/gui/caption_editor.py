"""Editable table of generated caption cards: fix transcription errors,
nudge timing, merge/split/delete cards.

v1 scope: text + timing editing (per the "Text + timing only" vs "Full
editing" choice, this covers text/timing; on-screen repositioning is a
global style control in the style panel, not per-card yet).
"""

from __future__ import annotations

from typing import Sequence

from PySide6.QtCore import QEvent, Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication, QKeySequence
from PySide6.QtWidgets import (
    QAbstractItemView,
    QHBoxLayout,
    QHeaderView,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..core.caption_builder import CaptionCard
from ..core.retime import rebuild_card, wrap_words
from ..core.style import CaptionFormatting
from ..core.transcribe import Word

COL_START, COL_END, COL_TEXT, COL_ACTIONS = range(4)


def _card_text(card: CaptionCard) -> str:
    return " ".join(w.text for line in card.lines for w in line)


def rebuild_card_text(
    card: CaptionCard,
    new_text: str,
    formatting: CaptionFormatting,
    reference: Sequence[Word] = (),
) -> list[CaptionCard]:
    """Applies edited text to a card, keeping each word on its spoken time
    (see core.retime). Returns several cards when the text contains "|"."""
    return rebuild_card(card, new_text, formatting, reference)


SPLIT_HINT = 'Tip: type "|" between words to split the card there, e.g. "OVO JE | ZAISTA TAKO".'


class CaptionEditorWidget(QWidget):
    cards_changed = Signal()
    add_card_requested = Signal()
    row_activated = Signal(int)  # the user moved to a row (click or arrow keys)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.formatting = CaptionFormatting()
        self.cards: list[CaptionCard] = []
        # Original Whisper words; lets an edit find the spoken time of a word
        # that wasn't in the card before.
        self.reference_words: list[Word] = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self.table = QTableWidget(0, 4, self)
        self.table.setHorizontalHeaderLabels(["Start (s)", "End (s)", "Text", ""])
        self.table.horizontalHeader().setSectionResizeMode(COL_TEXT, QHeaderView.Stretch)
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setToolTip(SPLIT_HINT)
        layout.addWidget(self.table)

        btn_row = QHBoxLayout()
        self.add_btn = QPushButton("Add Card")
        self.merge_btn = QPushButton("Merge Selected")
        self.delete_btn = QPushButton("Delete Selected")
        for b in (self.add_btn, self.merge_btn, self.delete_btn):
            btn_row.addWidget(b)
        btn_row.addStretch(1)
        layout.addLayout(btn_row)

        self.add_btn.clicked.connect(self.add_card_requested)
        self.merge_btn.clicked.connect(self._merge_selected)
        self.delete_btn.clicked.connect(self._delete_selected)
        self.table.itemChanged.connect(self._on_text_changed)
        self._selecting = False  # set while the row is selected from code
        self.table.currentCellChanged.connect(self._on_current_cell_changed)
        # Filtered on the table itself: while a cell is being edited the key
        # events go to the cell editor instead, so in-cell copy/paste still
        # behaves normally.
        self.table.installEventFilter(self)

    def eventFilter(self, obj, event) -> bool:  # noqa: N802
        if obj is self.table and event.type() == QEvent.KeyPress:
            if event.matches(QKeySequence.Copy):
                self._copy_selected()
                return True
            if event.matches(QKeySequence.Paste):
                self._paste_into_selected()
                return True
            if event.key() in (Qt.Key_Return, Qt.Key_Enter) and self.table.state() != QAbstractItemView.EditingState:
                row = self.table.currentRow()
                if 0 <= row < self.table.rowCount():
                    self.table.editItem(self.table.item(row, COL_TEXT))
                return True
        return super().eventFilter(obj, event)

    def _on_current_cell_changed(self, row: int, _col: int, prev_row: int, _prev_col: int) -> None:
        if not self._selecting and row != prev_row and 0 <= row < len(self.cards):
            self.row_activated.emit(row)

    def _copy_selected(self) -> None:
        rows = self._selected_rows()
        if not rows:
            return
        QGuiApplication.clipboard().setText("\n".join(_card_text(self.cards[r]) for r in rows))

    def _paste_into_selected(self) -> None:
        text = QGuiApplication.clipboard().text()
        rows = self._selected_rows()
        if not text.strip() or not rows:
            return
        lines = [line for line in text.splitlines() if line.strip()]
        # Bottom-up, so a row that splits into several doesn't shift the rest.
        for row, line in reversed(list(zip(rows, lines))):
            self.cards[row:row + 1] = rebuild_card_text(self.cards[row], line, self.formatting, self.reference_words)
        self._refresh_table()
        self.cards_changed.emit()

    def set_cards(self, cards: list[CaptionCard], formatting: CaptionFormatting) -> None:
        self.cards = list(cards)
        self.formatting = formatting
        self._refresh_table()

    def _refresh_table(self) -> None:
        self.table.blockSignals(True)
        self.table.setRowCount(len(self.cards))
        for row, card in enumerate(self.cards):
            self._populate_row(row, card)
        self.table.blockSignals(False)

    def _populate_row(self, row: int, card: CaptionCard) -> None:
        # Times are read-only here on purpose: they're edited by dragging on
        # the timeline, and editable spin boxes were being changed by stray
        # mouse-wheel scrolls over the list.
        for col, value in ((COL_START, card.start), (COL_END, card.end)):
            item = QTableWidgetItem(f"{value:.2f}")
            item.setFlags(Qt.ItemIsSelectable | Qt.ItemIsEnabled)
            self.table.setItem(row, col, item)

        text_item = QTableWidgetItem(_card_text(card))
        self.table.setItem(row, COL_TEXT, text_item)

        delete_btn = QPushButton("✕")
        delete_btn.setFixedWidth(28)
        delete_btn.clicked.connect(lambda _, r=row: self._delete_row(r))
        self.table.setCellWidget(row, COL_ACTIONS, delete_btn)

    def _on_text_changed(self, item: QTableWidgetItem) -> None:
        if item.column() != COL_TEXT:
            return
        row = item.row()
        if row >= len(self.cards):
            return
        new_cards = rebuild_card_text(self.cards[row], item.text(), self.formatting, self.reference_words)
        self.cards[row:row + 1] = new_cards
        if len(new_cards) > 1:
            # Rows were added; rebuild the table once the cell editor has closed.
            QTimer.singleShot(0, self._refresh_table)
        self.cards_changed.emit()

    def _selected_rows(self) -> list[int]:
        return sorted({idx.row() for idx in self.table.selectedIndexes()})

    def _delete_row(self, row: int) -> None:
        if 0 <= row < len(self.cards):
            del self.cards[row]
            self._refresh_table()
            self.cards_changed.emit()

    def _delete_selected(self) -> None:
        for row in reversed(self._selected_rows()):
            del self.cards[row]
        self._refresh_table()
        self.cards_changed.emit()

    def _merge_selected(self) -> None:
        rows = self._selected_rows()
        if len(rows) < 2:
            return
        words = [w for r in rows for w in self.cards[r].words]
        merged = CaptionCard(
            lines=wrap_words(words, self.formatting),
            start=self.cards[rows[0]].start,
            end=self.cards[rows[-1]].end,
        )
        for r in reversed(rows):
            del self.cards[r]
        self.cards.insert(rows[0], merged)
        self._refresh_table()
        self.cards_changed.emit()

    def select_row(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self._selecting = True
            try:
                self.table.selectRow(index)
                self.table.scrollToItem(self.table.item(index, COL_TEXT))
            finally:
                self._selecting = False
