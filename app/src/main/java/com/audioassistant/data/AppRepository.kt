package com.audioassistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.audioassistant.domain.AudioProject
import com.audioassistant.domain.AudioSegment
import com.audioassistant.domain.SongItem
import com.audioassistant.domain.SourceAudio
import com.audioassistant.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

data class VideoInput(val id: String, val displayName: String, val localPath: String)

/** Base level is one maximum across channels per 1024 decoded samples. Higher levels
 * halve the number of bins; gesture redraws never invoke the decoder. */
data class WaveformData(val levels: List<FloatArray>) {
    fun peaksFor(pixelWidth: Int): FloatArray = levels.firstOrNull { it.size <= max(1, pixelWidth) * 2 }
        ?: levels.lastOrNull() ?: floatArrayOf()
}

/** Files in sources/ are immutable import snapshots, independent of revocable SAF grants.
 * Every native operation receives an app-owned path, never a content:// pseudo-path. */
class AppRepository(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val engine = NativeEngine()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val files = this.context.filesDir
    private val sources = File(files, "sources").apply { mkdirs() }
    private val outputs = File(files, "outputs").apply { mkdirs() }
    private val waveformDir = File(this.context.cacheDir, "waveforms").apply { mkdirs() }
    private val loudnessDir = File(this.context.cacheDir, "loudness").apply { mkdirs() }
    private val imports = File(this.context.cacheDir, "imports").apply { mkdirs() }
    private val projectFile = AtomicFile(File(files, "project.json"))
    private val projectMutex = Mutex()
    private val waveformMutex = Mutex()

    suspend fun loadProject(): AudioProject = withContext(Dispatchers.IO) {
        projectMutex.withLock {
            cleanupInterruptedWork()
            if (!projectFile.baseFile.exists()) return@withLock AudioProject(
                id = UUID.randomUUID().toString(), title = "我的舞曲", songs = emptyList()
            )
            try {
                projectFile.openRead().use { json.decodeFromString<AudioProject>(it.reader().readText()) }
            } catch (e: Exception) {
                // Keep the broken file available for recovery rather than silently replacing it.
                throw IOException("项目读取失败，原始 project.json 已保留，请先备份后重试", e)
            }
        }
    }

    /** Process death bypasses native destructors. Sweep only old work artifacts;
     * never delete source snapshots or finished exports, nor another fresh job. */
    private fun cleanupInterruptedWork() {
        val olderThan = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        listOf(imports, waveformDir, loudnessDir, File(loudnessDir, "loudness-v1"), outputs).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.lastModified() < olderThan &&
                    (file.name.endsWith(".partial") || (file.isDirectory && file.name.startsWith("audio-job-")))) {
                    file.deleteRecursively()
                }
            }
        }
    }

    suspend fun saveProject(project: AudioProject) = withContext(Dispatchers.IO) {
        projectMutex.withLock {
            val stream = projectFile.startWrite()
            try {
                stream.write(json.encodeToString(project).toByteArray(Charsets.UTF_8))
                projectFile.finishWrite(stream)
            } catch (e: Exception) {
                projectFile.failWrite(stream)
                throw e
            }
        }
    }

    suspend fun importAudio(uri: Uri): SongItem = withContext(Dispatchers.IO) {
        val staged = stage(uri)
        val metadata = engine.probe(staged.file.absolutePath)
        require(metadata.durationUs > 0) { "无法确定音频时长，暂不支持该文件" }
        val source = SourceAudio(
            id = UUID.randomUUID().toString(), originalUri = uri.toString(),
            localPath = staged.file.absolutePath, displayName = staged.name,
            durationUs = metadata.durationUs, fingerprint = staged.digest,
            sampleRate = metadata.sampleRate, channels = metadata.channels
        )
        SongItem(UUID.randomUUID().toString(), source, listOf(AudioSegment(0, source.durationUs)))
    }

    suspend fun importVideo(uri: Uri): VideoInput = withContext(Dispatchers.IO) {
        val staged = stage(uri)
        // Probe now so the user sees a missing audio stream before queuing extraction.
        engine.probe(staged.file.absolutePath)
        VideoInput(UUID.randomUUID().toString(), staged.name, staged.file.absolutePath)
    }

    private data class Staged(val file: File, val name: String, val digest: String)

    private suspend fun stage(uri: Uri): Staged {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: "导入文件"
        if (uri.scheme == "content") {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Snapshot remains usable without a persistent grant. */ }
        }
        val temporary = File(imports, "${UUID.randomUUID()}.partial")
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        hash.update(buffer, 0, read)
                    }
                }
            } ?: throw IOException("无法打开文件，访问权限可能已经失效")
            require(temporary.length() > 0) { "所选文件为空" }
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            val destination = File(sources, "$digest.media")
            if (!destination.exists() && !temporary.renameTo(destination)) {
                throw IOException("输入暂存失败，请检查可用空间")
            }
            return Staged(destination, name, digest)
        } catch (e: SecurityException) {
            throw IOException("文件访问权限已失效，请重新选择文件", e)
        } finally { temporary.delete() }
    }

    suspend fun waveform(source: SourceAudio): WaveformData = withContext(Dispatchers.IO) {
        waveformMutex.withLock {
            validateSource(source)
            val cache = File(waveformDir, "${source.fingerprint}-v1.awf")
            if (cache.exists()) {
                try { return@withLock readWaveform(cache) }
                catch (_: IOException) { cache.delete() }
                catch (_: IllegalArgumentException) { cache.delete() }
            }
            val partial = File(waveformDir, "${source.fingerprint}-${UUID.randomUUID()}.partial")
            try {
                engine.waveform(source.localPath, partial.absolutePath) { }
                val data = readWaveform(partial)
                if (!partial.renameTo(cache)) throw IOException("波形缓存保存失败")
                data
            } finally { partial.delete() }
        }
    }

    private fun readWaveform(file: File): WaveformData = DataInputStream(
        BufferedInputStream(file.inputStream())
    ).use { input ->
        val magic = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        require(magic == "AWF1") { "无效波形缓存" }
        fun u32() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun u64() = java.lang.Long.reverseBytes(input.readLong())
        require(u32() == 1L)
        val sampleRate = u32()
        val channels = u32().toInt()
        val duration = u64()
        val framesPerPeak = u32()
        val count = u64()
        require(sampleRate in 1..768000 && channels in 1..64 && duration >= 0 && framesPerPeak > 0)
        require(count in 1..8_000_000) { "音频过长，波形缓存超出限制" }
        require(file.length() == 36L + count * channels * 4L) { "波形缓存不完整" }
        val base = FloatArray(count.toInt()) {
            var peak = 0f
            repeat(channels) {
                val value = Float.fromBits(Integer.reverseBytes(input.readInt()))
                require(value.isFinite())
                peak = max(peak, value.coerceIn(0f, 1f))
            }
            peak
        }
        val levels = mutableListOf(base)
        while (levels.last().size > 256) {
            val previous = levels.last()
            levels += FloatArray((previous.size + 1) / 2) { i ->
                max(previous[i * 2], previous.getOrElse(i * 2 + 1) { 0f })
            }
        }
        WaveformData(levels)
    }

    suspend fun extract(video: VideoInput, outputName: String, onProgress: (Double) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(File(video.localPath).isFile) { "输入文件丢失，请重新选择视频" }
            createOutput(outputName) { output -> engine.extract(video.localPath, output.absolutePath, onProgress) }
        }

    suspend fun export(project: AudioProject, onProgress: (Double) -> Unit): File = withContext(Dispatchers.IO) {
        require(project.songs.isNotEmpty()) { "请先添加歌曲" }
        project.songs.forEach { song ->
            validateSource(song.source)
            require(song.segments.isNotEmpty()) { "${song.source.displayName} 没有保留片段" }
        }
        createOutput(project.title) { engine.export(project, it.absolutePath, loudnessDir.absolutePath, onProgress) }
    }

    private suspend fun createOutput(name: String, process: suspend (File) -> Unit): File {
        val cleaned = name.removeSuffix(".m4a").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        // Limit Unicode code points rather than UTF-16 units: never split an emoji
        // surrogate pair when passing the same output path through JNI UTF-8.
        val safeName = StringBuilder().apply {
            cleaned.codePoints().limit(60).forEach { appendCodePoint(it) }
        }.toString().ifBlank { "音频" }
        val output = File(outputs, "$safeName-${UUID.randomUUID().toString().take(8)}.m4a")
        try { process(output); return output }
        catch (e: Throwable) { output.delete(); throw e }
    }

    private fun validateSource(source: SourceAudio) {
        val sourceFile = File(source.localPath)
        require(sourceFile.isFile && sourceFile.length() > 0) { "${source.displayName} 的本地源文件丢失，请重新导入" }
        // App-private sources are content-addressed and are never modified by editing.
        require(sourceFile.canonicalFile.parentFile == sources.canonicalFile) { "项目中的源文件路径不合法" }
    }

    suspend fun saveOutput(file: File, destination: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("无法写入所选位置")
    }

    /** MediaStore uses pending rows so other apps never see a partially copied export. */
    suspend fun saveToMusic(file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/AudioAssistant")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建媒体库文件")
        try {
            saveOutput(file, uri)
            resolver.update(uri, android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (e: Throwable) { resolver.delete(uri, null, null); throw e }
    }

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    fun cancel() = engine.cancel()
    override fun close() { engine.close() }
}
