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
import com.audioassistant.domain.AudioLibraryItem
import com.audioassistant.domain.LibraryOrigin
import java.util.Locale

@Composable
internal fun AudioLibraryEntry(state: EditorState, enabled: Boolean, open: () -> Unit, retry: () -> Unit) {
    Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("音频素材库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (state.libraryLoading) "正在读取素材…" else "${state.library.size} 个素材 · 转换结果与导入歌曲", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = open, enabled = enabled) { Text("浏览 / 添加") }
            }
            if (state.libraryError != null) {
                Text("素材库读取失败：${state.libraryError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = retry, enabled = !state.libraryLoading) { Text("重试读取") }
            } else Text("视频转换完成后自动存入；添加到拼接时直接使用原文件，无需再次导入或转换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AudioLibraryDialog(state: EditorState, vm: AudioViewModel, dismiss: () -> Unit, added: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val availableIds = state.library.map { it.id }
    LaunchedEffect(availableIds) { selected = ArrayList(selected.filter { it in availableIds }) }
    val editable = state.busy == null && !state.libraryLoading && state.libraryError == null
    val canAdd = editable && state.ready && !state.loadFailed
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 680.dp), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("从素材库添加", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = dismiss) { Text("关闭") }
                }
                Text("可多选，按勾选顺序追加到拼接列表。视频转换本身不会自动加入当前项目。", style = MaterialTheme.typography.bodySmall)
                if (state.libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.libraryError?.let { error ->
                    Text("素材库读取失败：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::refreshLibrary, enabled = !state.libraryLoading) { Text("重试读取") }
                }
                if (!state.libraryLoading && state.library.isEmpty() && state.libraryError == null) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("素材库还是空的", fontWeight = FontWeight.SemiBold)
                        Text("先转换一个视频为 MP3，或在多曲编辑中导入歌曲。素材会保留，重新打开后仍可直接添加。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.library, key = { it.id }) { item ->
                        val order = selected.indexOf(item.id)
                        Column(Modifier.fillMaxWidth().clickable(enabled = canAdd) {
                            selected = ArrayList(selected.toMutableList().apply { if (item.id in this) remove(item.id) else add(item.id) })
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = order >= 0, onCheckedChange = { checked -> selected = ArrayList(selected.toMutableList().apply { if (checked && item.id !in this) add(item.id) else if (!checked) remove(item.id) }) }, enabled = canAdd)
                                Column(Modifier.weight(1f)) {
                                    Text(item.source.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("${libraryFormat(item)} · ${timeText(item.source.durationUs)} · ${libraryOrigin(item.origin)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (order >= 0) Text("添加顺序 ${order + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                TextButton(onClick = { removeId = item.id }, enabled = editable) { Text("移除") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (state.loadFailed) Text("当前项目未恢复，暂不能添加；素材库中的音频记录保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 个", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { selected = arrayListOf() }, enabled = selected.isNotEmpty() && editable) { Text("清空") }
                    Button(onClick = { vm.addLibraryItems(selected.toList(), added) }, enabled = canAdd && selected.isNotEmpty()) { Text("添加到拼接") }
                }
            }
        }
    }
    removeId?.let { id ->
        val title = state.library.firstOrNull { it.id == id }?.source?.displayName.orEmpty()
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("从素材库移除？") }, text = { Text("移除「$title」的素材库记录。音频文件不会删除，已添加到项目中的歌曲与片段保留。") }, confirmButton = {
            TextButton(onClick = { selected = ArrayList(selected.filterNot { it == id }); removeId = null; vm.removeLibraryItem(id) }) { Text("移除记录") }
        }, dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } })
    }
}

private fun libraryFormat(item: AudioLibraryItem): String {
    val pathExtension = item.source.localPath.substringAfterLast('.', "")
    val extension = if (pathExtension == "media" || pathExtension.isBlank()) item.source.displayName.substringAfterLast('.', "音频") else pathExtension
    return extension.uppercase(Locale.ROOT)
}
private fun libraryOrigin(origin: LibraryOrigin): String = when (origin) {
    LibraryOrigin.VIDEO_CONVERSION -> "视频转换"
    LibraryOrigin.AUDIO_IMPORT -> "导入歌曲"
    LibraryOrigin.LEGACY_OUTPUT -> "已有音频"
}
