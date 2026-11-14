package com.audioassistant.engine

import com.audioassistant.domain.AudioProject
import com.audioassistant.domain.SegmentEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AudioMetadata(
    val durationUs: Long,
    val sampleRate: Int,
    val channels: Int,
    val codec: String,
)

/**
 * One cancellable libav job at a time across all instances. Native dependencies
 * load on first operation, so a missing installation produces an actionable
 * operation error instead of crashing Application.onCreate.
 */
class NativeEngine : Closeable {
    private val lifecycle = Any()
    private var handle = 0L
    private var closed = false

    suspend fun probe(path: String): AudioMetadata {
        val json = JSONObject(run(request("probe", path)))
        return AudioMetadata(
            durationUs = json.getLong("durationUs"),
            sampleRate = json.getInt("sampleRate"),
            channels = json.getInt("channels"),
            codec = json.getString("codec"),
        )
    }

    /** AWF1: 36-byte little-endian header followed by interleaved float32 peaks. */
    suspend fun waveform(path: String, outputPath: String, onProgress: (Double) -> Unit) {
        run(request("waveform", path).put("output", outputPath), onProgress)
    }

    /** True MP3: copy compatible MP3 packets, otherwise encode with libmp3lame. */
    suspend fun extract(input: String, output: String, onProgress: (Double) -> Unit) {
        run(request("extract", input).put("output", output), onProgress)
    }

    suspend fun export(
        project: AudioProject,
        output: String,
        cacheDir: String,
        onProgress: (Double) -> Unit,
    ) {
        val settings = project.settings
        val nativeSettings = JSONObject()
            .put("normalize", settings.normalize)
            .put("targetLufs", settings.targetLufs)
            .put("truePeakDb", settings.truePeakDb)
            .put("lra", settings.lra)
            .put("bitRate", settings.bitRate)
            .put("sampleRate", settings.sampleRate)
            .put("fadeMs", settings.fadeMs)
        val songs = JSONArray()
        project.songs.forEach { song ->
            val segments = JSONArray()
            SegmentEditor.normalize(song.segments, song.source.durationUs).forEach { segment ->
                segments.put(JSONObject().put("startUs", segment.startUs).put("endUs", segment.endUs))
            }
            songs.put(JSONObject().put("path", song.source.localPath).put("gainDb", song.gainDb).put("segments", segments))
        }
        run(
            JSONObject().put("operation", "export").put("output", output).put("cacheDir", cacheDir)
                .put("project", JSONObject().put("settings", nativeSettings).put("songs", songs)),
            onProgress,
        )
    }

    /** Safe on the main thread; libav observes the flag while reading/processing. */
    fun cancel() = synchronized(lifecycle) {
        if (handle != 0L) NativeBridge.cancel(handle)
    }

    /** Cancels active work; native shared ownership defers deletion until it exits. */
    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            if (handle != 0L) NativeBridge.destroy(handle)
            handle = 0L
        }
    }

    private suspend fun run(request: JSONObject, onProgress: (Double) -> Unit = {}): String =
        jobs.withLock {
            withContext(Dispatchers.IO) {
                val currentHandle = synchronized(lifecycle) {
                    check(!closed) { "Audio engine is closed" }
                    if (handle == 0L) {
                        NativeBridge.load()
                        handle = NativeBridge.create()
                        check(handle != 0L) { "Could not create the native audio engine" }
                    }
                    // Clear the previous job's cancellation before registering
                    // this coroutine's cancellation, never inside native run().
                    NativeBridge.prepare(handle)
                    handle
                }
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { NativeBridge.cancel(currentHandle) }
                    if (continuation.isActive) {
                        try {
                            val response = NativeBridge.run(currentHandle, request.toString(), NativeProgress(onProgress))
                            if (continuation.isActive) continuation.resume(response)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(actionableError(error))
                        }
                    }
                }
            }
        }

    private fun actionableError(error: Throwable): Throwable {
        if (error is CancellationException) return error
        val detail = error.message.orEmpty()
        val message = when {
            "Find audio stream" in detail || "No decodable audio" in detail || "no decodable audio" in detail ->
                "文件中没有可解码的音轨，请选择包含音频的文件。"
            "Invalid data" in detail || "corrupt" in detail.lowercase() ->
                "音频文件损坏或格式不受支持，请重新导入原始文件。"
            "libmp3lame encoder unavailable" in detail || "MP3 muxer unavailable" in detail ->
                "缺少 MP3 转换组件，请重新构建并打包包含 LAME 的 FFmpeg 音频库。"
            "libmp3lame exposes no supported" in detail ->
                "MP3 编码器不支持所需采样格式，请检查 LAME 与 FFmpeg 的构建配置。"
            "No such file" in detail -> "本地音频文件已丢失，请重新导入。"
            "Permission denied" in detail || "Operation not permitted" in detail ->
                "无法读取或写入文件，请重新选择文件并授予访问权限。"
            "storage" in detail.lowercase() || "No space" in detail || "Write audio failed" in detail ->
                "存储空间不足或文件无法写入，请清理空间后重试。"
            "segment" in detail.lowercase() || "Source ended" in detail ->
                "剪辑范围超出音频时长或已为空，请调整保留片段后重试。"
            "insufficient true-peak headroom" in detail ->
                "正增益过高，无法保留真峰值余量，请降低该歌曲的增益。"
            "no seek timestamps" in detail ->
                "此文件缺少可靠的定位时间戳，请先转换为 MP3 后再剪辑。"
            "decoder" in detail.lowercase() || "encoder unavailable" in detail ->
                "当前音频库不支持此编码格式，请转换文件或检查 FFmpeg 依赖。"
            "closed" in detail -> "音频引擎已关闭，请重新打开项目。"
            else -> "音频处理失败：$detail"
        }
        return IllegalStateException(message, error)
    }

    private fun request(operation: String, input: String) =
        JSONObject().put("operation", operation).put("input", input)

    companion object { private val jobs = Mutex() }
}

/** Named callback method is kept in proguard-rules.pro for JNI lookup. */
internal class NativeProgress(private val callback: (Double) -> Unit) {
    fun onProgress(value: Double) = callback(value)
}

internal object NativeBridge {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        try {
            System.loadLibrary("audioassistant")
            loaded = true
        } catch (error: LinkageError) {
            throw IllegalStateException(
                "缺少本机 FFmpeg 音频库。请先构建并打包官方 arm64-v8a FFmpeg 依赖，再重新安装应用。",
                error,
            )
        }
    }

    external fun create(): Long
    external fun prepare(handle: Long)
    external fun run(handle: Long, request: String, progress: NativeProgress): String
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}
