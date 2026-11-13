package com.audioassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCacheKeyTest {
    private val source = SourceAudio("source", "content://audio/1", "/private/source.wav", "Song.wav", 100, "sha256-content")
    private val song = SongItem("song", source, listOf(AudioSegment(0, 100)))
    private val settings = ExportSettings()

    @Test fun keysAreStableFullLengthSha256() {
        val key = AudioCacheKey.rendered(song, settings)
        assertEquals(key, AudioCacheKey.rendered(song.copy(), settings.copy()))
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(key, AudioCacheKey.forSong(song, settings))
    }

    @Test fun cacheFollowsContentAcrossRenamesAndCopies() {
        val moved = source.copy(id = "new", localPath = "/other/path", originalUri = "content://other", displayName = "Other.wav")
        assertEquals(AudioCacheKey.source(source), AudioCacheKey.source(moved))
        assertEquals(AudioCacheKey.rendered(song), AudioCacheKey.rendered(song.copy(id = "another-song", source = moved)))
    }

    @Test fun changingAudioOrItsInterpretationInvalidatesKeys() {
        for (changed in listOf(
            source.copy(fingerprint = "other"), source.copy(durationUs = 101),
            source.copy(sampleRate = 44_100), source.copy(channels = 1),
        )) {
            assertNotEquals(AudioCacheKey.source(source), AudioCacheKey.source(changed))
            assertNotEquals(AudioCacheKey.rendered(song), AudioCacheKey.rendered(song.copy(source = changed)))
        }
    }

    @Test fun editsGainAndExplicitSplitsInvalidateAnalysisAndRender() {
        for (changed in listOf(
            song.copy(segments = listOf(AudioSegment(0, 90))),
            song.copy(segments = listOf(AudioSegment(0, 50), AudioSegment(50, 100))),
            song.copy(gainDb = 1.0),
        )) {
            assertNotEquals(AudioCacheKey.loudness(song), AudioCacheKey.loudness(changed))
            assertNotEquals(AudioCacheKey.rendered(song), AudioCacheKey.rendered(changed))
        }
    }

    @Test fun semanticallyEquivalentUnnormalizedEditsShareAKey() {
        val equivalent = song.copy(segments = listOf(AudioSegment(20, 80), AudioSegment(-50, 150)))
        assertEquals(AudioCacheKey.rendered(song), AudioCacheKey.rendered(equivalent))
    }

    @Test fun everyExportSettingParticipatesInRenderKey() {
        for (changed in listOf(
            settings.copy(normalize = false), settings.copy(targetLufs = -18.0), settings.copy(truePeakDb = -2.0),
            settings.copy(lra = 7.0), settings.copy(bitRate = 128_000), settings.copy(sampleRate = 44_100),
            settings.copy(fadeMs = 500),
        )) assertNotEquals(AudioCacheKey.rendered(song, settings), AudioCacheKey.rendered(song, changed))
    }

    @Test fun loudnessTargetsAndPrefiltersInvalidateAnalysis() {
        for (changed in listOf(
            settings.copy(targetLufs = -18.0), settings.copy(truePeakDb = -2.0), settings.copy(lra = 7.0),
            settings.copy(fadeMs = 500), settings.copy(sampleRate = 44_100),
        )) assertNotEquals(AudioCacheKey.loudness(song, settings), AudioCacheKey.loudness(song, changed))
        assertEquals(AudioCacheKey.loudness(song, settings), AudioCacheKey.loudness(song, settings.copy(bitRate = 128_000)))
    }

    @Test fun waveformKeyDependsOnBucketsAndSourceOnly() {
        assertNotEquals(AudioCacheKey.waveform(source, 100), AudioCacheKey.waveform(source, 200))
        assertNotEquals(AudioCacheKey.waveform(source), AudioCacheKey.waveform(source.copy(fingerprint = "changed")))
        assertEquals(AudioCacheKey.waveform(source), AudioCacheKey.waveform(source.copy(displayName = "Renamed")))
    }

    @Test fun projectKeyDependsOnSongOrderAndSettings() {
        val other = song.copy(source = source.copy(fingerprint = "other"))
        val project = AudioProject("id", "Title", listOf(song, other))
        assertNotEquals(AudioCacheKey.project(project), AudioCacheKey.project(project.copy(songs = listOf(other, song))))
        assertNotEquals(AudioCacheKey.project(project), AudioCacheKey.project(project.copy(settings = settings.copy(fadeMs = 300))))
        assertEquals(AudioCacheKey.project(project), AudioCacheKey.project(project.copy(id = "new", title = "Renamed")))
    }

    @Test fun blankFingerprintFallsBackToSourceLocations() {
        val blank = source.copy(fingerprint = "")
        assertNotEquals(AudioCacheKey.source(blank), AudioCacheKey.source(blank.copy(localPath = "/different")))
        assertNotEquals(AudioCacheKey.source(blank), AudioCacheKey.source(blank.copy(originalUri = "content://different")))
    }

    @Test fun stringFieldsUseUnambiguousLengthPrefixes() {
        val first = source.copy(fingerprint = "", localPath = "ab", originalUri = "c")
        val second = source.copy(fingerprint = "", localPath = "a", originalUri = "bc")
        assertNotEquals(AudioCacheKey.source(first), AudioCacheKey.source(second))
    }

    @Test(expected = IllegalArgumentException::class)
    fun waveformRejectsZeroBuckets() { AudioCacheKey.waveform(source, 0) }
}
