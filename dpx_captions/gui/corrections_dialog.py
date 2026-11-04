"""Lists the learned corrections and lets the user delete wrong ones."""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QAbstractItemView,
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
)

from ..core.corrections import CorrectionStore


class CorrectionsDialog(QDialog):
    def __init__(self, store: CorrectionStore, parent=None):
        super().__init__(parent)
        self.store = store
        self.setWindowTitle("Learned corrections")
        self.resize(560, 480)

        layout = QVBoxLayout(self)
        info = QLabel(
            "Learned from your edits each time you export. They are applied "
            "automatically the next time you click Generate."
        )
        info.setWordWrap(True)
        info.setStyleSheet("color: #9ab;")
        layout.addWidget(info)

        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText("Filter")
        self.filter_edit.textChanged.connect(self._refresh)
        layout.addWidget(self.filter_edit)

        self.table = QTableWidget(0, 4, self)
        self.table.setHorizontalHeaderLabels(["Whisper wrote", "Corrected to", "Applies", "Times"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setSortingEnabled(True)
        layout.addWidget(self.table)

        row = QHBoxLayout()
        delete_btn = QPushButton("Delete selected")
        delete_btn.clicked.connect(self._delete_selected)
        row.addWidget(delete_btn)
        row.addStretch(1)
        self.count_label = QLabel("")
        row.addWidget(self.count_label)
        layout.addLayout(row)

        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self._refresh()

    def _refresh(self) -> None:
        needle = self.filter_edit.text().strip().lower()
        rows = [
            (key, self.store.best(key), self.store.votes(key))
            for key in self.store.rules
            if not needle or needle in key or needle in (self.store.best(key) or "").lower()
        ]
        self.table.setSortingEnabled(False)
        self.table.setRowCount(len(rows))
        for r, (key, target, votes) in enumerate(rows):
            source_item = QTableWidgetItem(key)
            source_item.setData(Qt.UserRole, key)
            self.table.setItem(r, 0, source_item)
            self.table.setItem(r, 1, QTableWidgetItem(target))
            where = QTableWidgetItem("only in this phrase" if self.store.is_context(key) else "everywhere")
            where.setToolTip(
                "Learned with the words around it, because the edit only makes sense there"
                if self.store.is_context(key) else "A spelling fix applied wherever Whisper writes this"
            )
            self.table.setItem(r, 2, where)
            votes_item = QTableWidgetItem()
            votes_item.setData(Qt.DisplayRole, votes)
            self.table.setItem(r, 3, votes_item)
        self.table.setSortingEnabled(True)
        self.count_label.setText(f"{len(self.store.rules)} correction(s)")

    def _delete_selected(self) -> None:
        rows = {idx.row() for idx in self.table.selectedIndexes()}
        keys = [self.table.item(r, 0).data(Qt.UserRole) for r in rows]
        if not keys:
            return
        for key in keys:
            self.store.remove(key)
        self.store.save()
        self._refresh()
