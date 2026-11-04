package com.dpx.captions.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.dpx.captions.LocalContainer
import com.dpx.captions.core.CorrectionStore
import java.io.File

/**
 * The corrections learned from exports, with a way to delete a wrong one and to bring in what the
 * desktop app has learned (its corrections.json has the same format).
 */
@Composable
fun LearnedCorrections() {
    val container = LocalContainer.current
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val store = remember(version) { container.corrections() }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val temp = File.createTempFile("corrections", ".json", context.cacheDir)
            context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
            val incoming = CorrectionStore(temp)
            temp.delete()
            require(incoming.rules.isNotEmpty()) { "no rules" }
            val mine = container.corrections()
            var added = 0
            for ((key, targets) in incoming.rules) {
                if (key !in mine.rules) added++
                for ((target, votes) in targets) repeat(votes) { mine.learn(key, target, context = incoming.isContext(key)) }
            }
            mine.save()
            version++
            "Imported ${incoming.rules.size} correction(s), $added new."
        }.getOrElse { "That file isn't a DPX Captions corrections.json." }
    }

    Text(
        "Learned from your edits each time you export, and applied automatically the next time you generate captions. " +
            "\"Everywhere\" rules are spelling fixes; \"in this phrase\" rules only apply next to the same words.",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurfaceDim,
    )

    val keys = store.rules.keys.filter { key ->
        filter.isBlank() || key.contains(filter.trim(), ignoreCase = true) || (store.best(key) ?: "").contains(filter.trim(), ignoreCase = true)
    }.sorted()

    if (store.rules.size > 8) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("corrections_filter"),
        )
    }
    if (store.rules.isEmpty()) {
        Text("None yet. Fix a few captions and export.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    }
    for (key in keys) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$key → ${store.best(key)}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    (if (store.isContext(key)) "in this phrase" else "everywhere") + " · ${store.votes(key)}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }
            IconButton(onClick = {
                val fresh = container.corrections()
                fresh.remove(key)
                fresh.save()
                version++
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete correction $key", tint = OnSurfaceDim)
            }
        }
    }
    OutlinedButton(
        onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import corrections.json from the desktop app") }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Accent) }
}
