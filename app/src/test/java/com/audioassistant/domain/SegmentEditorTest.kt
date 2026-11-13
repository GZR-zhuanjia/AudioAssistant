package com.audioassistant.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentEditorTest {
    private fun segment(start: Long, end: Long) = AudioSegment(start, end)
    private val full = listOf(segment(0, 100))

    @Test fun normalizationClampsSortsAndDropsEmptyOrReversedIntervals() {
        assertEquals(
            listOf(segment(0, 20), segment(40, 100)),
            SegmentEditor.normalize(
                listOf(segment(60, 110), segment(-50, 20), segment(40, 80), segment(5, 5), segment(30, 25)),
                100,
            ),
        )
    }

    @Test fun normalizationPreservesExplicitSplits() {
        val split = listOf(segment(0, 30), segment(30, 60), segment(60, 100))
        assertEquals(split, SegmentEditor.normalize(split.reversed(), 100))
        assertEquals(split, SegmentEditor.normalize(split + segment(10, 90), 100))
        assertEquals(split, SegmentEditor.normalize(split + split, 100))
    }

    @Test fun zeroAndNegativeSourceDurationAreEmpty() {
        assertTrue(SegmentEditor.normalize(full, 0).isEmpty())
        assertTrue(SegmentEditor.normalize(full, -1).isEmpty())
        assertTrue(SegmentEditor.restore(full, 0, 100, -1).isEmpty())
        assertTrue(SegmentEditor.keep(full, 0, 100, 0).isEmpty())
    }

    @Test fun deleteMiddleCreatesTwoRetainedIntervals() {
        assertEquals(listOf(segment(0, 20), segment(70, 100)), SegmentEditor.delete(full, 20, 70, 100))
    }

    @Test fun deleteAtEdgesAndOutsideSource() {
        assertEquals(listOf(segment(30, 100)), SegmentEditor.delete(full, -50, 30, 100))
        assertEquals(listOf(segment(0, 80)), SegmentEditor.delete(full, 80, 200, 100))
        assertEquals(full, SegmentEditor.delete(full, 120, 200, 100))
        assertEquals(full, SegmentEditor.delete(full, -20, -10, 100))
        assertTrue(SegmentEditor.delete(full, Long.MIN_VALUE, Long.MAX_VALUE, 100).isEmpty())
    }

    @Test fun reversedSelectionsHaveTheSameEffect() {
        assertEquals(SegmentEditor.delete(full, 20, 60, 100), SegmentEditor.delete(full, 60, 20, 100))
        assertEquals(SegmentEditor.keep(full, 20, 60, 100), SegmentEditor.keep(full, 60, 20, 100))
        assertEquals(SegmentEditor.restore(emptyList(), 20, 60, 100), SegmentEditor.restore(emptyList(), 60, 20, 100))
    }

    @Test fun emptySelectionIsANoopForDeleteAndRestoreAndEmptyForKeep() {
        assertEquals(full, SegmentEditor.delete(full, 30, 30, 100))
        assertEquals(full, SegmentEditor.restore(full, 30, 30, 100))
        assertTrue(SegmentEditor.keep(full, 30, 30, 100).isEmpty())
    }

    @Test fun deletingAcrossASplitKeepsTheSurvivingSides() {
        val split = listOf(segment(0, 25), segment(25, 50), segment(50, 100))
        assertEquals(listOf(segment(0, 25), segment(25, 35), segment(80, 100)), SegmentEditor.delete(split, 35, 80, 100))
        assertEquals(split, SegmentEditor.delete(split, 25, 25, 100))
        assertEquals(listOf(segment(0, 25), segment(50, 100)), SegmentEditor.delete(split, 25, 50, 100))
    }

    @Test fun deletingAGapDoesNotChangeAudio() {
        val retained = listOf(segment(0, 20), segment(70, 100))
        assertEquals(retained, SegmentEditor.delete(retained, 30, 60, 100))
    }

    @Test fun keepIntersectsExistingAudioAndPreservesSplits() {
        val retained = listOf(segment(0, 20), segment(40, 60), segment(60, 100))
        assertEquals(listOf(segment(10, 20), segment(40, 60), segment(60, 80)), SegmentEditor.keep(retained, 10, 80, 100))
        assertTrue(SegmentEditor.keep(retained, 25, 35, 100).isEmpty())
        assertEquals(retained, SegmentEditor.keep(retained, -100, 1_000, 100))
    }

    @Test fun restoreFillsGapWithoutRemovingUnrelatedExplicitSplits() {
        val retained = listOf(segment(0, 10), segment(10, 20), segment(70, 100))
        assertEquals(listOf(segment(0, 10), segment(10, 100)), SegmentEditor.restore(retained, 20, 70, 100))
        assertEquals(listOf(segment(0, 10), segment(10, 100)), SegmentEditor.restore(retained, 5, 90, 100))
    }

    @Test fun restoreCanRecoverACompletelyDeletedSong() {
        assertEquals(full, SegmentEditor.restore(emptyList(), -1, 200, 100))
        assertEquals(listOf(segment(30, 50)), SegmentEditor.restore(emptyList(), 30, 50, 100))
    }

    @Test fun restoreDisjointAudioLeavesGaps() {
        val retained = listOf(segment(0, 10))
        assertEquals(listOf(segment(0, 10), segment(30, 40)), SegmentEditor.restore(retained, 30, 40, 100))
    }

    @Test fun splitIsIdempotentAndSurvivesNormalizationAndKeep() {
        val split = SegmentEditor.split(full, 40, 100)
        assertEquals(listOf(segment(0, 40), segment(40, 100)), split)
        assertEquals(split, SegmentEditor.split(split, 40, 100))
        assertEquals(split, SegmentEditor.normalize(split, 100))
        assertEquals(split, SegmentEditor.keep(split, 0, 100, 100))
    }

    @Test fun splittingOutsideAudioOrAtAnEndpointIsANoop() {
        val retained = listOf(segment(10, 30), segment(60, 90))
        for (point in listOf(-1L, 0L, 10L, 30L, 45L, 60L, 90L, 100L, Long.MAX_VALUE)) {
            assertEquals("split at $point", retained, SegmentEditor.split(retained, point, 100))
        }
    }

    @Test fun sourceAndEditedMappingCrossDeletedGaps() {
        val retained = listOf(segment(10, 30), segment(60, 90))
        assertEquals(0L, SegmentEditor.sourceToEditedUs(retained, 10))
        assertEquals(15L, SegmentEditor.sourceToEditedUs(retained, 25))
        assertEquals(20L, SegmentEditor.sourceToEditedUs(retained, 30))
        assertEquals(20L, SegmentEditor.sourceToEditedUs(retained, 60))
        assertEquals(50L, SegmentEditor.sourceToEditedUs(retained, 90))
        assertNull(SegmentEditor.sourceToEditedUs(retained, 45))
        assertNull(SegmentEditor.sourceToEditedUs(retained, 9))
        assertNull(SegmentEditor.sourceToEditedUs(retained, 91))
        assertEquals(10L, SegmentEditor.editedToSourceUs(retained, 0))
        assertEquals(29L, SegmentEditor.editedToSourceUs(retained, 19))
        assertEquals(60L, SegmentEditor.editedToSourceUs(retained, 20))
        assertEquals(90L, SegmentEditor.editedToSourceUs(retained, 50))
        assertNull(SegmentEditor.editedToSourceUs(retained, -1))
        assertNull(SegmentEditor.editedToSourceUs(retained, 51))
    }

    @Test fun emptyAudioHasNoMappablePositions() {
        assertNull(SegmentEditor.editedToSourceUs(emptyList(), 0))
        assertNull(SegmentEditor.sourceToEditedUs(emptyList(), 0))
        assertEquals(0L, SegmentEditor.editedDurationUs(emptyList()))
        assertTrue(SegmentEditor.editedRangeToSource(emptyList(), 0, 100).isEmpty())
    }

    @Test fun editedSelectionCanCrossSeveralSourceIntervals() {
        val retained = listOf(segment(10, 30), segment(60, 70), segment(70, 90))
        assertEquals(listOf(segment(25, 30), segment(60, 70), segment(70, 80)), SegmentEditor.editedRangeToSource(retained, 15, 40))
        assertEquals(listOf(segment(25, 30), segment(60, 70), segment(70, 80)), SegmentEditor.editedRangeToSource(retained, 40, 15))
        assertEquals(retained, SegmentEditor.editedRangeToSource(retained, -100, 1_000))
        assertTrue(SegmentEditor.editedRangeToSource(retained, 20, 20).isEmpty())
    }

    @Test fun editedDeleteAndSplitUseCompressedTimeline() {
        val retained = listOf(segment(10, 30), segment(60, 90))
        assertEquals(listOf(segment(10, 25), segment(80, 90)), SegmentEditor.deleteEditedRange(retained, 15, 40, 100))
        assertEquals(listOf(segment(10, 30), segment(60, 70), segment(70, 90)), SegmentEditor.splitAtEditedUs(retained, 30, 100))
        assertEquals(retained, SegmentEditor.splitAtEditedUs(retained, 20, 100))
        assertEquals(retained, SegmentEditor.splitAtEditedUs(retained, 500, 100))
    }

    @Test fun overlappingSegmentsAreNotDoubleCounted() {
        assertEquals(80L, SegmentEditor.editedDurationUs(listOf(segment(0, 50), segment(20, 80))))
        assertEquals(100L, SegmentEditor.editedDurationUs(listOf(segment(0, 50), segment(50, 100))))
    }

    @Test fun largeMicrosecondValuesDoNotOverflow() {
        val max = Long.MAX_VALUE
        val retained = listOf(segment(0, 10), segment(max - 20, max))
        assertEquals(30L, SegmentEditor.editedDurationUs(retained))
        assertEquals(25L, SegmentEditor.sourceToEditedUs(retained, max - 5))
        assertEquals(max - 5, SegmentEditor.editedToSourceUs(retained, 25))
        assertEquals(max, SegmentEditor.editedDurationUs(listOf(segment(0, max))))
        assertEquals(listOf(segment(0, max)), SegmentEditor.normalize(listOf(segment(Long.MIN_VALUE, max)), max))
        assertEquals(listOf(segment(0, max - 1)), SegmentEditor.delete(listOf(segment(0, max)), max - 1, max, max))
    }

    @Test fun randomEditsAgreeWithAnIndependentSampleCoverageOracle() {
        val random = Random(7_945)
        val duration = 40L
        repeat(500) {
            val raw = List(random.nextInt(0, 15)) {
                segment(random.nextLong(-10, 51), random.nextLong(-10, 51))
            }
            val expected = (0L until duration).filterTo(mutableSetOf()) { point ->
                raw.any { it.startUs <= point && point < it.endUs }
            }
            val normalized = SegmentEditor.normalize(raw, duration)
            assertEquals(expected, samples(normalized))
            assertEquals(normalized, SegmentEditor.normalize(normalized, duration))
            assertValid(normalized, duration)
            val a = random.nextLong(-20, 61)
            val b = random.nextLong(-20, 61)
            val selected = (minOf(a, b) until maxOf(a, b)).filterTo(mutableSetOf()) { it in 0 until duration }
            val deleted = SegmentEditor.delete(raw, a, b, duration)
            val kept = SegmentEditor.keep(raw, a, b, duration)
            val restored = SegmentEditor.restore(raw, a, b, duration)
            assertEquals(expected - selected, samples(deleted))
            assertEquals(expected.intersect(selected), samples(kept))
            assertEquals(expected + selected, samples(restored))
            assertValid(deleted, duration)
            assertValid(kept, duration)
            assertValid(restored, duration)
            val split = SegmentEditor.split(raw, a, duration)
            assertEquals(expected, samples(split))
            assertValid(split, duration)
            assertEquals(expected.size.toLong(), SegmentEditor.editedDurationUs(normalized))
            val selectedByEdited = expected.sorted().filterIndexed { index, _ ->
                index.toLong() >= minOf(a, b) && index.toLong() < maxOf(a, b)
            }.toSet()
            assertEquals(selectedByEdited, samples(SegmentEditor.editedRangeToSource(normalized, a, b)))
            val editedDeletion = SegmentEditor.deleteEditedRange(raw, a, b, duration)
            assertEquals(expected - selectedByEdited, samples(editedDeletion))
            assertValid(editedDeletion, duration)
            expected.sorted().forEachIndexed { edited, source ->
                assertEquals(source, SegmentEditor.editedToSourceUs(normalized, edited.toLong()))
                assertEquals(edited.toLong(), SegmentEditor.sourceToEditedUs(normalized, source))
            }
        }
    }

    private fun samples(segments: List<AudioSegment>): Set<Long> =
        segments.flatMap { (it.startUs until it.endUs).toList() }.toSet()

    private fun assertValid(segments: List<AudioSegment>, durationUs: Long) {
        segments.forEach {
            assertTrue(it.startUs >= 0)
            assertTrue(it.endUs <= durationUs)
            assertTrue(it.startUs < it.endUs)
        }
        segments.zipWithNext().forEach { (left, right) -> assertFalse(left.endUs > right.startUs) }
    }
}
