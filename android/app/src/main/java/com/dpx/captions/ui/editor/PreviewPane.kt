package com.dpx.captions.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dpx.captions.LocalContainer
import kotlin.math.hypot
import kotlin.math.min

// Fractions of the frame that TikTok / Reels / Shorts overlay with their own interface.
private const val TOP_UNSAFE = 0.07f
private const val BOTTOM_UNSAFE = 0.18f
private const val RIGHT_UNSAFE = 0.12f

/**
 * The video with the live captions on top, framed in the chosen output aspect ratio. Tapping plays or
 * pauses; dragging a visible caption moves it.
 */
@Composable
fun PreviewPane(
    controller: PlayerController,
    state: EditorState,
    guides: Boolean,
    onMoveCaption: (x: Double, y: Double) -> Unit,
    onMoveCaptionEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalContainer.current
    val painter = remember { container.newPainter() }
    val project = state.project
    val (canvasW, canvasH) = remember(project.aspect, project.width, project.height) {
        project.aspect.canvasSize(project.width, project.height)
    }

    // A slightly lighter surround than the black frame, so the edges of a 16:9 or 1:1 canvas are visible.
    BoxWithConstraints(modifier.background(Color(0xFF16191F)), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val fit = min(constraints.maxWidth.toFloat() / canvasW, constraints.maxHeight.toFloat() / canvasH)
        val widthDp = with(density) { (canvasW * fit).toDp() }
        val heightDp = with(density) { (canvasH * fit).toDp() }

        Box(Modifier.size(widthDp, heightDp).background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        player = controller.player
                    }
                },
                update = { it.player = controller.player },
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(canvasW, canvasH) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val toCanvasX = canvasW / size.width.toFloat()
                            val toCanvasY = canvasH / size.height.toFloat()

                            val bounds = painter.bounds(canvasW, canvasH, state.cards, state.style, controller.positionSeconds)
                            val onCaption = bounds?.contains(down.position.x * toCanvasX, down.position.y * toCanvasY) == true

                            val startX = state.style.positionX
                            val startY = state.style.positionY
                            var dx = 0f
                            var dy = 0f
                            var dragging = false
                            var moved = false
                            val slop = viewConfiguration.touchSlop

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (change.changedToUp()) {
                                    when {
                                        dragging -> onMoveCaptionEnd()
                                        !moved -> controller.togglePlay()
                                    }
                                    break
                                }

                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                if (hypot(dx, dy) > slop) {
                                    moved = true
                                    if (onCaption) dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    onMoveCaption(
                                        (startX + dx * toCanvasX / canvasW).coerceIn(0.0, 1.0),
                                        (startY - dy * toCanvasY / canvasH).coerceIn(0.0, 1.0),
                                    )
                                }
                            }
                        }
                    },
            ) {
                // Both reads below are state, so the overlay redraws on every playback frame and edit
                // without the surrounding layout being recomposed.
                val t = controller.positionSeconds
                val cards = state.cards
                val style = state.style

                drawIntoCanvasNative { canvas ->
                    canvas.save()
                    canvas.scale(size.width / canvasW, size.height / canvasH)
                    painter.draw(canvas, canvasW, canvasH, cards, style, t)
                    canvas.restore()
                }

                if (guides) drawGuides()
            }
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvasNative(
    block: (android.graphics.Canvas) -> Unit,
) = drawIntoCanvas { block(it.nativeCanvas) }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGuides() {
    val shade = Color(0x37DC3C3C)
    val w = size.width
    val h = size.height
    drawRect(shade, Offset.Zero, Size(w, h * TOP_UNSAFE))
    drawRect(shade, Offset(0f, h * (1 - BOTTOM_UNSAFE)), Size(w, h * BOTTOM_UNSAFE))
    drawRect(shade, Offset(w * (1 - RIGHT_UNSAFE), 0f), Size(w * RIGHT_UNSAFE, h))
    drawRect(
        color = Color(0xCCFFD250),
        topLeft = Offset(0f, h * TOP_UNSAFE),
        size = Size(w * (1 - RIGHT_UNSAFE), h * (1 - TOP_UNSAFE - BOTTOM_UNSAFE)),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}
