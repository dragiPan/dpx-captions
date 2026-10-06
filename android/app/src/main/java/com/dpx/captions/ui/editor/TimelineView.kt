package com.dpx.captions.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpx.captions.audio.Waveform
import com.dpx.captions.core.CaptionOps
import com.dpx.captions.model.CaptionCard
import com.dpx.captions.ui.CaptionBlue
import com.dpx.captions.ui.CaptionBlueSelected
import com.dpx.captions.ui.OnSurfaceDim
import com.dpx.captions.ui.Surface0
import com.dpx.captions.ui.Surface1
import com.dpx.captions.ui.Waveform as WaveformColor
import com.dpx.captions.ui.Accent
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val TimelineHeight: Dp = 176.dp

private val RulerHeight = 26.dp
private val WaveHeight = 58.dp
private val CardHeight = 62.dp
private val HandleTouch = 26.dp
private val HandleVisual = 12.dp
private val SnapDistance = 10.dp

const val MIN_PPS = 6f
const val MAX_PPS = 600f

private enum class DragMode { PAN, MOVE, TRIM_START, TRIM_END }

/** A card being dragged: the live start/end that are drawn, before the edit is committed. */
private class Drag(val index: Int, val mode: DragMode, val originalStart: Double, val originalEnd: Double) {
    var start by mutableStateOf(originalStart)
    var end by mutableStateOf(originalEnd)
}

/**
 * CapCut-style caption timeline: the playhead stays fixed in the middle and the content scrolls under
 * it. Drag empty space or an unselected caption to scrub, pinch to zoom, tap a caption to select it,
 * then drag its body to move it or its handles to trim. Dropping a caption on top of others overwrites
 * them, the way a timeline overwrite edit does.
 */
