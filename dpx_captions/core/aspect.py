"""Target aspect ratio handling.

"Auto" keeps the source frame exactly as-is. Any fixed ratio pads the
source into a canvas of that ratio (never crops), so a landscape clip can
be delivered as a 9:16 Reel. Captions are laid out against the canvas, and
the export pads with the same maths so preview and output match.
"""

from __future__ import annotations

from enum import Enum


class Aspect(Enum):
    AUTO = "auto"
    VERTICAL_9_16 = "9:16"
    LANDSCAPE_16_9 = "16:9"
    SQUARE_1_1 = "1:1"
    PORTRAIT_4_5 = "4:5"

    @property
    def label(self) -> str:
        return "Auto (source)" if self is Aspect.AUTO else self.value

    @property
    def ratio(self) -> float | None:
        if self is Aspect.AUTO:
            return None
        w, h = self.value.split(":")
        return int(w) / int(h)


def canvas_size(source_width: int, source_height: int, aspect: Aspect) -> tuple[int, int]:
    """Canvas of the requested ratio, sized so its long side matches the
    source's long side.

    Keeping the long side fixed means a 1080x1920 clip delivered as 16:9
    becomes 1920x1080 (pillarboxed) rather than an inflated 3414x1920, and
    a 4K source stays 4K.
    """
    ratio = aspect.ratio
    if ratio is None:
        return source_width, source_height

    long_side = max(source_width, source_height)
    if ratio >= 1:
        width, height = long_side, round(long_side / ratio)
    else:
        width, height = round(long_side * ratio), long_side

    # h264 requires even dimensions.
    return width + (width % 2), height + (height % 2)
