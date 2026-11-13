package com.audioassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongHistoryTest {
    private val source = SourceAudio("source", "content://audio/1", "/private/source.wav", "Song.wav", 100, "sha256")
    private val song = SongItem("song", source, listOf(AudioSegment(0, 100)))

    @Test fun editsUndoAndRedoWithSongGainAndSourcePreserved() {
        val history = SongHistory(song)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        val cut = history.edit { it.copy(segments = SegmentEditor.delete(it.segments, 20, 40, 100)) }
        val gain = history.edit { it.copy(gainDb = -3.0) }
        assertTrue(history.canUndo)
        assertEquals(cut, history.undo())
        assertEquals(song, history.undo())
        assertEquals(song, history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(cut, history.redo())
        assertEquals(gain, history.redo())
        assertEquals(gain, history.redo())
        assertFalse(history.canRedo)
        assertEquals(source, history.current.source)
    }

    @Test fun newEditAfterUndoClearsRedo() {
        val history = SongHistory(song)
        history.edit { it.copy(gainDb = 1.0) }
        history.edit { it.copy(gainDb = 2.0) }
        history.undo()
        val branch = history.edit { it.copy(gainDb = -1.0) }
        assertFalse(history.canRedo)
        assertEquals(branch, history.redo())
        assertEquals(1.0, history.undo().gainDb, 0.0)
    }

    @Test fun unchangedEditDoesNotEraseRedoOrConsumeHistory() {
        val history = SongHistory(song)
        history.commit(song.copy())
        assertFalse(history.canUndo)
        val edited = history.edit { it.copy(gainDb = 1.0) }
        history.undo()
        history.commit(song.copy())
        assertTrue(history.canRedo)
        assertEquals(edited, history.redo())
    }

    @Test fun oldestSnapshotsAreEvictedAtConfiguredCapacity() {
        val history = SongHistory(song, maxEntries = 2)
        for (gain in 1..4) history.edit { it.copy(gainDb = gain.toDouble()) }
        assertEquals(3.0, history.undo().gainDb, 0.0)
        assertEquals(2.0, history.undo().gainDb, 0.0)
        assertFalse(history.canUndo)
        assertEquals(3.0, history.redo().gainDb, 0.0)
        assertEquals(4.0, history.redo().gainDb, 0.0)
        assertFalse(history.canRedo)
    }

    @Test fun resetStartsANewSongAndClearsBothStacks() {
        val history = SongHistory(song)
        history.edit { it.copy(gainDb = 1.0) }
        history.undo()
        val other = song.copy(id = "other")
        assertEquals(other, history.reset(other))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(other, history.current)
    }

    @Test fun snapshotsAreNotChangedByTheCallersMutableList() {
        val initialSegments = mutableListOf(AudioSegment(0, 100))
        val history = SongHistory(song.copy(segments = initialSegments))
        initialSegments.clear()
        assertEquals(song, history.current)
        val editedSegments = mutableListOf(AudioSegment(20, 60))
        history.commit(song.copy(segments = editedSegments))
        editedSegments.clear()
        assertEquals(listOf(AudioSegment(20, 60)), history.current.segments)
        assertEquals(song, history.undo())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCapacityIsRejected() { SongHistory(song, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun committingAnotherSongIsRejected() { SongHistory(song).commit(song.copy(id = "other")) }
}
