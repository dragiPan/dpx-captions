"""Find and replace across every caption.

Replacement happens per word so each word keeps its own timing - rebuilding
a whole card from edited text would redistribute timings evenly and break
the word-level pop animation.
"""

from __future__ import annotations

import re
from dataclasses import replace

from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QLabel,
    QLineEdit,
    QVBoxLayout,
)

from ..core.caption_builder import CaptionCard


def replace_in_cards(
    cards: list[CaptionCard],
    find: str,
    replacement: str,
    match_case: bool = False,
    whole_word: bool = False,
) -> int:
    """Applies the replacement in place. Returns how many words changed."""
    if not find:
        return 0

    flags = 0 if match_case else re.IGNORECASE
    pattern = re.compile(rf"\b{re.escape(find)}\b" if whole_word else re.escape(find), flags)

    changed = 0
    for index, card in enumerate(cards):
        new_lines = []
        card_changed = False
        for line in card.lines:
            new_line = []
            for word in line:
                new_text = pattern.sub(replacement, word.text)
                if new_text != word.text:
                    changed += 1
                    card_changed = True
                    new_line.append(replace(word, text=new_text))
                else:
                    new_line.append(word)
            new_lines.append(new_line)
        if card_changed:
            cards[index] = replace(card, lines=new_lines)

    return changed


class FindReplaceDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Find and replace")
        self.setMinimumWidth(400)

        layout = QVBoxLayout(self)
        form = QFormLayout()
        self.find_edit = QLineEdit()
        self.replace_edit = QLineEdit()
        form.addRow("Find", self.find_edit)
        form.addRow("Replace with", self.replace_edit)
        layout.addLayout(form)

        self.match_case = QCheckBox("Match case")
        self.whole_word = QCheckBox("Whole word only")
        layout.addWidget(self.match_case)
        layout.addWidget(self.whole_word)

        self.result_label = QLabel("")
        self.result_label.setStyleSheet("color: #9ab;")
        layout.addWidget(self.result_label)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("Replace all")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self.find_edit.setFocus()
