@file:Suppress("UnsafeOptInUsageError")

package com.audioassistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.audioassistant.domain.AudioNames
import com.audioassistant.domain.LibraryOrigin

@Composable
internal fun AudioLibraryEntry(state: EditorState, enabled: Boolean, open: () -> Unit, retry: () -> Unit) {
    val count = state.library.count { it.origin != LibraryOrigin.AUDIO_IMPORT }
    Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("音频成果库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (state.libraryLoading) "正在读取成果…" else "$count 个成果 · 转换与拼接结果", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = open, enabled = enabled) { Text("管理 / 添加") }
            }
            if (state.libraryError != null) {
                Text("成果库读取失败：${state.libraryError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = retry, enabled = !state.libraryLoading) { Text("重试读取") }
            } else Text("转换或拼接完成后自动保留。可重命名、选择保存位置，也可直接添加到拼接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AudioLibraryDialog(state: EditorState, vm: AudioViewModel, dismiss: () -> Unit, added: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("all") }
    val results = state.library.filter { it.origin != LibraryOrigin.AUDIO_IMPORT }
    val availableIds = results.map { it.id }
    val shown = results.filter { item -> when (category) {
        "conversion" -> item.origin == LibraryOrigin.VIDEO_CONVERSION
        "concat" -> item.origin == LibraryOrigin.PROJECT_EXPORT
        "legacy" -> item.origin == LibraryOrigin.LEGACY_OUTPUT
        else -> true
    } }
    LaunchedEffect(availableIds) { selected = ArrayList(selected.filter { it in availableIds }) }
    val editable = state.busy == null && !state.libraryLoading && state.libraryError == null
    val canAdd = editable && state.ready && !state.loadFailed
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 680.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音频成果库", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = dismiss) { Text("关闭") }
                }
                Text("保存和管理转换、拼接完成的成果。勾选多个成果，可按勾选顺序添加到拼接。", style = MaterialTheme.typography.bodySmall)
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = category == "all", onClick = { category = "all" }, label = { Text("全部") })
                        FilterChip(selected = category == "conversion", onClick = { category = "conversion" }, label = { Text("转换成果") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = category == "concat", onClick = { category = "concat" }, label = { Text("拼接成果") })
                        FilterChip(selected = category == "legacy", onClick = { category = "legacy" }, label = { Text("旧版成果") })
                    }
                }
                if (state.libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.libraryError?.let { error ->
                    Text("成果库读取失败：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::refreshLibrary, enabled = !state.libraryLoading) { Text("重试读取") }
                }
                if (!state.libraryLoading && shown.isEmpty() && state.libraryError == null) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (results.isEmpty()) "成果库还是空的" else "这个分类还没有成果", fontWeight = FontWeight.SemiBold)
                        Text("先转换视频或完成一次拼接。成果会自动保留，重新打开后也能找到。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id }) { item ->
                        val order = selected.indexOf(item.id)
                        Column(Modifier.fillMaxWidth().clickable(enabled = canAdd) {
                            selected = ArrayList(selected.toMutableList().apply { if (item.id in this) remove(item.id) else add(item.id) })
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = order >= 0, onCheckedChange = { checked -> selected = ArrayList(selected.toMutableList().apply { if (checked && item.id !in this) add(item.id) else if (!checked) remove(item.id) }) }, enabled = canAdd)
                                Column(Modifier.weight(1f)) {
                                    Text(AudioNames.suggested(item.source.displayName), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("${timeText(item.source.durationUs)} · ${libraryOrigin(item.origin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(item.lastSavedFolderName?.let { "上次保存到：$it" } ?: if (item.lastSavedUri != null) "曾另存到其他位置，应用内仍保留" else "保留在应用内", style = MaterialTheme.typography.labelSmall)
                                    if (order >= 0) Text("添加顺序 ${order + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { dismiss(); vm.requestLibrarySave(item.id) }, enabled = editable) { Text("保存到…") }
                                TextButton(onClick = { vm.dismissError(); renameId = item.id }, enabled = editable) { Text("重命名") }
                                TextButton(onClick = { removeId = item.id }, enabled = editable) { Text("移除") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (state.loadFailed) Text("当前项目未恢复，暂不能添加；成果库中的音频保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 个", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { selected = arrayListOf() }, enabled = selected.isNotEmpty() && editable) { Text("清空") }
                    Button(onClick = { vm.addLibraryItems(selected.toList(), added) }, enabled = canAdd && selected.isNotEmpty()) { Text("添加到拼接") }
                }
            }
        }
    }
    renameId?.let { id ->
        val title = results.firstOrNull { it.id == id }?.source?.displayName.orEmpty()
        ResultNameDialog(key = "rename:$id", title = "重命名成果", initialName = title, error = state.error, busy = state.busy != null,
            confirmLabel = "保存名称", dismissLabel = "取消", confirm = { vm.renameLibraryResult(id, it) { renameId = null } }, dismiss = { renameId = null })
    }
    removeId?.let { id ->
        val title = AudioNames.suggested(results.firstOrNull { it.id == id }?.source?.displayName.orEmpty())
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("从成果库移除？") }, text = { Text("移除「$title」的成果库记录。音频文件不会删除，已添加到项目中的歌曲与片段保留。") }, confirmButton = {
            TextButton(onClick = { selected = ArrayList(selected.filterNot { it == id }); removeId = null; vm.removeLibraryItem(id) }) { Text("移除记录") }
        }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } })
    }
}

private fun libraryOrigin(origin: LibraryOrigin): String = when (origin) {
    LibraryOrigin.VIDEO_CONVERSION -> "转换成果"
    LibraryOrigin.PROJECT_EXPORT -> "拼接成果"
    LibraryOrigin.LEGACY_OUTPUT -> "旧版成果"
    LibraryOrigin.AUDIO_IMPORT -> "原始歌曲"
}
