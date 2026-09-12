"""QThread workers so transcription/export never block the UI thread."""

from __future__ import annotations

from PySide6.QtCore import QObject, QThread, Signal

from ..core import pipeline
from ..core.caption_builder import CaptionCard
from ..core.ffmpeg_util import VideoInfo
from ..core.gpu import ComputeBackend
from ..core.style import AnimationStyle, CaptionFormatting


class GenerateWorker(QObject):
    progress = Signal(str, float)
    finished = Signal(object)  # pipeline.GenerateResult
    failed = Signal(str)

    def __init__(self, video_path: str, formatting: CaptionFormatting, model_size: str, backend: ComputeBackend):
        super().__init__()
        self.video_path = video_path
        self.formatting = formatting
        self.model_size = model_size
        self.backend = backend

    def run(self) -> None:
        try:
            result = pipeline.generate_captions(
                self.video_path,
                self.formatting,
                model_size=self.model_size,
                backend=self.backend,
                progress_cb=lambda stage, frac: self.progress.emit(stage, frac),
            )
            self.finished.emit(result)
        except Exception as exc:  # surfaced in the UI, not a crash
            self.failed.emit(str(exc))


class ExportWorker(QObject):
    finished = Signal(str)
    failed = Signal(str)

    def __init__(self, video_path: str, cards: list[CaptionCard], style: AnimationStyle,
                 video_info: VideoInfo, output_path: str):
        super().__init__()
        self.video_path = video_path
        self.cards = cards
        self.style = style
        self.video_info = video_info
        self.output_path = output_path

    def run(self) -> None:
        try:
            pipeline.export_video(self.video_path, self.cards, self.style, self.video_info, self.output_path)
            self.finished.emit(self.output_path)
        except Exception as exc:
            self.failed.emit(str(exc))


def run_in_thread(worker: QObject) -> QThread:
    thread = QThread()
    worker.moveToThread(thread)
    thread.started.connect(worker.run)
    worker.finished.connect(thread.quit)
    worker.failed.connect(thread.quit)
    thread.start()
    return thread
