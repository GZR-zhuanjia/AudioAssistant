package com.audioassistant.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.audioassistant.data.AppRepository
import com.audioassistant.data.VideoInput
import com.audioassistant.data.WaveformData
import com.audioassistant.domain.AudioProject
import com.audioassistant.domain.ExportSettings
import com.audioassistant.domain.SegmentEditor
import com.audioassistant.domain.SongHistory
import com.audioassistant.domain.SongItem
import com.audioassistant.playback.PreviewController
import com.audioassistant.playback.PreviewPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID


data class VideoTask(val input: VideoInput, val outputName: String, val progress: Double = 0.0, val output: File? = null)
data class EditorState(
    val project: AudioProject = AudioProject(UUID.randomUUID().toString(), "我的舞曲", emptyList()),
    val ready: Boolean = false,
    val loadFailed: Boolean = false,
    val selectedSongId: String? = null,
    val sourceCursorUs: Long = 0,
    val selectionStartUs: Long = 0,
    val selectionEndUs: Long = 0,
    val zoom: Float = 1f,
    val viewportStartUs: Long = 0,
    val waveforms: Map<String, WaveformData> = emptyMap(),
    val waveformLoading: Boolean = false,
    val waveformError: String? = null,
    val videos: List<VideoTask> = emptyList(),
    val busy: String? = null,
    val activeVideoId: String? = null,
    val progress: Double = 0.0,
    val exportOutput: File? = null,
    val error: String? = null,
    val notice: String? = null,
    val saveStatus: String = "正在恢复项目…",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val preview: PreviewPosition = PreviewPosition(),
) {
    val selectedSong: SongItem? get() = project.songs.firstOrNull { it.id == selectedSongId }
}

