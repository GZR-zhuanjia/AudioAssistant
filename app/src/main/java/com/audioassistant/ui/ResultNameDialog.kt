package com.audioassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.audioassistant.domain.AudioNames

@Composable
internal fun ResultNameDialog(
    key: String,
    title: String,
    initialName: String,
    error: String?,
    busy: Boolean,
    confirmLabel: String,
    dismissLabel: String,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var name by rememberSaveable(key) { mutableStateOf(AudioNames.suggested(initialName)) }
    var validationError by rememberSaveable(key) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("起一个容易找到的名字，接下来选择保存文件夹。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = name, onValueChange = { name = it; validationError = null }, modifier = Modifier.fillMaxWidth(), label = { Text("成果名称") }, enabled = !busy, singleLine = true, isError = validationError != null || error != null)
            (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            try { confirm(AudioNames.validate(name)) }
            catch (e: IllegalArgumentException) { validationError = e.message ?: "请输入有效名称" }
        }, enabled = !busy) { Text(if (busy) "正在保存…" else confirmLabel) }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(dismissLabel) } })
}
