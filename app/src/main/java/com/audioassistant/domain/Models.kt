package com.audioassistant.domain

import kotlinx.serialization.Serializable

/** A half-open interval on the original source's microsecond timeline. */
@Serializable
data class AudioSegment(val startUs: Long, val endUs: Long) {
    val durationUs: Long
        get() {
            if (endUs <= startUs) return 0
            val difference = endUs - startUs
            return if (difference < 0) Long.MAX_VALUE else difference
        }
}

@Serializable
data class SourceAudio(
    val id: String,
    val originalUri: String,
    val localPath: String,
    val displayName: String,
    val durationUs: Long,
    val fingerprint: String,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
)

@Serializable
data class SongItem(
    val id: String,
    val source: SourceAudio,
    val segments: List<AudioSegment>,
    val gainDb: Double = 0.0,
) {
    val editedDurationUs: Long
        get() = SegmentEditor.editedDurationUs(
            SegmentEditor.normalize(segments, source.durationUs),
        )
}

@Serializable
data class AudioProject(
    val id: String,
    val title: String,
    val songs: List<SongItem>,
    val settings: ExportSettings = ExportSettings(),
)

/** The five values returned by FFmpeg's first loudnorm pass. */
@Serializable
data class LoudnessInfo(
    val inputI: Double,
    val inputTp: Double,
    val inputLra: Double,
    val inputThresh: Double,
    val targetOffset: Double,
)

@Serializable
data class ExportSettings(
    val normalize: Boolean = true,
    val targetLufs: Double = -16.0,
    val truePeakDb: Double = -1.5,
    val lra: Double = 11.0,
    val bitRate: Int = 192_000,
    val sampleRate: Int = 48_000,
    val fadeMs: Int = 0,
)
