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

    /**
     * [progress] is null while the amount of work left isn't known. [detail] says how long is left and
     * when it should be done, or just the time elapsed when that can't be estimated yet.
     */
    data class Running(
        val kind: JobKind,
        val projectId: String,
        val stage: String,
        val progress: Float?,
        val detail: String? = null,
    ) : JobState

    /** [message] reports what was learned or applied (corrections), when there is anything to say. */
    data class Finished(
        val kind: JobKind,
        val projectId: String,
        val outputPath: String? = null,
        val message: String? = null,
    ) : JobState

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

    // For the time-left estimate: when the job and its current stage began.
    private var jobStartedAt = 0L
    private var stageStartedAt = 0L
    private var currentStage = ""

    private fun start(kind: JobKind, projectId: String, block: suspend () -> JobState) {
        if (isRunning) return
        jobStartedAt = System.currentTimeMillis()
        stageStartedAt = jobStartedAt
        currentStage = ""
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
            val now = System.currentTimeMillis()
            if (stage != currentStage) {
                currentStage = stage
                stageStartedAt = now
            }
            val detail = timeLeft(fraction, now)
            flow.value = current.copy(kind = kind, projectId = projectId, stage = stage, progress = fraction, detail = detail)
            val label = if (kind == JobKind.EXPORT && stage == "Exporting") "Exporting video" else stage
            container.work.update(WorkStatus(if (fraction != null) "$label · $detail" else label, fraction))
        }
    }

    /** "about 1:20 left · done around 14:32", once enough of the stage has run to extrapolate. */
    private fun timeLeft(fraction: Float?, now: Long): String {
        val elapsed = (now - jobStartedAt) / 1000.0
        val stageElapsed = (now - stageStartedAt) / 1000.0
        if (fraction == null || fraction < 0.02f || stageElapsed < 2) return "${clock(elapsed)} elapsed"
        val left = stageElapsed * (1 - fraction) / fraction
        val doneAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(now + (left * 1000).toLong()))
        return "about ${clock(left)} left · done around $doneAt"
    }

    private fun clock(seconds: Double): String {
        val s = maxOf(0, Math.round(seconds).toInt())
        return "%d:%02d".format(s / 60, s % 60)
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

            progress(JobKind.GENERATE, projectId, "Applying learned corrections", null)
            container.projects.saveWords(projectId, words)
            val (cards, applied) = buildWithCorrections(projectId, words, formatting)
            container.projects.save(project.copy(cards = cards, formatting = formatting, modelSize = modelId))

            JobState.Finished(
                JobKind.GENERATE, projectId,
                message = if (applied > 0) "Applied $applied learned correction(s)" else null,
            )
        }
    }

    /**
     * Rewrites Whisper's words with the learned corrections, groups them into cards, and keeps those
     * cards as the baseline the next export learns from. Also used when regrouping without transcribing.
     */
    fun buildWithCorrections(projectId: String, words: List<com.dpx.captions.model.Word>, formatting: CaptionFormatting):
        Pair<List<com.dpx.captions.model.CaptionCard>, Int> {
        val corrected = runCatching { Corrections.apply(words, container.corrections()) }
            .getOrElse { Corrections.Applied(words, emptyList()) }
        val cards = CaptionBuilder.build(corrected.words, formatting)
        runCatching { container.projects.saveBaseline(projectId, Corrections.baselineFromCards(cards)) }
        return cards to corrected.rewrites.size
    }

    fun export(projectId: String, options: ExportOptions) {
        start(JobKind.EXPORT, projectId) {
            val project = container.projects.load(projectId) ?: error("Project not found")
            val output = container.exporter.export(project, options) { stage, fraction ->
                progress(JobKind.EXPORT, projectId, stage, fraction)
            }
            JobState.Finished(JobKind.EXPORT, projectId, output.absolutePath, learnFromExport(project))
        }
    }

    /**
     * Records the user's edits as learned corrections, as the desktop app does after an export. A
     * failure here must never hide a good export, so it only ever costs the summary line.
     */
    private fun learnFromExport(project: ProjectFile): String? = runCatching {
        val baseline = container.projects.loadBaseline(project.id) ?: return null
        val store = container.corrections()
        val seen = container.projects.loadLearned(project.id)
        val report = Corrections.learn(
            baseline, project.cards, store,
            caseMeaningful = project.formatting.textCase == com.dpx.captions.model.TextCase.NORMAL,
            seen = seen,
        )
        if (report.changed) store.save()
        container.projects.saveLearned(project.id, seen)
        report.summary()
    }.getOrElse { "Could not save learned corrections: ${it.message}" }

    private suspend fun currentJob(): Job? = kotlin.coroutines.coroutineContext[Job]

    private companion object {
        const val WORK_ID = "job"
    }
}
