package com.dpx.captions.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dpx.captions.audio.AudioDecoder
import com.dpx.captions.audio.Waveform
import com.dpx.captions.core.AppContainer
import com.dpx.captions.core.CaptionBuilder
import com.dpx.captions.core.CaptionOps
import com.dpx.captions.core.History
import com.dpx.captions.core.ProjectFile
import com.dpx.captions.core.Snapshot
import com.dpx.captions.model.AnimationStyle
import com.dpx.captions.model.Aspect
import com.dpx.captions.model.CaptionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the editor edits: captions, style and aspect, with undo/redo and autosave. A plain class
 * created per visit to the screen (not a ViewModel) so it always starts from what is on disk - after
 * regenerating captions in setup there is no stale instance to reconcile.
 */
class EditorState(
    private val container: AppContainer,
    initial: ProjectFile,
    private val scope: CoroutineScope,
) {
    var project by mutableStateOf(initial)
        private set
    var selected by mutableStateOf<Int?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var peaks by mutableStateOf<FloatArray?>(null)
        private set

    val cards: List<CaptionCard> get() = project.cards
    val style: AnimationStyle get() = project.style

    /** Raw transcript words are kept, so captions can be regrouped without transcribing again. */
    val canRegroup: Boolean get() = container.projects.loadWords(project.id) != null

    private val history = History().also { it.reset(Snapshot(initial.cards, initial.style, initial.aspect)) }
    private var saveJob: Job? = null

    /**
     * Run from a LaunchedEffect, not from this class's constructor: the state objects above are created
     * during composition, and a write from another thread before that composition is applied is
     * silently missed, so the waveform would never appear.
     */
    suspend fun loadPeaks() {
        val computed = withContext(Dispatchers.IO) {
            val cache = container.projects.audioCache(project.id)
            val samples = AudioDecoder.readCache(cache) ?: runCatching {
                AudioDecoder.decode(project.videoPath).also { AudioDecoder.writeCache(cache, it) }
            }.getOrNull()
            samples?.let(Waveform::peaks)
        }
        peaks = computed
    }

    private fun snapshot() = Snapshot(project.cards, project.style, project.aspect)

    private fun refreshFlags() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            withContext(Dispatchers.IO) { container.projects.save(project) }
        }
    }

    /** Writes immediately; used when leaving the screen, where a pending debounce would be lost. */
    fun saveNow() {
        saveJob?.cancel()
        val snapshot = project
        scope.launch(NonCancellable + Dispatchers.IO) { container.projects.save(snapshot) }
    }

    // --- selection ---------------------------------------------------------------------------------

    fun select(index: Int?) {
        selected = index?.takeIf { it in cards.indices }
    }

    fun indexAt(seconds: Double): Int? = cards.indexOfFirst { seconds >= it.start && seconds <= it.end }.takeIf { it >= 0 }

    // --- committing edits --------------------------------------------------------------------------

    private fun commit(cards: List<CaptionCard>, select: Int?) {
        project = project.copy(cards = cards)
        selected = select?.takeIf { it in cards.indices }
        history.record(snapshot())
        refreshFlags()
        scheduleSave()
    }

    fun commitCards(cards: List<CaptionCard>, select: Int?) = commit(cards, select)

    /** Live style changes (a slider mid-drag) show immediately but don't each become an undo step. */
    fun previewStyle(style: AnimationStyle) {
        project = project.copy(style = style)
        scheduleSave()
    }

    fun commitStyle() {
        history.record(snapshot())
        refreshFlags()
        scheduleSave()
        // The look you last used is what the next project starts with.
        container.settings.update { it.copy(style = project.style, aspect = project.aspect) }
    }

    fun setStyle(style: AnimationStyle) {
        previewStyle(style)
        commitStyle()
    }

    fun setAspect(aspect: Aspect) {
        if (aspect == project.aspect) return
        project = project.copy(aspect = aspect)
        history.record(snapshot())
        refreshFlags()
        scheduleSave()
        container.settings.update { it.copy(aspect = aspect) }
    }

    // --- caption operations ------------------------------------------------------------------------

    fun editText(index: Int, text: String) {
        val card = cards.getOrNull(index) ?: return
        val rewritten = CaptionOps.rewriteText(card, text, project.formatting)
        if (rewritten == null) {
            deleteAt(index)
            return
        }
        commit(cards.toMutableList().also { it[index] = rewritten }, index)
    }

    fun splitAt(seconds: Double): Boolean {
        val result = CaptionOps.splitAt(cards, seconds) ?: return false
        commit(result.cards, result.selected)
        return true
    }

    fun deleteAt(index: Int) {
        if (index !in cards.indices) return
        commit(CaptionOps.delete(cards, index), null)
    }

    fun duplicate(index: Int): Boolean {
        val result = CaptionOps.duplicate(cards, index, project.durationSeconds) ?: return false
        commit(result.cards, result.selected)
        return true
    }

    fun mergeWithNext(index: Int): Boolean {
        val result = CaptionOps.mergeWithNext(cards, index) ?: return false
        commit(result.cards, result.selected)
        return true
    }

    /** Returns the index of the new caption so the UI can open it for typing. */
    fun addAt(seconds: Double): Int? {
        val result = CaptionOps.addAt(cards, seconds, project.durationSeconds) ?: return null
        commit(result.cards, result.selected)
        return result.selected
    }

    fun findReplace(find: String, replacement: String, matchCase: Boolean, wholeWord: Boolean): Int {
        val (updated, count) = CaptionOps.findReplace(cards, find, replacement, matchCase, wholeWord)
        if (count > 0) commit(updated, selected)
        return count
    }

    fun regroup(): Boolean {
        val words = container.projects.loadWords(project.id) ?: return false
        commit(CaptionBuilder.build(words, project.formatting), null)
        return true
    }

    // --- history -----------------------------------------------------------------------------------

    private fun restore(snapshot: Snapshot) {
        project = project.copy(cards = snapshot.cards, style = snapshot.style, aspect = snapshot.aspect)
        selected = selected?.takeIf { it in snapshot.cards.indices }
        refreshFlags()
        scheduleSave()
    }

    fun undo() = history.undo()?.let(::restore)

    fun redo() = history.redo()?.let(::restore)
}
