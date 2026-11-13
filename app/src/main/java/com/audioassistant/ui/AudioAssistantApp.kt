@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("UnsafeOptInUsageError")

package com.audioassistant.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audioassistant.data.WaveformData
import com.audioassistant.domain.SegmentEditor
import com.audioassistant.domain.SongItem
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

private val Ink = Color(0xFF12273D)
private val Accent = Color(0xFF087F8C)
private val Kept = Color(0xFF118A91)
private val Cut = Color(0xFFBCC6CE)
private val Selection = Color(0xFFECA747)

@Composable
fun AudioAssistantApp(vm: AudioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var module by rememberSaveable { mutableStateOf("edit") }
    var pendingSavePath by rememberSaveable { mutableStateOf<String?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importAudio)
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importVideos)
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val file = pendingSavePath?.let(::File); pendingSavePath = null
        if (uri != null && file != null) vm.saveOutput(file, uri)
    }
    val saveAs: (File) -> Unit = { file -> pendingSavePath = file.absolutePath; savePicker.launch(file.name) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val share: (File) -> Unit = { file ->
        try {
            val uri = vm.shareUri(file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("音频", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享音频"))
        } catch (e: Exception) { shareError = "无法分享：${e.message}" }
    }
    val playOutput: (File) -> Unit = { file ->
        try {
            val uri = vm.shareUri(file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { shareError = "无法播放导出文件，请安装支持 M4A 的播放器：${e.message}" }
    }
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { vm.pausePlayback(); vm.persistNow() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF69D3D6), secondary = Color(0xFFB6CDDD), background = Color(0xFF101B25), surface = Color(0xFF192733), onSurface = Color(0xFFE1EBF2)) else lightColorScheme(primary = Accent, secondary = Ink, surface = Color.White, background = Color(0xFFF2F5F8), onSurface = Ink)) {
        Scaffold(topBar = {
            TopAppBar(title = { Column { Text("音频助手", fontWeight = FontWeight.Bold); Text("从视频到舞曲，让每一段恰到好处", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { Text("本地处理", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp)) })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                item { Row(Modifier.widthIn(max = 900.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModuleCard("视频提取", "批量导入 · 独立音轨", module == "video", Modifier.weight(1f)) { module = "video" }
                    ModuleCard("多曲编辑", "精确裁剪 · 顺序拼接", module == "edit", Modifier.weight(1f)) { module = "edit" }
                } }
                if (!state.ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在恢复上次项目…") }
                if (state.loadFailed) item { Section {
                    Text("项目暂时无法恢复", style = MaterialTheme.typography.titleMedium)
                    Text("原项目文件已保留，自动保存与歌曲导入已暂停。修复文件后可重新读取。")
                    Button(onClick = vm::restoreProject) { Text("重新读取项目") }
                } }
                if (state.error != null || state.notice != null || shareError != null) item {
                    Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(), color = if (state.error != null || shareError != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(shareError ?: state.error ?: state.notice.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.dismissError(); shareError = null }) { Text("关闭") }
                        }
                    }
                }
                if (state.busy != null) item { Section {
                    Text(state.busy.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { state.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = vm::cancel) { Text("取消") }
                    }
                } }
                if (module == "video") {
                    item { Section {
                        Text("视频音轨提取", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("选择多个视频，逐个命名并提取为 M4A。提取结果可直接保存或分享。", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, enabled = state.busy == null) { Text("＋ 批量选择视频") }
                        if (state.videos.any { it.output == null }) Button(onClick = vm::extractAll, enabled = state.busy == null && state.videos.filter { it.output == null }.all { it.outputName.isNotBlank() }) { Text("批量提取全部待处理视频") }
                        if (state.videos.isEmpty()) EmptyHint("尚未导入视频", "支持文件选择器中的本地视频及可读取的文档来源")
                    } }
                    itemsIndexed(state.videos, key = { _, row -> row.input.id }) { index, row -> Section {
                        Text("${index + 1}. ${row.input.displayName}", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(value = row.outputName, onValueChange = { vm.videoName(row.input.id, it) }, modifier = Modifier.fillMaxWidth(), label = { Text("输出文件名") }, suffix = { Text(".m4a") }, singleLine = true, enabled = state.busy == null)
                        if (state.activeVideoId == row.input.id) {
                            LinearProgressIndicator(progress = { row.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = vm::cancel) { Text("取消当前提取") }
                        }
                        if (row.output == null) Button(onClick = { vm.extract(row.input.id) }, enabled = state.busy == null && row.outputName.isNotBlank()) { Text("提取音轨") }
                        else { Text("音轨已提取", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); OutputActions(row.output, state.busy == null, saveAs, share, vm::saveToMusic, playOutput) }
                    } }
                } else {
                    item { Section {
                        Text("我的舞曲", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.project.songs.size} 首 · 保留 ${timeText(state.project.songs.sumOf { SegmentEditor.editedDurationUs(it.segments) })}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = state.ready && !state.loadFailed && state.busy == null) { Text("＋ 添加歌曲") }
                        }
                        Text(state.saveStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.project.songs.isEmpty()) EmptyHint("从第一首歌开始", "批量选择歌曲后，调整顺序、删掉片段，再导出一段完整舞曲")
                    } }
                    itemsIndexed(state.project.songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(song, state.waveforms[song.source.id], index, state.project.songs.size, song.id == state.selectedSongId, state.busy == null, vm)
                    }
                    val selected = state.selectedSong
                    if (selected != null) {
                        item { TrimEditor(state, selected, vm) }
                        item { PlaybackPanel(state, vm) }
                    }
                    if (state.project.songs.isNotEmpty()) item { ExportPanel(state, vm, saveAs, share, playOutput) }
                }
                item { Text("文件保存在设备内，编辑保留原始音频。", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ModuleCard(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = if (selected) Ink else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = if (selected) Color(0xFFBFD7E0) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable
private fun EmptyHint(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun SongRow(song: SongItem, waveform: WaveformData?, index: Int, total: Int, selected: Boolean, enabled: Boolean, vm: AudioViewModel) {
    Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth().border(if (selected) 2.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(16.dp)).clickable(enabled = enabled) { vm.selectSong(song.id) }, shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}".padStart(2, '0'), Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(song.source.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("原长 ${timeText(song.source.durationUs)} → 保留 ${timeText(SegmentEditor.editedDurationUs(song.segments))} · ${song.segments.size} 段", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { vm.selectSong(song.id) }, enabled = enabled) { Text(if (selected) "编辑中" else "编辑") }
            }
            Waveform(song, waveform, 0, song.source.durationUs, 0, 0, null, Modifier.fillMaxWidth().height(30.dp), overview = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.moveSong(song.id, -1) }, enabled = enabled && index > 0) { Text("上移") }
                TextButton(onClick = { vm.moveSong(song.id, 1) }, enabled = enabled && index < total - 1) { Text("下移") }
                TextButton(onClick = { vm.removeSong(song.id) }, enabled = enabled) { Text("移除") }
            }
        }
    }
}

@Composable
private fun TrimEditor(state: EditorState, song: SongItem, vm: AudioViewModel) {
    val enabled = state.busy == null
    val duration = song.source.durationUs
    val waveform = state.waveforms[song.source.id]
    val viewport = (duration / state.zoom).toLong().coerceAtLeast(1)
    Section {
        Text("精确裁剪", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(song.source.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { Legend("保留", Kept); Legend("已删除", Cut); Legend("选区", Selection) }
        if (state.waveformLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在分析真实波形，较长音频需要一些时间…", style = MaterialTheme.typography.labelSmall) }
        if (state.waveformError != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("波形分析失败：${state.waveformError}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::analyzeSelected, enabled = enabled) { Text("重试") }
        }
        Text("全曲概览 · 点击定位窗口", style = MaterialTheme.typography.labelMedium)
        Waveform(song, waveform, 0, duration, state.selectionStartUs, state.selectionEndUs, state.sourceCursorUs,
            Modifier.fillMaxWidth().height(76.dp).pointerInput(song.id, state.zoom) {
                awaitEachGesture { val down = awaitFirstDown(); vm.pan((down.position.x / size.width * duration).toLong() - viewport / 2); down.consume() }
            }, overview = true, viewport = state.viewportStartUs to viewport)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("细节波形 · ${String.format(Locale.ROOT, "%.1f", state.zoom)}×", style = MaterialTheme.typography.labelMedium)
            Row { TextButton(onClick = { vm.zoom(state.zoom / 2) }, enabled = enabled && state.zoom > 1) { Text("缩小") }; TextButton(onClick = { vm.zoom(state.zoom * 2) }, enabled = enabled && state.zoom < 128) { Text("放大") }; TextButton(onClick = vm::focusSelection, enabled = enabled) { Text("聚焦选区") } }
        }
        val gestureState by rememberUpdatedState(state)
        val latestWidthDuration by rememberUpdatedState(duration)
        Waveform(song, waveform, state.viewportStartUs, viewport, state.selectionStartUs, state.selectionEndUs,
            state.sourceCursorUs,
            Modifier.fillMaxWidth().height(190.dp).pointerInput(song.id, enabled) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initial = gestureState
                    val span = (latestWidthDuration / initial.zoom).toLong().coerceAtLeast(1)
                    val at = (initial.viewportStartUs + down.position.x / size.width * span).toLong().coerceIn(0, latestWidthDuration)
                    val startX = ((initial.selectionStartUs - initial.viewportStartUs).toDouble() / span * size.width).toFloat()
                    val endX = ((initial.selectionEndUs - initial.viewportStartUs).toDouble() / span * size.width).toFloat()
                    val handle = when { abs(down.position.x - startX) < 30.dp.toPx() -> 0; abs(down.position.x - endX) < 30.dp.toPx() -> 1; else -> 2 }
                    var moved = false; var transformed = false
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }
                        if (pointers.size >= 2) {
                            transformed = true
                            val fraction = (event.calculateCentroid().x / size.width).coerceIn(0f, 1f)
                            vm.transformViewport(event.calculateZoom(), event.calculatePan().x / size.width, fraction)
                            event.changes.forEach { it.consume() }
                        } else if (pointers.size == 1 && !transformed) {
                            val pointer = pointers.first()
                            if (abs(pointer.position.x - down.position.x) > viewConfiguration.touchSlop || moved) {
                                moved = true
                                val latest = gestureState
                                val target = (latest.viewportStartUs + pointer.position.x / size.width * (latestWidthDuration / latest.zoom)).toLong().coerceIn(0, latestWidthDuration)
                                when (handle) { 0 -> vm.selection(target, initial.selectionEndUs); 1 -> vm.selection(initial.selectionStartUs, target); else -> vm.selection(at, target) }
                                pointer.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved && !transformed) { vm.selection(at, at); vm.seekSource(at) }
                }
            })
        Text("单指拖动创建选区或移动金色边界；双指缩放、平移；轻点放置分割点。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.zoom > 1) {
            val maxStart = (duration - viewport).coerceAtLeast(1)
            Slider(value = (state.viewportStartUs.toDouble() / maxStart).toFloat().coerceIn(0f, 1f), onValueChange = { vm.pan((it * maxStart).toLong()) }, enabled = enabled)
            Text("可视范围 ${timeText(state.viewportStartUs)} – ${timeText(state.viewportStartUs + viewport)}", style = MaterialTheme.typography.labelSmall)
        }
        TimeBoundary("起点", state.selectionStartUs, enabled, { vm.selection(it.coerceAtMost(state.selectionEndUs), state.selectionEndUs) }, { vm.nudge(true, it) })
        TimeBoundary("终点", state.selectionEndUs, enabled, { vm.selection(state.selectionStartUs, it.coerceAtLeast(state.selectionStartUs)) }, { vm.nudge(false, it) })
        Text("选中 ${timeText(state.selectionEndUs - state.selectionStartUs)} · 光标 ${timeText(state.sourceCursorUs)}", style = MaterialTheme.typography.labelMedium, color = Accent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.trim("delete") }, enabled = enabled && state.selectionEndUs > state.selectionStartUs, modifier = Modifier.weight(1f)) { Text("删除选区") }
            OutlinedButton(onClick = { vm.trim("keep") }, enabled = enabled && state.selectionEndUs > state.selectionStartUs, modifier = Modifier.weight(1f)) { Text("仅留选区") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.trim("restore") }, enabled = enabled && state.selectionEndUs > state.selectionStartUs, modifier = Modifier.weight(1f)) { Text("恢复选区") }
            OutlinedButton(onClick = { vm.trim("split") }, enabled = enabled && state.sourceCursorUs > 0 && state.sourceCursorUs < duration, modifier = Modifier.weight(1f)) { Text("光标分割") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::undo, enabled = enabled && state.canUndo) { Text("撤销") }
            TextButton(onClick = vm::redo, enabled = enabled && state.canRedo) { Text("重做") }
            TextButton(onClick = { vm.selection(0, duration) }, enabled = enabled) { Text("选择全曲") }
        }
        if (song.segments.isEmpty()) Text("当前歌曲没有保留片段。选中范围后点「恢复选区」，或撤销。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("保留片段 ${song.segments.size} 段 · 导出长度 ${timeText(SegmentEditor.editedDurationUs(song.segments))}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        song.segments.take(12).forEachIndexed { index, segment -> Text("${index + 1}. ${timeText(segment.startUs)} — ${timeText(segment.endUs)}", style = MaterialTheme.typography.labelSmall) }
        if (song.segments.size > 12) Text("另有 ${song.segments.size - 12} 个片段…", style = MaterialTheme.typography.labelSmall)
        var gain by remember(song.id, song.gainDb) { mutableFloatStateOf(song.gainDb.toFloat()) }
        Text("本曲音量  ${String.format(Locale.ROOT, "%+.1f", gain)} dB", style = MaterialTheme.typography.titleSmall)
        Slider(gain, { gain = it }, valueRange = -6f..6f, onValueChangeFinished = { vm.gain(gain.toDouble()) }, enabled = enabled)
    }
}

@Composable
private fun Legend(label: String, color: Color) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp))); Text(label, style = MaterialTheme.typography.labelSmall) } }

@Composable
private fun TimeBoundary(label: String, value: Long, enabled: Boolean, set: (Long) -> Unit, nudge: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(timeText(value)) }
    var invalid by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it; invalid = false }, label = { Text("$label · 分:秒.毫秒") }, modifier = Modifier.weight(1f), singleLine = true, enabled = enabled, isError = invalid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedButton(onClick = { val parsed = parseTime(text); if (parsed != null) set(parsed) else invalid = true }, enabled = enabled) { Text("应用") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(-1_000_000L to "−1秒", -100_000L to "−0.1秒", 100_000L to "+0.1秒", 1_000_000L to "+1秒").forEach { (step, title) -> TextButton(onClick = { nudge(step) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(title, style = MaterialTheme.typography.labelMedium) } }
        }
        if (invalid) Text("请输入秒数、分:秒.毫秒或时:分:秒.毫秒", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PlaybackPanel(state: EditorState, vm: AudioViewModel) = Section {
    Text("试听", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("自动均衡仅导出生效，试听仅应用手动音量。预览与最终响度可能不同。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.preview("original") }, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("原音") }
        OutlinedButton(onClick = { vm.preview("edited") }, enabled = state.busy == null && state.selectedSong?.segments?.isNotEmpty() == true, modifier = Modifier.weight(1f)) { Text("本曲编辑") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.preview("selection") }, enabled = state.busy == null && state.selectionEndUs > state.selectionStartUs, modifier = Modifier.weight(1f)) { Text("选区") }
        OutlinedButton(onClick = { vm.preview("boundary") }, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("剪口前后") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { vm.preview("start") }, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("起点 ±1秒") }
        TextButton(onClick = { vm.preview("end") }, enabled = state.busy == null, modifier = Modifier.weight(1f)) { Text("终点 ±1秒") }
    }
    if (state.preview.durationUs > 0) {
        Text("${state.preview.label} · ${timeText(state.preview.timelineUs)} / ${timeText(state.preview.durationUs)}", style = MaterialTheme.typography.labelMedium)
        var seeking by remember { mutableStateOf<Float?>(null) }
        Slider(value = seeking ?: (state.preview.timelineUs.toDouble() / state.preview.durationUs).toFloat().coerceIn(0f, 1f), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { vm.seekPlayback((it * state.preview.durationUs).toLong()) }; seeking = null }, enabled = state.busy == null)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::togglePlayback, enabled = state.busy == null) { Text(if (state.preview.playing) "暂停" else "继续播放") }
            Text("原音位置 ${timeText(state.preview.sourceUs)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExportPanel(state: EditorState, vm: AudioViewModel, saveAs: (File) -> Unit, share: (File) -> Unit, playOutput: (File) -> Unit) = Section {
    Text("拼接与导出", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    OutlinedTextField(value = state.project.title, onValueChange = vm::title, modifier = Modifier.fillMaxWidth(), label = { Text("舞曲名称") }, singleLine = true, enabled = state.busy == null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("自动音量均衡", fontWeight = FontWeight.SemiBold); Text("双遍 EBU R128 · 每曲匹配目标响度", style = MaterialTheme.typography.labelSmall) }
        Switch(checked = state.project.settings.normalize, onCheckedChange = { vm.settings(state.project.settings.copy(normalize = it)) }, enabled = state.busy == null)
    }
    if (state.project.settings.normalize) {
        Text("目标 ${state.project.settings.targetLufs} LUFS · 编码前真峰值目标 ${state.project.settings.truePeakDb} dBTP", style = MaterialTheme.typography.bodySmall)
        Text("AAC 编码可能产生峰值过冲；试听不含自动均衡与淡入淡出。", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-18.0, -16.0, -14.0).forEach { value -> FilterChip(selected = state.project.settings.targetLufs == value, onClick = { vm.settings(state.project.settings.copy(targetLufs = value)) }, label = { Text("${value.toInt()} LUFS") }, enabled = state.busy == null) }
        }
    }
    if (!state.project.settings.normalize) Text("关闭均衡后，正手动增益可能削波，请留意音量。", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("歌曲边界淡化", fontWeight = FontWeight.SemiBold); Text("每首剪辑后歌曲首尾 5 毫秒淡入淡出", style = MaterialTheme.typography.labelSmall) }
        Switch(checked = state.project.settings.fadeMs > 0, onCheckedChange = { vm.settings(state.project.settings.copy(fadeMs = if (it) 5 else 0)) }, enabled = state.busy == null)
    }
    Text("M4A · AAC ${(state.project.settings.bitRate / 1000)} kbps · ${String.format(Locale.ROOT, "%.1f", state.project.settings.sampleRate / 1000.0)} kHz", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.preview("all") }, enabled = state.busy == null && state.project.songs.any { it.segments.isNotEmpty() }, modifier = Modifier.weight(1f)) { Text("试听全部") }
        Button(onClick = vm::export, enabled = state.busy == null && state.project.title.isNotBlank() && state.project.songs.all { it.segments.isNotEmpty() }, modifier = Modifier.weight(1f)) { Text("导出舞曲") }
    }
    if (state.project.songs.any { it.segments.isEmpty() }) Text("有歌曲未保留任何片段，请恢复片段或移除该曲后导出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    state.exportOutput?.let { file -> Text("已生成 ${file.name}", style = MaterialTheme.typography.labelMedium, color = Accent); OutputActions(file, state.busy == null, saveAs, share, vm::saveToMusic, playOutput) }
}

