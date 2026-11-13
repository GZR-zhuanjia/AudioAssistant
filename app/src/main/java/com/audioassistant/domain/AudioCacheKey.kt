package com.audioassistant.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Content-based versioned keys, independent of filenames, song IDs and JSON formatting. */
object AudioCacheKey {
    fun source(source: SourceAudio): String = digest("source-v1") { writeSource(source) }

    fun waveform(source: SourceAudio, bucketCount: Int = 1_000): String {
        require(bucketCount > 0)
        return digest("waveform-v1") {
            writeSource(source)
            writeInt(bucketCount)
        }
    }

    /** Include loudnorm targets because FFmpeg's analysis output depends on them. */
    fun loudness(song: SongItem, settings: ExportSettings = ExportSettings()): String =
        digest("loudness-v1") {
            writeSong(song)
            writeDouble(settings.targetLufs)
            writeDouble(settings.truePeakDb)
            writeDouble(settings.lra)
            writeInt(settings.fadeMs)
            writeInt(settings.sampleRate)
        }

    fun rendered(song: SongItem, settings: ExportSettings = ExportSettings()): String =
        digest("render-v1") {
            writeSong(song)
            writeSettings(settings)
        }

    fun forSong(song: SongItem, settings: ExportSettings = ExportSettings()): String =
        rendered(song, settings)

    fun project(project: AudioProject): String = digest("project-render-v1") {
        writeInt(project.songs.size)
        for (song in project.songs) writeSong(song)
        writeSettings(project.settings)
    }

    private fun DataOutputStream.writeSource(source: SourceAudio) {
        writeString(source.fingerprint)
        // A missing fingerprint must never alias unrelated imported files.
        if (source.fingerprint.isBlank()) {
            writeString(source.localPath)
            writeString(source.originalUri)
        }
        writeLong(source.durationUs)
        writeInt(source.sampleRate)
        writeInt(source.channels)
    }

    private fun DataOutputStream.writeSong(song: SongItem) {
        writeSource(song.source)
        val retained = SegmentEditor.normalize(song.segments, song.source.durationUs)
        writeInt(retained.size)
        for (segment in retained) {
            writeLong(segment.startUs)
            writeLong(segment.endUs)
        }
        writeDouble(song.gainDb)
    }

    private fun DataOutputStream.writeSettings(settings: ExportSettings) {
        writeBoolean(settings.normalize)
        writeDouble(settings.targetLufs)
        writeDouble(settings.truePeakDb)
        writeDouble(settings.lra)
        writeInt(settings.bitRate)
        writeInt(settings.sampleRate)
        writeInt(settings.fadeMs)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private inline fun digest(version: String, write: DataOutputStream.() -> Unit): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { stream ->
            stream.writeString(version)
            stream.write()
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
