"""Progress bar + label for Generate and Export: which step is running,
how far along it is, roughly how long is left and when it will be done."""

from __future__ import annotations

import time
from datetime import datetime, timedelta

from PySide6.QtCore import QObject, QTimer
from PySide6.QtWidgets import QLabel, QProgressBar

STAGES = {
    "extracting_audio": "Extracting audio",
    "loading_model": "Loading the Whisper model (the first run downloads it)",
    "transcribing": "Transcribing",
    "applying_learned_corrections": "Applying learned corrections",
    "grouping_captions": "Building captions",
    "preparing_captions": "Preparing captions",
    "encoding": "Encoding video",
}
# Steps that report a real fraction; the rest show a busy bar and a clock.
MEASURED = {"transcribing", "encoding"}


def _clock(seconds: float) -> str:
    seconds = max(0, round(seconds))
    return f"{seconds // 60}:{seconds % 60:02d}"


class ProgressReporter(QObject):
    def __init__(self, bar: QProgressBar, label: QLabel, parent=None):
        super().__init__(parent)
        self.bar = bar
        self.label = label
        self.label.setWordWrap(True)
        self._tick = QTimer(self)
        self._tick.setInterval(1000)
        self._tick.timeout.connect(self._render)
        self._task = ""
        self._stage = ""
        self._frac = 0.0
        self._started = 0.0
        self._stage_started = 0.0

    def start(self, task: str) -> None:
        self._task = task
        self._stage = ""
        self._frac = 0.0
        self._started = self._stage_started = time.monotonic()
        self.bar.setVisible(True)
        self.bar.setTextVisible(True)
        self._tick.start()
        self._render()

    def update(self, stage: str, frac: float) -> None:
        if stage != self._stage:
            self._stage = stage
            self._stage_started = time.monotonic()
        self._frac = max(0.0, min(frac, 1.0))
        self._render()

    def finish(self, message: str = "") -> None:
        self._tick.stop()
        self.bar.setVisible(False)
        self.bar.setRange(0, 100)
        total = time.monotonic() - self._started
        self.label.setText(f"{message} (took {_clock(total)})" if message else "")

    def _render(self) -> None:
        now = time.monotonic()
        elapsed = now - self._started
        name = STAGES.get(self._stage, self._stage.replace("_", " ").capitalize() or "Starting")

        if self._stage in MEASURED:
            self.bar.setRange(0, 1000)
            self.bar.setValue(round(self._frac * 1000))
            self.bar.setFormat(f"{self._frac * 100:.0f}%")
            stage_elapsed = now - self._stage_started
            if self._frac >= 0.02 and stage_elapsed >= 2:
                left = stage_elapsed * (1 - self._frac) / self._frac
                done_at = (datetime.now() + timedelta(seconds=left)).strftime("%H:%M")
                eta = f"about {_clock(left)} left, done around {done_at}"
            else:
                eta = "estimating time left..."
            text = f"{self._task}: {name} - {self._frac * 100:.0f}%\n{eta}  ·  {_clock(elapsed)} elapsed"
        else:
            self.bar.setRange(0, 0)  # busy
            text = f"{self._task}: {name}...\n{_clock(elapsed)} elapsed"
        self.label.setText(text)