@UnstableApi
class AudioViewModel(private val repository: AppRepository, context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = mutable.asStateFlow()
    private val histories = mutableMapOf<String, SongHistory>()
    private var operation: Job? = null
    private var saving: Job? = null
    private var waveJob: Job? = null
    private var waveGeneration = 0
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistenceEnabled = false
    private val preview = PreviewController(context.applicationContext) { message -> mutable.update { it.copy(error = message) } }
    init {
        restoreProject()
        viewModelScope.launch { while (true) { val position = preview.position(); mutable.update { it.copy(preview = position, sourceCursorUs = if (position.songId == it.selectedSongId && position.songId != null) position.sourceUs else it.sourceCursorUs) }; delay(80) } }
    }
    fun restoreProject() {
        if (mutable.value.busy != null) return
        viewModelScope.launch {
            try {
                val project = repository.loadProject()
                histories.clear(); project.songs.forEach { histories[it.id] = SongHistory(it) }
                persistenceEnabled = true
                val song = project.songs.firstOrNull()
                mutable.update { it.copy(project = project, ready = true, loadFailed = false, selectedSongId = song?.id,
                    selectionStartUs = 0, selectionEndUs = song?.source?.durationUs ?: 0, saveStatus = "项目已恢复", error = null) }
                analyzeSelected()
            } catch (e: Exception) {
                persistenceEnabled = false
                mutable.update { it.copy(ready = true, loadFailed = true, error = e.message ?: "项目恢复失败", saveStatus = "自动保存已暂停，原项目已保留") }
            }
        }
    }
    private fun projectChanged(project: AudioProject) {
        val previous = mutable.value.project
        if (previous.songs != project.songs || previous.settings != project.settings) preview.stop()
        mutable.update { it.copy(project = project, exportOutput = null, saveStatus = if (persistenceEnabled) "正在保存…" else it.saveStatus) }
        saving?.cancel()
        if (persistenceEnabled) saving = persistenceScope.launch {
            delay(500)
            try { repository.saveProject(project); mutable.update { it.copy(saveStatus = "已自动保存") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(saveStatus = "保存失败", error = "自动保存失败：${e.message}") } }
        }
    }
    fun persistNow() {
        if (!persistenceEnabled) return
        saving?.cancel()
        val project = mutable.value.project
        saving = persistenceScope.launch {
            try { repository.saveProject(project); mutable.update { it.copy(saveStatus = "已自动保存") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(saveStatus = "保存失败", error = "自动保存失败：${e.message}") } }
        }
    }
    fun title(value: String) { projectChanged(mutable.value.project.copy(title = value)) }
    fun settings(value: ExportSettings) { projectChanged(mutable.value.project.copy(settings = value)) }
    fun dismissError() { mutable.update { it.copy(error = null, notice = null) } }
    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty() || mutable.value.busy != null || !persistenceEnabled) return
        waveJob?.cancel()
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = "正在导入歌曲", progress = 0.0) }
            try {
                val failed = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    try {
                        val song = repository.importAudio(uri)
                        histories[song.id] = SongHistory(song)
                        projectChanged(mutable.value.project.copy(songs = mutable.value.project.songs + song))
                        if (mutable.value.selectedSongId == null) selectSong(song.id, analyze = false)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed += e.message ?: "文件导入失败" }
                    mutable.update { it.copy(progress = (index + 1.0) / uris.size) }
                }
                if (failed.isNotEmpty()) mutable.update { it.copy(error = failed.joinToString("\n")) }
            } catch (_: CancellationException) { }
            finally { mutable.update { it.copy(busy = null) }; analyzeSelected() }
        }
    }
    fun importVideos(uris: List<Uri>) {
        if (uris.isEmpty() || mutable.value.busy != null) return
        waveJob?.cancel()
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = "正在导入视频", progress = 0.0) }
            try {
                val failed = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    try {
                        val video = repository.importVideo(uri)
                        mutable.update { it.copy(videos = it.videos + VideoTask(video, video.displayName.substringBeforeLast('.', video.displayName))) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed += e.message ?: "视频导入失败" }
                    mutable.update { it.copy(progress = (index + 1.0) / uris.size) }
                }
                if (failed.isNotEmpty()) mutable.update { it.copy(error = failed.joinToString("\n")) }
            } catch (_: CancellationException) { }
            finally { mutable.update { it.copy(busy = null) }; analyzeSelected() }
        }
    }
    fun videoName(id: String, value: String) { mutable.update { it.copy(videos = it.videos.map { row -> if (row.input.id == id) row.copy(outputName = value) else row }) } }
    fun extract(id: String) {
        val row = mutable.value.videos.firstOrNull { it.input.id == id } ?: return
        runOperation("正在提取 ${row.input.displayName}", id) {
            val file = repository.extract(row.input, row.outputName) { progress ->
                mutable.update { it.copy(progress = progress, videos = it.videos.map { v -> if (v.input.id == id) v.copy(progress = progress) else v }) }
            }
            mutable.update { it.copy(videos = it.videos.map { v -> if (v.input.id == id) v.copy(output = file, progress = 1.0) else v }, notice = "音轨提取完成") }
        }
    }
    fun extractAll() {
        val rows = mutable.value.videos.filter { it.output == null }
        if (rows.isEmpty()) return
        runOperation("正在批量提取视频") {
            val errors = mutableListOf<String>()
            rows.forEachIndexed { index, row ->
                mutable.update { it.copy(activeVideoId = row.input.id) }
                try {
                    val file = repository.extract(row.input, row.outputName) { progress ->
                        mutable.update { it.copy(progress = (index + progress) / rows.size, videos = it.videos.map { v -> if (v.input.id == row.input.id) v.copy(progress = progress) else v }) }
                    }
                    mutable.update { it.copy(videos = it.videos.map { v -> if (v.input.id == row.input.id) v.copy(output = file, progress = 1.0) else v }) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${row.input.displayName}：${e.message}" }
            }
            mutable.update { it.copy(progress = 1.0, notice = "批量提取完成", error = errors.takeIf { list -> list.isNotEmpty() }?.joinToString("\n")) }
        }
    }
    fun export() {
        val project = mutable.value.project
        if (project.songs.isEmpty()) return
        runOperation("正在导出舞曲") {
            val file = repository.export(project) { progress -> mutable.update { it.copy(progress = progress) } }
            mutable.update { it.copy(exportOutput = file, notice = "导出完成，可保存或分享") }
        }
    }
    fun saveOutput(file: File, uri: Uri) = runOperation("正在保存文件") { repository.saveOutput(file, uri); mutable.update { it.copy(notice = "文件已保存") } }
    fun saveToMusic(file: File) = runOperation("正在保存到音乐库") { repository.saveToMusic(file); mutable.update { it.copy(notice = "已保存到音乐库的 AudioAssistant 文件夹") } }
    fun shareUri(file: File): Uri = repository.shareUri(file)
    private fun runOperation(label: String, videoId: String? = null, block: suspend () -> Unit) {
        if (mutable.value.busy != null) return
        preview.pause(); waveJob?.cancel()
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = label, activeVideoId = videoId, progress = 0.0, waveformLoading = false) }
            try { block() }
            catch (_: CancellationException) { }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作失败") } }
            finally { mutable.update { it.copy(busy = null, activeVideoId = null) }; analyzeSelected() }
        }
    }
    fun cancel() { repository.cancel(); operation?.cancel(); mutable.update { it.copy(notice = "已取消操作") } }
    fun selectSong(id: String, analyze: Boolean = true) {
        val song = mutable.value.project.songs.firstOrNull { it.id == id } ?: return
        val history = histories.getOrPut(id) { SongHistory(song) }
        mutable.update { it.copy(selectedSongId = id, sourceCursorUs = 0, selectionStartUs = 0, selectionEndUs = song.source.durationUs, zoom = 1f, viewportStartUs = 0,
            canUndo = history.canUndo, canRedo = history.canRedo, waveformError = null, waveformLoading = false) }
        if (analyze) analyzeSelected()
    }
    fun analyzeSelected() {
        val selected = mutable.value.selectedSong ?: return
        val songs = (listOf(selected) + mutable.value.project.songs.filter { it.id != selected.id })
            .filterNot { mutable.value.waveforms.containsKey(it.source.id) }
        if (songs.isEmpty() || mutable.value.busy != null) return
        val generation = ++waveGeneration
        waveJob?.cancel()
        waveJob = viewModelScope.launch {
            try {
                songs.forEach { song ->
                    if (mutable.value.waveforms.containsKey(song.source.id)) return@forEach
                    if (mutable.value.selectedSongId == song.id) mutable.update { it.copy(waveformLoading = true, waveformError = null) }
                    try {
                        val data = repository.waveform(song.source)
                        mutable.update { it.copy(waveforms = it.waveforms + (song.source.id to data), waveformLoading = if (it.selectedSongId == song.id) false else it.waveformLoading) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (mutable.value.selectedSongId == song.id) mutable.update { it.copy(waveformLoading = false, waveformError = e.message ?: "波形分析失败") } }
                }
            } finally { if (generation == waveGeneration) mutable.update { it.copy(waveformLoading = false) } }
        }
    }
    fun selection(startUs: Long, endUs: Long) {
        val duration = mutable.value.selectedSong?.source?.durationUs ?: return
        mutable.update { it.copy(selectionStartUs = minOf(startUs, endUs).coerceIn(0, duration), selectionEndUs = maxOf(startUs, endUs).coerceIn(0, duration)) }
    }
    fun nudge(start: Boolean, deltaUs: Long) {
        val s = mutable.value
        if (start) selection((s.selectionStartUs + deltaUs).coerceAtMost(s.selectionEndUs), s.selectionEndUs)
        else selection(s.selectionStartUs, (s.selectionEndUs + deltaUs).coerceAtLeast(s.selectionStartUs))
    }
    fun zoom(value: Float, anchorFraction: Float = 0.5f) {
        val state = mutable.value; val duration = state.selectedSong?.source?.durationUs ?: return
        val next = value.coerceIn(1f, 128f)
        val oldSpan = (duration / state.zoom).toLong(); val newSpan = (duration / next).toLong().coerceAtLeast(1)
        val anchor = state.viewportStartUs + (oldSpan * anchorFraction).toLong()
        mutable.update { it.copy(zoom = next, viewportStartUs = (anchor - newSpan * anchorFraction).toLong().coerceIn(0, (duration - newSpan).coerceAtLeast(0))) }
    }
    fun transformViewport(zoomFactor: Float, panFraction: Float, anchorFraction: Float) {
        zoom(mutable.value.zoom * zoomFactor, anchorFraction)
        val current = mutable.value
        val duration = current.selectedSong?.source?.durationUs ?: return
        pan(current.viewportStartUs - (panFraction * duration / current.zoom).toLong())
    }
    fun pan(startUs: Long) {
        val s = mutable.value; val duration = s.selectedSong?.source?.durationUs ?: return
        val span = (duration / s.zoom).toLong()
        mutable.update { it.copy(viewportStartUs = startUs.coerceIn(0, (duration - span).coerceAtLeast(0))) }
    }
    fun focusSelection() {
        val s = mutable.value; val duration = s.selectedSong?.source?.durationUs ?: return
        val length = (s.selectionEndUs - s.selectionStartUs).coerceAtLeast(100_000)
        zoom((duration.toDouble() / (length * 1.3)).toFloat()); pan(s.selectionStartUs - (length * 0.15).toLong())
    }
    private fun edit(transform: (SongItem) -> SongItem) {
        val song = mutable.value.selectedSong ?: return
        val history = histories.getOrPut(song.id) { SongHistory(song) }
        replaceSong(history.edit(transform))
    }
    private fun replaceSong(song: SongItem) {
        preview.stop()
        projectChanged(mutable.value.project.copy(songs = mutable.value.project.songs.map { if (it.id == song.id) song else it }))
        val h = histories[song.id]
        mutable.update { it.copy(canUndo = h?.canUndo == true, canRedo = h?.canRedo == true) }
    }
    fun trim(action: String) {
        val s = mutable.value
        val currentPlayback = preview.position()
        val splitAtUs = if (currentPlayback.songId == s.selectedSongId && currentPlayback.songId != null) currentPlayback.sourceUs else s.sourceCursorUs
        if (action != "split" && s.selectionEndUs <= s.selectionStartUs) return
        edit { song -> song.copy(segments = when (action) {
            "delete" -> SegmentEditor.delete(song.segments, s.selectionStartUs, s.selectionEndUs, song.source.durationUs)
            "keep" -> SegmentEditor.keep(song.segments, s.selectionStartUs, s.selectionEndUs, song.source.durationUs)
            "restore" -> SegmentEditor.restore(song.segments, s.selectionStartUs, s.selectionEndUs, song.source.durationUs)
            "split" -> SegmentEditor.split(song.segments, splitAtUs, song.source.durationUs)
            else -> song.segments
        }) }
    }
    fun gain(value: Double) = edit { it.copy(gainDb = value) }
    fun undo() { mutable.value.selectedSong?.id?.let { id -> histories[id]?.let { replaceSong(it.undo()) } } }
    fun redo() { mutable.value.selectedSong?.id?.let { id -> histories[id]?.let { replaceSong(it.redo()) } } }
    fun moveSong(id: String, direction: Int) {
        if (mutable.value.busy != null) return
        val songs = mutable.value.project.songs.toMutableList(); val index = songs.indexOfFirst { it.id == id }; val next = index + direction
        if (index < 0 || next !in songs.indices) return
        val song = songs.removeAt(index); songs.add(next, song); projectChanged(mutable.value.project.copy(songs = songs))
    }
    fun reorderSongs(orderedIds: List<String>) {
        val current = mutable.value
        if (current.busy != null || current.loadFailed) return
        val songs = current.project.songs
        // Reject a stale drag if another action changed the membership mid-gesture.
        if (orderedIds.size != songs.size || orderedIds.toSet().size != songs.size ||
            orderedIds.toSet() != songs.map { it.id }.toSet()) return
        val byId = songs.associateBy { it.id }
        val reordered = orderedIds.map { byId.getValue(it) }
        if (reordered != songs) projectChanged(current.project.copy(songs = reordered))
    }
    fun removeSong(id: String) {
        preview.stop(); histories.remove(id)
        projectChanged(mutable.value.project.copy(songs = mutable.value.project.songs.filterNot { it.id == id }))
        if (mutable.value.selectedSongId == id) {
            val first = mutable.value.project.songs.firstOrNull()
            if (first != null) selectSong(first.id) else mutable.update { it.copy(selectedSongId = null, waveformLoading = false) }
        }
    }
    fun preview(mode: String) {
        val s = mutable.value; val song = s.selectedSong ?: return
        when (mode) {
            "original" -> preview.original(song)
            "edited" -> preview.edited(s.project.copy(songs = listOf(song)))
            "all" -> preview.edited(s.project)
            "selection" -> preview.selection(song, s.selectionStartUs, s.selectionEndUs)
            "start" -> preview.edge(song, s.selectionStartUs, "起点附近 ±1 秒")
            "end" -> preview.edge(song, s.selectionEndUs, "终点附近 ±1 秒")
            "boundary" -> preview.boundary(song, s.selectionStartUs, s.selectionEndUs)
        }
    }
    fun seekPlayback(targetUs: Long) { preview.seekTimeline(targetUs) }
    fun seekSource(sourceUs: Long) { mutable.update { it.copy(sourceCursorUs = sourceUs) }; mutable.value.selectedSongId?.let { if (!preview.seekSource(it, sourceUs)) preview.stop() } }
    fun togglePlayback() { preview.toggle() }
    fun pausePlayback() { preview.pause() }
    override fun onCleared() {
        repository.cancel(); preview.release(); saving?.cancel()
        val project = mutable.value.project
        persistenceScope.launch {
            try { if (persistenceEnabled) repository.saveProject(project) }
            catch (e: Exception) { android.util.Log.e("AudioAssistant", "Final project save failed", e) }
            finally { repository.close(); persistenceScope.cancel() }
        }
        super.onCleared()
    }
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AudioViewModel(AppRepository(context), context) as T
    }
}
