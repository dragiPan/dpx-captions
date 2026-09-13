"""Undo/redo history for caption edits.

Snapshots are whole-card lists rather than per-action deltas: caption
edits are small and often touch several cards at once (an overwrite drag
can trim one card, delete another and move a third), so replaying a full
snapshot is both simpler and harder to get wrong than inverting each kind
of edit.
"""

from __future__ import annotations

import copy

from .caption_builder import CaptionCard

HISTORY_LIMIT = 100


class History:
    def __init__(self, limit: int = HISTORY_LIMIT):
        self._limit = limit
        self._undo: list[list[CaptionCard]] = []
        self._redo: list[list[CaptionCard]] = []
        self._current: list[CaptionCard] = []

    @staticmethod
    def _snapshot(cards: list[CaptionCard]) -> list[CaptionCard]:
        return copy.deepcopy(cards)

    def reset(self, cards: list[CaptionCard]) -> None:
        """Starts a fresh history, e.g. after generating new captions."""
        self._undo.clear()
        self._redo.clear()
        self._current = self._snapshot(cards)

    def record(self, cards: list[CaptionCard]) -> None:
        """Records a committed edit as the new current state."""
        self._undo.append(self._current)
        if len(self._undo) > self._limit:
            self._undo.pop(0)
        self._redo.clear()
        self._current = self._snapshot(cards)

    def can_undo(self) -> bool:
        return bool(self._undo)

    def can_redo(self) -> bool:
        return bool(self._redo)

    def undo(self) -> list[CaptionCard] | None:
        if not self._undo:
            return None
        self._redo.append(self._current)
        self._current = self._undo.pop()
        return self._snapshot(self._current)

    def redo(self) -> list[CaptionCard] | None:
        if not self._redo:
            return None
        self._undo.append(self._current)
        self._current = self._redo.pop()
        return self._snapshot(self._current)
