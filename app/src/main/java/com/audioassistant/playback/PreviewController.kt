package com.audioassistant.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.audioassistant.domain.AudioProject
import com.audioassistant.domain.AudioSegment
import com.audioassistant.domain.SongItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Every playlist item carries its original source interval, even after a cut. */
data class PreviewClip(val songId: String, val sourceStartUs: Long, val sourceEndUs: Long, val gainDb: Double)
data class PreviewPosition(val playing: Boolean = false, val songId: String? = null, val sourceUs: Long = 0, val timelineUs: Long = 0, val durationUs: Long = 0, val label: String = "尚未播放")

@UnstableApi
class PreviewController(context: Context, private val onError: (String) -> Unit) {
    private val gain = GainProcessor()
    private val rendererFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setAudioProcessors(arrayOf(gain)).build()
    }
    private val player = ExoPlayer.Builder(context, rendererFactory).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private var clips = emptyList<PreviewClip>()
    private var label = "尚未播放"
    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateGain() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onError("预览失败：${error.localizedMessage ?: error.errorCodeName}") }
        })
    }
    private fun updateGain() { gain.multiplier = 10.0.pow((clips.getOrNull(player.currentMediaItemIndex)?.gainDb ?: 0.0) / 20.0).toFloat() }
    private fun start(items: List<Pair<SongItem, AudioSegment>>, title: String) {
        if (items.isEmpty()) { onError("没有可试听的保留片段"); return }
        label = title
        clips = items.map { (song, segment) -> PreviewClip(song.id, segment.startUs, segment.endUs, song.gainDb.toDouble()) }
        player.setMediaItems(items.mapIndexed { index, (song, segment) ->
            MediaItem.Builder().setMediaId("${song.id}:$index")
                .setUri(Uri.fromFile(File(song.source.localPath)))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(segment.startUs).setEndPositionUs(segment.endUs).build()).build()
        })
        updateGain()
        player.prepare()
        player.play()
    }
    fun original(song: SongItem) = start(listOf(song to AudioSegment(0, song.source.durationUs)), "原音试听")
    fun edited(project: AudioProject) = start(project.songs.flatMap { song -> song.segments.map { song to it } }, "编排试听")
    fun selection(song: SongItem, startUs: Long, endUs: Long) = start(listOf(song to AudioSegment(startUs, endUs)).filter { it.second.endUs > it.second.startUs }, "选区试听")
    fun edge(song: SongItem, atUs: Long, title: String) {
        val startUs = (atUs - 1_000_000).coerceAtLeast(0)
        val endUs = (atUs + 1_000_000).coerceAtMost(song.source.durationUs)
        start(listOf(song to AudioSegment(startUs, endUs)).filter { it.second.endUs > it.second.startUs }, title)
    }
    fun boundary(song: SongItem, startUs: Long, endUs: Long) {
        val radius = 1_000_000L
        val before = song.segments.mapNotNull { s ->
            val a = maxOf(s.startUs, startUs - radius); val b = minOf(s.endUs, startUs)
            if (b > a) song to AudioSegment(a, b) else null
        }
        val after = song.segments.mapNotNull { s ->
            val a = maxOf(s.startUs, endUs); val b = minOf(s.endUs, endUs + radius)
            if (b > a) song to AudioSegment(a, b) else null
        }
        start(before + after, "剪口试听（前后各 1 秒）")
    }
    fun seekTimeline(targetUs: Long) {
        var remaining = targetUs.coerceIn(0, clips.sumOf { it.sourceEndUs - it.sourceStartUs })
        clips.forEachIndexed { index, clip ->
            val length = clip.sourceEndUs - clip.sourceStartUs
            if (remaining < length || index == clips.lastIndex) {
                player.seekTo(index, remaining.coerceAtMost(length) / 1000)
                return
            }
            remaining -= length
        }
    }
    fun seekSource(songId: String, targetUs: Long): Boolean {
        val index = clips.indexOfFirst { it.songId == songId && targetUs >= it.sourceStartUs && targetUs <= it.sourceEndUs }
        if (index >= 0) player.seekTo(index, (targetUs - clips[index].sourceStartUs) / 1000)
        return index >= 0
    }
    fun toggle() { if (player.isPlaying) player.pause() else if (player.mediaItemCount > 0) { if (player.playbackState == Player.STATE_ENDED) seekTimeline(0); player.play() } }
    fun pause() { player.pause() }
    fun stop() { player.stop(); player.clearMediaItems(); clips = emptyList(); label = "已停止" }
    fun position(): PreviewPosition {
        val index = player.currentMediaItemIndex
        val clip = clips.getOrNull(index)
        val localUs = player.currentPosition.coerceAtLeast(0) * 1000
        return PreviewPosition(player.isPlaying, clip?.songId, clip?.let { (it.sourceStartUs + localUs).coerceAtMost(it.sourceEndUs) } ?: 0,
            clips.take(index.coerceAtLeast(0)).sumOf { it.sourceEndUs - it.sourceStartUs } + localUs,
            clips.sumOf { it.sourceEndUs - it.sourceStartUs }, label)
    }
    fun release() { player.release() }
}

/** Gain is applied to PCM, including positive values; samples saturate safely. */
@UnstableApi
private class GainProcessor : BaseAudioProcessor() {
    @Volatile var multiplier = 1f
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val scale = multiplier
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) output.putFloat((inputBuffer.float * scale).coerceIn(-1f, 1f))
        } else {
            while (inputBuffer.remaining() >= 2) output.putShort((inputBuffer.short.toFloat() * scale).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }
}
