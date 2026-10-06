package com.dpx.captions.core

import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.Aspect
import com.dpx.captions.model.CaptionCard

/**
 * Whole-state snapshots instead of per-action deltas: one edit often touches several cards at once (an
 * overwrite can trim one, delete another and move a third), so restoring a snapshot is simpler and far
 * harder to get wrong than inverting each kind of edit. Everything in a snapshot is immutable, so
 * keeping references is safe.
 */
data class Snapshot(
    val cards: List<CaptionCard>,
    val style: AnimationStyle,
    val aspect: Aspect,
)

class History(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var current: Snapshot? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset(snapshot: Snapshot) {
        undoStack.clear()
        redoStack.clear()
        current = snapshot
    }

    /** Records a committed edit. Identical consecutive states are ignored. */
    fun record(snapshot: Snapshot) {
        val before = current
        if (before == snapshot) return
        if (before != null) {
            undoStack.addLast(before)
            if (undoStack.size > limit) undoStack.removeFirst()
        }
        redoStack.clear()
        current = snapshot
    }

    fun undo(): Snapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        current?.let { redoStack.addLast(it) }
        current = previous
        return previous
    }

    fun redo(): Snapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        current?.let { undoStack.addLast(it) }
        current = next
        return next
    }
}
