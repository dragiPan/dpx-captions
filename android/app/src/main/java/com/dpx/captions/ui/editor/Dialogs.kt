package com.dpx.captions.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dpx.captions.ui.Danger
import com.dpx.captions.ui.OnSurfaceDim
import com.dpx.captions.ui.SwitchRow

@Composable
fun EditTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit caption") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("edit_text"),
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
                )
                Text(
                    "Leave it empty to delete the caption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.text) }, modifier = Modifier.testTag("edit_save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FindReplaceDialog(
    onReplace: (find: String, replacement: String, matchCase: Boolean, wholeWord: Boolean) -> Int,
    onDismiss: () -> Unit,
) {
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find and replace") },
        text = {
            Column {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it; result = null },
                    label = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("find"),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it; result = null },
                    label = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("replace"),
                )
                SwitchRow("Match case", matchCase) { matchCase = it }
                SwitchRow("Whole word only", wholeWord) { wholeWord = it }
                result?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = com.dpx.captions.ui.Accent)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = find.isNotEmpty(),
                onClick = {
                    val count = onReplace(find, replacement, matchCase, wholeWord)
                    result = if (count == 0) "No matches." else "Replaced $count word${if (count == 1) "" else "s"}."
                },
                modifier = Modifier.testTag("replace_all"),
            ) { Text("Replace all") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
