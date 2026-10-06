package com.dpx.captions.core

import com.dpx.captions.audio.AudioDecoder
import com.dpx.captions.model.CaptionFormatting
import com.dpx.captions.whisper.TranscriptionException
import com.dpx.captions.whisper.WhisperCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class JobKind { GENERATE, EXPORT }

sealed interface JobState {
    data object Idle : JobState

    /** [progress] is null while the amount of work left isn't known. */
    data class Running(
        val kind: JobKind,
        val projectId: String,
        val stage: String,
        val progress: Float?,
    ) : JobState

    data class Finished(val kind: JobKind, val projectId: String, val outputPath: String? = null) : JobState

    data class Failed(val kind: JobKind, val projectId: String, val message: String) : JobState
}

/**
 * Runs the long jobs - transcription and export - on an app-wide scope, so they carry on when the user
 * leaves the screen. Only one runs at a time: both are heavy enough that two would just fight.
 */
class JobRunner(private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val flow = MutableStateFlow<JobState>(JobState.Idle)

    val state: StateFlow<JobState> = flow

    val isRunning: Boolean get() = flow.value is JobState.Running

    fun acknowledge() {
        if (flow.value !is JobState.Running) flow.value = JobState.Idle
    }

    fun cancel() {
        job?.cancel()
        flow.value = JobState.Idle
    }

    private fun start(kind: JobKind, projectId: String, block: suspend () -> JobState) {
        if (isRunning) return
        flow.value = JobState.Running(kind, projectId, "Starting", null)
        container.work.begin(WORK_ID)
        job = scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                JobState.Idle
            } catch (e: TranscriptionException) {
                JobState.Failed(kind, projectId, e.message ?: "Transcription failed")
            } catch (e: AudioDecoder.NoAudioTrackException) {
                JobState.Failed(kind, projectId, "This video has no audio, so there is nothing to transcribe.")
            } catch (e: OutOfMemoryError) {
                JobState.Failed(kind, projectId, "The phone ran out of memory. Try a smaller model.")
            } catch (e: Exception) {
                JobState.Failed(kind, projectId, e.message ?: e.javaClass.simpleName)
            }
            flow.value = result
            container.work.end(WORK_ID)
        }
    }

    private fun progress(kind: JobKind, projectId: String, stage: String, fraction: Float?) {
        val current = flow.value
        if (current is JobState.Running) {
            flow.value = current.copy(kind = kind, projectId = projectId, stage = stage, progress = fraction)
            val label = if (kind == JobKind.EXPORT && stage == "Exporting") "Exporting video" else stage
            container.work.update(WorkStatus(label, fraction))
        }
    }

    fun generate(projectId: String, modelId: String, formatting: CaptionFormatting) {
        start(JobKind.GENERATE, projectId) {
            val project = container.projects.load(projectId) ?: error("Project not found")
            val model = WhisperCatalog.byId(modelId)
            val settings = container.settings.state.value

            val cache = container.projects.audioCache(projectId)
            val samples = withContext(Dispatchers.IO) {
                AudioDecoder.readCache(cache) ?: run {
                    val self = currentJob()
                    val decoded = AudioDecoder.decode(
                        project.videoPath,
                        onProgress = { progress(JobKind.GENERATE, projectId, "Reading audio", it) },
                        isCancelled = { self?.isActive == false },
                    )
                    AudioDecoder.writeCache(cache, decoded)
                    decoded
                }
            }

            progress(JobKind.GENERATE, projectId, "Loading ${model.label}", null)
            val threads = if (settings.threads > 0) settings.threads
            else (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

            val words = container.transcriber.transcribe(
                samples, model, formatting.vocabularyContext, threads, settings.beamSize,
            ) { percent -> progress(JobKind.GENERATE, projectId, "Transcribing", percent / 100f) }

            progress(JobKind.GENERATE, projectId, "Building captions", null)
            container.projects.saveWords(projectId, words)
            val cards = CaptionBuilder.build(words, formatting)
            container.projects.save(project.copy(cards = cards, formatting = formatting, modelSize = modelId))

            JobState.Finished(JobKind.GENERATE, projectId)
        }
    }

    fun export(projectId: String, options: ExportOptions) {
        start(JobKind.EXPORT, projectId) {
            val project = container.projects.load(projectId) ?: error("Project not found")
            val output = container.exporter.export(project, options) { stage, fraction ->
                progress(JobKind.EXPORT, projectId, stage, fraction)
            }
            JobState.Finished(JobKind.EXPORT, projectId, output.absolutePath)
        }
    }

    private suspend fun currentJob(): Job? = kotlin.coroutines.coroutineContext[Job]

    private companion object {
        const val WORK_ID = "job"
    }
}
