package com.dpx.captions.core

import android.app.Application
import com.dpx.captions.render.CaptionPainter
import com.dpx.captions.render.FontRegistry
import com.dpx.captions.whisper.ModelStore
import com.dpx.captions.whisper.Transcriber

/** Hand-rolled service locator; the app is small enough that a DI framework would only add weight. */
class AppContainer(val app: Application) {
    val work = WorkTracker(app)
    val settings = SettingsStore(app)
    val projects = ProjectStore(app)
    val models = ModelStore(app)
    val downloads = ModelDownloads(models, work)
    val fonts = FontRegistry(app)
    val transcriber = Transcriber(models)
    val exporter = Exporter(app, this)
    val jobs = JobRunner(this)

    /** A Paint-holding painter isn't thread safe, so the preview and the export each get their own. */
    fun newPainter() = CaptionPainter(fonts)

    fun cancelAllWork() {
        jobs.cancel()
        downloads.cancelAll()
    }
}