@Composable
fun TimelineView(
    cards: List<CaptionCard>,
    selected: Int?,
    durationSeconds: Double,
    peaks: FloatArray?,
    positionSeconds: () -> Double,
    pixelsPerSecond: Float,
    onZoom: (Float) -> Unit,
    onScrub: (seconds: Double, finished: Boolean) -> Unit,
    onSelect: (Int?) -> Unit,
    onCommit: (cards: List<CaptionCard>, selected: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // The gesture block is keyed only on things that rarely change, so everything that changes under
    // it is read through these.
    val currentCards by rememberUpdatedState(cards)
    val currentSelected by rememberUpdatedState(selected)
    val currentPps by rememberUpdatedState(pixelsPerSecond)
    val currentDuration by rememberUpdatedState(durationSeconds)
    val currentPosition by rememberUpdatedState(positionSeconds)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnCommit by rememberUpdatedState(onCommit)

    var drag by remember { mutableStateOf<Drag?>(null) }
    var fling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val rulerPx = with(density) { RulerHeight.toPx() }
    val wavePx = with(density) { WaveHeight.toPx() }
    val cardPx = with(density) { CardHeight.toPx() }
    val handleTouchPx = with(density) { HandleTouch.toPx() }
    val snapPx = with(density) { SnapDistance.toPx() }
    val trackTopPx = rulerPx + wavePx + with(density) { 8.dp.toPx() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .background(Surface0)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling?.cancel()

                    val pps = currentPps
                    val width = size.width.toFloat()
                    val centerX = width / 2
                    val position = currentPosition()
                    fun xOf(t: Double) = centerX + ((t - position) * pps).toFloat()
                    fun tOf(x: Float) = position + (x - centerX) / pps

                    // Work out what the finger landed on.
                    val list = currentCards
                    val sel = currentSelected
                    var target = DragMode.PAN
                    var targetIndex: Int? = null
                    val onCardRow = down.position.y in trackTopPx..(trackTopPx + cardPx)

                    if (onCardRow) {
                        if (sel != null && sel in list.indices) {
                            val c = list[sel]
                            val sx = xOf(c.start)
                            val ex = xOf(c.end)
                            val grab = handleTouchPx
                            val nearStart = abs(down.position.x - sx) <= grab
                            val nearEnd = abs(down.position.x - ex) <= grab
                            // The side the finger is on decides which handle wins when cards touch.
                            when {
                                nearStart && nearEnd -> {
                                    target = if (down.position.x < (sx + ex) / 2) DragMode.TRIM_START else DragMode.TRIM_END
                                    targetIndex = sel
                                }
                                nearStart -> { target = DragMode.TRIM_START; targetIndex = sel }
                                nearEnd -> { target = DragMode.TRIM_END; targetIndex = sel }
                                down.position.x in sx..ex -> { target = DragMode.MOVE; targetIndex = sel }
                            }
                        }
                        if (targetIndex == null) {
                            targetIndex = list.indexOfFirst { down.position.x in xOf(it.start)..xOf(it.end) }.takeIf { it >= 0 }
                        }
                    }

                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    var totalDx = 0f
                    var totalDy = 0f
                    var moved = false
                    var pinching = false
                    var lastSpan = 0f
                    val slop = viewConfiguration.touchSlop
                    var snappedLast = false

                    // Snap points: the playhead and every other card's edges.
                    fun snap(time: Double, ignore: Int?): Double {
                        val threshold = (snapPx / pps).toDouble()
                        var best = time
                        var bestDistance = threshold
                        val points = buildList {
                            add(position)
                            add(0.0)
                            add(currentDuration)
                            list.forEachIndexed { i, c -> if (i != ignore) { add(c.start); add(c.end) } }
                        }
                        for (p in points) {
                            val d = abs(p - time)
                            if (d < bestDistance) { bestDistance = d; best = p }
                        }
                        val snapped = best != time
                        if (snapped && !snappedLast) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        snappedLast = snapped
                        return best
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            // Pinch to zoom around the playhead.
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val span = hypot(a.x - b.x, a.y - b.y)
                            if (!pinching) { pinching = true; lastSpan = span; drag = null }
                            else if (lastSpan > 0f && span > 0f) {
                                currentOnZoom((currentPps * span / lastSpan).coerceIn(MIN_PPS, MAX_PPS))
                                lastSpan = span
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pinching) {
                            // One finger left after a pinch: ignore the rest of this gesture.
                            if (event.changes.none { it.pressed }) break
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (change.changedToUp()) {
                            val d = drag
                            when {
                                d != null -> {
                                    commitDrag(d, list, currentOnCommit)
                                    drag = null
                                }
                                !moved -> currentOnSelect(targetIndex)
                                target == DragMode.PAN -> {
                                    currentOnScrub(position - totalDx / pps, true)
                                    val velocity = tracker.calculateVelocity().x
                                    if (abs(velocity) > 200f) {
                                        fling = scope.launch {
                                            var last = 0f
                                            Animatable(0f).animateDecay(velocity, exponentialDecay(frictionMultiplier = 1.4f)) {
                                                val step = value - last
                                                last = value
                                                val now = currentPosition()
                                                currentOnScrub((now - step / currentPps).coerceIn(0.0, currentDuration), false)
                                            }
                                            currentOnScrub(currentPosition(), true)
                                        }
                                    }
                                }
                            }
                            break
                        }

                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (!moved && hypot(totalDx, totalDy) > slop) {
                            moved = true
                            // Only the selected caption can be edited by dragging; a drag that starts on
                            // any other caption scrubs instead, so a thumb resting on one never edits it.
                            if (target != DragMode.PAN && targetIndex != null) {
                                val c = list[targetIndex]
                                drag = Drag(targetIndex, target, c.start, c.end)
                                currentOnSelect(targetIndex)
                            }
                        }

                        if (moved) {
                            change.consume()
                            val d = drag
                            if (d == null) {
                                currentOnScrub((position - totalDx / pps).coerceIn(0.0, currentDuration), false)
                            } else {
                                val dt = totalDx / pps.toDouble()
                                val minLength = CaptionOps.MIN_CARD_DURATION
                                when (d.mode) {
                                    DragMode.MOVE -> {
                                        val span = d.originalEnd - d.originalStart
                                        var s = d.originalStart + dt
                                        // Snap whichever edge is closer to something.
                                        val sSnap = snap(s, d.index)
                                        val eSnap = snap(s + span, d.index) - span
                                        s = if (abs(sSnap - s) <= abs(eSnap - s)) sSnap else eSnap
                                        s = s.coerceIn(0.0, max(currentDuration - span, 0.0))
                                        d.start = s
                                        d.end = s + span
                                    }
                                    DragMode.TRIM_START -> {
                                        d.start = snap(d.originalStart + dt, d.index).coerceIn(0.0, d.originalEnd - minLength)
                                    }
                                    DragMode.TRIM_END -> {
                                        d.end = snap(d.originalEnd + dt, d.index)
                                            .coerceIn(d.originalStart + minLength, max(currentDuration, d.originalStart + minLength))
                                    }
                                    DragMode.PAN -> Unit
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val position = positionSeconds()
        val pps = pixelsPerSecond
        val centerX = size.width / 2
        fun xOf(t: Double) = centerX + ((t - position) * pps).toFloat()

        val liveDrag = drag
        val trackTop = trackTopPx

        drawRect(Surface1, Offset.Zero, Size(size.width, rulerPx))
        drawRuler(position, pps, rulerPx, centerX)
        drawWaveform(peaks, position, pps, rulerPx, wavePx, centerX)

        // Caption cards.
        clipRect(0f, trackTop - 4f, size.width, trackTop + cardPx + 4f) {
            cards.forEachIndexed { i, card ->
                val isDragged = liveDrag?.index == i
                val start = if (isDragged) liveDrag!!.start else card.start
                val end = if (isDragged) liveDrag!!.end else card.end
                val left = xOf(start)
                val right = xOf(end)
                if (right < -40f || left > size.width + 40f) return@forEachIndexed

                val isSelected = i == selected
                val width = max(right - left, 3f)
                drawRoundRect(
                    color = if (isSelected) CaptionBlueSelected else CaptionBlue,
                    topLeft = Offset(left, trackTop),
                    size = Size(width, cardPx),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                if (width > 36.dp.toPx()) {
                    clipRect(left + 6.dp.toPx(), trackTop, left + width - 6.dp.toPx(), trackTop + cardPx) {
                        drawIntoCanvas { c ->
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                textSize = 13.dp.toPx()
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            c.nativeCanvas.drawText(card.text, left + 10.dp.toPx(), trackTop + cardPx / 2 + paint.textSize / 3, paint)
                        }
                    }
                }
                if (isSelected) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(left, trackTop),
                        size = Size(width, cardPx),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(2.dp.toPx()),
                    )
                    val handleW = HandleVisual.toPx()
                    for (hx in listOf(left - handleW / 2, right - handleW / 2)) {
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(hx, trackTop + 8.dp.toPx()),
                            size = Size(handleW, cardPx - 16.dp.toPx()),
                            cornerRadius = CornerRadius(handleW / 2),
                        )
                        drawRect(
                            color = Color(0xFF3A404B),
                            topLeft = Offset(hx + handleW / 2 - 1f, trackTop + 20.dp.toPx()),
                            size = Size(2f, cardPx - 40.dp.toPx()),
                        )
                    }
                }
            }
        }

        // Fixed playhead.
        drawLine(Color.White, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 2.dp.toPx())
        val tri = Path().apply {
            moveTo(centerX - 7.dp.toPx(), 0f)
            lineTo(centerX + 7.dp.toPx(), 0f)
            lineTo(centerX, 9.dp.toPx())
            close()
        }
        drawPath(tri, Color.White)
    }
}

private fun commitDrag(
    drag: Drag,
    original: List<CaptionCard>,
    onCommit: (List<CaptionCard>, Int) -> Unit,
) {
    if (abs(drag.start - drag.originalStart) < 1e-4 && abs(drag.end - drag.originalEnd) < 1e-4) return
    val edited = original.toMutableList()
    edited[drag.index] = original[drag.index].copy(start = drag.start, end = drag.end)
    val result = CaptionOps.applyOverwrite(edited, drag.index)
    onCommit(result.cards, result.selected ?: drag.index)
}

private fun DrawScope.drawRuler(position: Double, pps: Float, height: Float, centerX: Float) {
    val steps = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
    val step = steps.firstOrNull { it * pps >= 72f } ?: steps.last()

    val firstTime = position - centerX / pps
    val lastTime = position + (size.width - centerX) / pps
    var t = kotlin.math.floor(firstTime / step) * step

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(154, 163, 176)
        textSize = 11.dp.toPx()
    }
    val native = drawContext.canvas.nativeCanvas
    while (t <= lastTime + step) {
        val x = centerX + ((t - position) * pps).toFloat()
        drawLine(Color(0xFF5B6270), Offset(x, height - 9.dp.toPx()), Offset(x, height), 1.dp.toPx())
        if (t >= -1e-9) {
            val label = if (step < 1.0) com.dpx.captions.ui.formatTime(t, tenths = true) else com.dpx.captions.ui.formatTime(t)
            native.drawText(label, x + 4.dp.toPx(), height - 12.dp.toPx(), paint)
        }
        // Light subdivisions between the labelled ticks.
        val sub = step / 5
        for (k in 1..4) {
            val sx = x + (sub * k * pps).toFloat()
            drawLine(Color(0xFF3A404B), Offset(sx, height - 5.dp.toPx()), Offset(sx, height), 1.dp.toPx())
        }
        t += step
    }
}

private fun DrawScope.drawWaveform(
    peaks: FloatArray?,
    position: Double,
    pps: Float,
    top: Float,
    height: Float,
    centerX: Float,
) {
    val mid = top + height / 2
    drawLine(Color(0xFF1F242B), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
    if (peaks == null || peaks.isEmpty()) return

    val perSecond = Waveform.BUCKETS_PER_SECOND
    val half = height / 2 - 3.dp.toPx()
    val columnWidth = 2.dp.toPx()
    var x = 0f
    while (x < size.width) {
        val t0 = position + (x - centerX) / pps
        val t1 = position + (x + columnWidth - centerX) / pps
        if (t1 >= 0) {
            val i0 = max((t0 * perSecond).toInt(), 0)
            val i1 = max((t1 * perSecond).toInt(), i0 + 1)
            if (i0 < peaks.size) {
                var peak = 0f
                for (i in i0 until min(i1, peaks.size)) if (peaks[i] > peak) peak = peaks[i]
                val h = max(peak * half, 1f)
                drawLine(WaveformColor, Offset(x, mid - h), Offset(x, mid + h), columnWidth - 0.5f)
            }
        }
        x += columnWidth
    }
}