@Composable
private fun OutputActions(file: File, enabled: Boolean, saveAs: (File) -> Unit, share: (File) -> Unit, music: (File) -> Unit, play: (File) -> Unit) {
    TextButton(onClick = { play(file) }, enabled = enabled) { Text("播放导出音频") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { saveAs(file) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("另存为") }
        OutlinedButton(onClick = { music(file) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("存音乐库") }
        Button(onClick = { share(file) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("分享") }
    }
}

@Composable
private fun Waveform(song: SongItem, waveform: WaveformData?, viewStartUs: Long, viewSpanUs: Long, selectionStartUs: Long, selectionEndUs: Long, playheadUs: Long?, modifier: Modifier, overview: Boolean = false, viewport: Pair<Long, Long>? = null) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tickPaint = remember(tickColor) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tickColor; textSize = 24f } }
    val waveformBackground = MaterialTheme.colorScheme.surfaceVariant
    val playheadColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.background(waveformBackground, RoundedCornerShape(8.dp))) {
        val bottom = if (overview) size.height else size.height - 28.dp.toPx()
        val span = viewSpanUs.coerceAtLeast(1)
        fun x(time: Long) = ((time - viewStartUs).toDouble() / span * size.width).toFloat()
        val visibleEnd = viewStartUs + span
        song.segments.forEach { s ->
            val left = x(maxOf(s.startUs, viewStartUs)).coerceIn(0f, size.width); val right = x(minOf(s.endUs, visibleEnd)).coerceIn(0f, size.width)
            if (right > left) drawRect(Kept.copy(alpha = 0.08f), Offset(left, 0f), androidx.compose.ui.geometry.Size(right - left, bottom))
        }
        val peaks = waveform?.peaksFor((size.width * song.source.durationUs.toDouble() / span).toInt().coerceIn(1, 2_000_000))
        if (peaks != null && peaks.isNotEmpty()) {
            var segmentIndex = 0
            val step = if (overview) 2 else 2
            for (pixel in 0 until size.width.toInt() step step) {
                val sourceTime = viewStartUs + (pixel.toDouble() / size.width * span).toLong()
                while (segmentIndex < song.segments.size && song.segments[segmentIndex].endUs <= sourceTime) segmentIndex++
                val retained = song.segments.getOrNull(segmentIndex)?.let { sourceTime >= it.startUs && sourceTime < it.endUs } == true
                val bin = (sourceTime.toDouble() / song.source.durationUs.coerceAtLeast(1) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                val nextTime = minOf(song.source.durationUs, viewStartUs + ((pixel + step).toDouble() / size.width * span).toLong())
                val lastBin = ceil(nextTime.toDouble() / song.source.durationUs.coerceAtLeast(1) * peaks.size).toInt().coerceIn(bin + 1, peaks.size)
                var peak = 0f
                for (sample in bin until lastBin) peak = maxOf(peak, peaks[sample])
                val amplitude = peak.coerceIn(0f, 1f) * bottom * 0.44f
                drawLine(if (retained) Kept else Cut, Offset(pixel.toFloat(), bottom / 2 - amplitude), Offset(pixel.toFloat(), bottom / 2 + amplitude), strokeWidth = 1.5.dp.toPx())
            }
        } else {
            drawLine(Cut, Offset(0f, bottom / 2), Offset(size.width, bottom / 2), 1.dp.toPx())
        }
        val left = x(selectionStartUs).coerceIn(0f, size.width); val right = x(selectionEndUs).coerceIn(0f, size.width)
        if (right > left) drawRect(Selection.copy(alpha = 0.17f), Offset(left, 0f), androidx.compose.ui.geometry.Size(right - left, bottom))
        listOf(selectionStartUs, selectionEndUs).forEach { t -> if (t in viewStartUs..visibleEnd) { val p = x(t); drawLine(Selection, Offset(p, 0f), Offset(p, bottom), 2.dp.toPx()); if (!overview) drawCircle(Selection, 5.dp.toPx(), Offset(p, 7.dp.toPx())) } }
        if (!overview) song.segments.forEach { s -> if (s.startUs in viewStartUs..visibleEnd && s.startUs > 0) drawLine(Kept.copy(alpha = 0.55f), Offset(x(s.startUs), 0f), Offset(x(s.startUs), bottom), 1.dp.toPx()) }
        playheadUs?.let { if (it in viewStartUs..visibleEnd) drawLine(playheadColor, Offset(x(it), 0f), Offset(x(it), bottom), 2.dp.toPx()) }
        viewport?.let { (start, length) ->
            val l = x(start).coerceIn(0f, size.width); val r = x(start + length).coerceIn(0f, size.width)
            drawRect(playheadColor.copy(alpha = 0.08f), Offset(l, 0f), androidx.compose.ui.geometry.Size(r - l, bottom))
            drawLine(playheadColor, Offset(l, 0f), Offset(l, bottom), 2.dp.toPx()); drawLine(playheadColor, Offset(r, 0f), Offset(r, bottom), 2.dp.toPx())
        }
        if (!overview) {
            tickPaint.textSize = 10.dp.toPx()
            val target = span / 5
            val stepUs = listOf(100_000L, 200_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 30_000_000L, 60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L, 1_800_000_000L, 3_600_000_000L).firstOrNull { it >= target } ?: 3_600_000_000L
            var tick = ceil(viewStartUs.toDouble() / stepUs).toLong() * stepUs
            while (tick <= visibleEnd) {
                val p = x(tick)
                drawLine(Cut, Offset(p, bottom), Offset(p, bottom + 5.dp.toPx()), 1.dp.toPx())
                val label = timeText(tick).let { if (stepUs >= 1_000_000) it.substringBefore('.') else it }
                val textWidth = tickPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(label, (p - textWidth / 2).coerceIn(0f, (size.width - textWidth).coerceAtLeast(0f)), size.height - 5.dp.toPx(), tickPaint)
                tick += stepUs
            }
        }
    }
}

internal fun timeText(timeUs: Long): String {
    val millis = timeUs.coerceAtLeast(0) / 1000
    val hours = millis / 3_600_000; val minutes = millis / 60_000 % 60; val seconds = millis / 1000 % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis % 1000)
    else String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis % 1000)
}
internal fun parseTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 1..3) return null
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    if (numbers.any { !it.isFinite() || it < 0 } || numbers.drop(1).any { it >= 60 }) return null
    val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
    return if (seconds <= Long.MAX_VALUE / 1_000_000.0) (seconds * 1_000_000).roundToLong() else null
}
