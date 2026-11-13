package com.audioassistant.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelsSerializationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun fullProjectRoundTripsWithExactMicrosecondPrecisionAndExplicitSplits() {
        val duration = 9_007_199_254_740_993L
        val source = SourceAudio("s", "content://audio/42", "/private/歌曲.wav", "歌曲 🎵.wav", duration, "content-hash", 96_000, 1)
        val project = AudioProject(
            "p", "Concert", listOf(SongItem("song", source, listOf(AudioSegment(0, 10), AudioSegment(10, duration)), -2.5)),
            ExportSettings(false, -18.0, -2.0, 7.0, 320_000, 44_100, 500),
        )
        val encoded = json.encodeToString(project)
        assertEquals(project, json.decodeFromString<AudioProject>(encoded))
        assertFalse(encoded.contains("editedDurationUs"))
        assertFalse(encoded.contains("\"durationUs\":10"))
    }

    @Test fun defaultSettingsAndSourceFormatSupportOlderProjectFiles() {
        val decoded = json.decodeFromString<AudioProject>(
            """{"id":"p","title":"Imported","songs":[{"id":"i","source":{"id":"s","originalUri":"content://audio/1","localPath":"/data/a.wav","displayName":"a.wav","durationUs":100,"fingerprint":"hash"},"segments":[{"startUs":0,"endUs":100}],"futureField":true}]}""",
        )
        assertEquals(ExportSettings(), decoded.settings)
        assertEquals(48_000, decoded.songs.single().source.sampleRate)
        assertEquals(2, decoded.songs.single().source.channels)
        assertEquals(0.0, decoded.songs.single().gainDb, 0.0)
    }

    @Test fun analysisRoundTripsWithoutLosingMeasurementValues() {
        val info = LoudnessInfo(-21.73, -2.45, 9.12, -32.7, 0.04)
        assertEquals(info, json.decodeFromString<LoudnessInfo>(json.encodeToString(info)))
    }

    @Test fun songComputedDurationUsesClampedNonOverlappingSourceCoverage() {
        val source = SourceAudio("s", "", "", "", 100, "hash")
        val song = SongItem("i", source, listOf(AudioSegment(-20, 60), AudioSegment(30, 150)))
        assertEquals(100L, song.editedDurationUs)
    }

    @Test fun rawIntervalDurationHandlesInvalidAndOverflowingInput() {
        assertEquals(40L, AudioSegment(10, 50).durationUs)
        assertEquals(0L, AudioSegment(50, 10).durationUs)
        assertEquals(0L, AudioSegment(10, 10).durationUs)
        assertEquals(Long.MAX_VALUE, AudioSegment(Long.MIN_VALUE, Long.MAX_VALUE).durationUs)
    }
}
