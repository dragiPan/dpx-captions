package com.dpx.captions.core

import android.app.Application
import com.dpx.captions.whisper.ModelStore
import com.dpx.captions.whisper.WhisperCatalog
import com.dpx.captions.whisper.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkStatus(val text: String, val progress: Float?)

/**
 * Counts what needs the process kept alive (a transcription, an export, a model download) and runs
 * the foreground service exactly while that count is above zero.
 */
class WorkTracker(private val app: Application) {
    private val active = mutableSetOf<String>()
    private val flow = MutableStateFlow<WorkStatus?>(null)

    val status: StateFlow<WorkStatus?> = flow

    @Synchronized
    fun begin(id: String) {
        val wasIdle = active.isEmpty()
        active += id
        if (wasIdle) JobService.start(app)
    }

    @Synchronized
    fun end(id: String) {
        active -= id
        if (active.isEmpty()) {
            flow.value = null
            JobService.stop(app)
        }
    }

    fun update(status: WorkStatus) {
        flow.value = status
    }
}

sealed interface DownloadState {
    data class Downloading(val downloaded: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class Failed(val message: String) : DownloadState
}

class ModelDownloads(private val store: ModelStore, private val work: WorkTracker) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<String, Job>()

    private val stateFlow = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val installedFlow = MutableStateFlow(scanInstalled())

    val state: StateFlow<Map<String, DownloadState>> = stateFlow

    /** Ids of models that are fully downloaded. */
    val installed: StateFlow<Set<String>> = installedFlow

    private fun scanInstalled() = WhisperCatalog.models.filter { store.isInstalled(it) }.map { it.id }.toSet()

    fun start(model: WhisperModel) {
        if (running[model.id]?.isActive == true) return

        stateFlow.update { it + (model.id to DownloadState.Downloading(store.partialBytes(model), model.sizeBytes)) }
        val workId = "download:${model.id}"
        work.begin(workId)

        running[model.id] = scope.launch {
            try {
                store.download(model) { done, total ->
                    stateFlow.update { it + (model.id to DownloadState.Downloading(done, total)) }
                    work.update(WorkStatus("Downloading ${model.label}", done.toFloat() / total))
                }
                stateFlow.update { it - model.id }
                installedFlow.value = scanInstalled()
            } catch (e: CancellationException) {
                stateFlow.update { it - model.id }
            } catch (e: Exception) {
                stateFlow.update { it + (model.id to DownloadState.Failed(e.message ?: "Download failed")) }
            } finally {
                work.end(workId)
            }
        }
    }

    /** Keeps the partial file, so tapping download again resumes instead of starting over. */
    fun cancel(model: WhisperModel) {
        running[model.id]?.cancel()
    }

    fun cancelAll() {
        running.values.forEach { it.cancel() }
    }

    fun delete(model: WhisperModel) {
        cancel(model)
        store.delete(model)
        stateFlow.update { it - model.id }
        installedFlow.value = scanInstalled()
    }
}
