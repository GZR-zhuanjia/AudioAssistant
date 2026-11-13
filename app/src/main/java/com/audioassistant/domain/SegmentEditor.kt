package com.audioassistant.domain

/**
 * Source edits never change the source file. Retained intervals are chronological,
 * non-overlapping and half-open. Adjacent intervals remain separate so a split is
 * represented even before any audio is removed.
 */
object SegmentEditor {
    /** Clamp to the source, discard empty/reversed intervals and merge overlaps. */
    fun normalize(segments: List<AudioSegment>, durationUs: Long): List<AudioSegment> {
        val clipped = clip(segments, durationUs)
        if (clipped.isEmpty()) return emptyList()
        return splitCoverage(mergeCoverage(clipped), joins(clipped))
    }

    /** Delete a source range from the retained audio; reversed selections are accepted. */
    fun delete(
        segments: List<AudioSegment>,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
    ): List<AudioSegment> {
        val retained = normalize(segments, durationUs)
        val selection = selection(startUs, endUs, durationUs)
        if (selection.startUs == selection.endUs) return retained
        return retained.flatMap { segment ->
            when {
                segment.endUs <= selection.startUs || segment.startUs >= selection.endUs ->
                    listOf(segment)
                else -> buildList {
                    if (segment.startUs < selection.startUs) {
                        add(AudioSegment(segment.startUs, selection.startUs))
                    }
                    if (segment.endUs > selection.endUs) {
                        add(AudioSegment(selection.endUs, segment.endUs))
                    }
                }
            }
        }
    }

    /** Keep the intersection of a source selection and the currently retained audio. */
    fun keep(
        segments: List<AudioSegment>,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
    ): List<AudioSegment> {
        val selection = selection(startUs, endUs, durationUs)
        return normalize(segments, durationUs).mapNotNull { segment ->
            val start = maxOf(segment.startUs, selection.startUs)
            val end = minOf(segment.endUs, selection.endUs)
            if (start < end) AudioSegment(start, end) else null
        }
    }

    /**
     * Restore source audio, joining across deleted gaps while retaining existing
     * explicit split boundaries. Restoring the entire source also works after all
     * retained audio has been deleted.
     */
    fun restore(
        segments: List<AudioSegment>,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
    ): List<AudioSegment> {
        val retained = normalize(segments, durationUs)
        val selection = selection(startUs, endUs, durationUs)
        if (selection.startUs == selection.endUs) return retained
        return splitCoverage(mergeCoverage(retained + selection), joins(retained))
    }

    /** Split retained source audio at a source timestamp; a point in a gap is a no-op. */
    fun split(
        segments: List<AudioSegment>,
        atUs: Long,
        durationUs: Long,
    ): List<AudioSegment> = normalize(segments, durationUs).flatMap { segment ->
        if (atUs > segment.startUs && atUs < segment.endUs) {
            listOf(AudioSegment(segment.startUs, atUs), AudioSegment(atUs, segment.endUs))
        } else {
            listOf(segment)
        }
    }

    /** Total retained duration, counting overlapping input only once. */
    fun editedDurationUs(segments: List<AudioSegment>): Long =
        normalize(segments, Long.MAX_VALUE).sumOf { it.endUs - it.startUs }

    /**
     * Map a retained source point to the edited timeline. Gap interiors return
     * null. Both edges of a deleted gap map to the same edited join position.
     */
    fun sourceToEditedUs(segments: List<AudioSegment>, sourceUs: Long): Long? {
        var offset = 0L
        for (segment in normalize(segments, Long.MAX_VALUE)) {
            if (sourceUs >= segment.startUs && sourceUs <= segment.endUs) {
                return offset + sourceUs - segment.startUs
            }
            offset += segment.endUs - segment.startUs
        }
        return null
    }

    /**
     * Map an edited position to source time. At a join choose the next segment's
     * start, except that the final edited endpoint maps to the final source end.
     */
    fun editedToSourceUs(segments: List<AudioSegment>, editedUs: Long): Long? {
        if (editedUs < 0) return null
        val retained = normalize(segments, Long.MAX_VALUE)
        var offset = 0L
        for (segment in retained) {
            val next = offset + segment.endUs - segment.startUs
            if (editedUs < next) return segment.startUs + (editedUs - offset)
            offset = next
        }
        return if (retained.isNotEmpty() && editedUs == offset) retained.last().endUs else null
    }

    /** Source intervals corresponding to a range on the edited timeline. */
    fun editedRangeToSource(
        segments: List<AudioSegment>,
        startUs: Long,
        endUs: Long,
    ): List<AudioSegment> {
        val retained = normalize(segments, Long.MAX_VALUE)
        val selection = selection(startUs, endUs, editedDurationUs(retained))
        var offset = 0L
        return buildList {
            for (segment in retained) {
                val next = offset + segment.endUs - segment.startUs
                val start = maxOf(offset, selection.startUs)
                val end = minOf(next, selection.endUs)
                if (start < end) {
                    add(AudioSegment(segment.startUs + (start - offset), segment.startUs + (end - offset)))
                }
                offset = next
            }
        }
    }

    fun splitAtEditedUs(
        segments: List<AudioSegment>,
        atUs: Long,
        durationUs: Long,
    ): List<AudioSegment> {
        val retained = normalize(segments, durationUs)
        val sourceUs = editedToSourceUs(retained, atUs) ?: return retained
        return split(retained, sourceUs, durationUs)
    }

    fun deleteEditedRange(
        segments: List<AudioSegment>,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
    ): List<AudioSegment> {
        var retained = normalize(segments, durationUs)
        val sourceRanges = editedRangeToSource(retained, startUs, endUs)
        for (range in sourceRanges) retained = delete(retained, range.startUs, range.endUs, durationUs)
        return retained
    }

    private fun selection(startUs: Long, endUs: Long, durationUs: Long): AudioSegment {
        val duration = durationUs.coerceAtLeast(0)
        val start = minOf(startUs, endUs).coerceIn(0, duration)
        val end = maxOf(startUs, endUs).coerceIn(0, duration)
        return AudioSegment(start, end)
    }

    private fun clip(segments: List<AudioSegment>, durationUs: Long): List<AudioSegment> {
        if (durationUs <= 0) return emptyList()
        return segments.mapNotNull { segment ->
            val start = segment.startUs.coerceIn(0, durationUs)
            val end = segment.endUs.coerceIn(0, durationUs)
            if (start < end) AudioSegment(start, end) else null
        }.sortedWith(compareBy(AudioSegment::startUs, AudioSegment::endUs))
    }

    private fun joins(segments: List<AudioSegment>): Set<Long> {
        val starts = segments.mapTo(HashSet()) { it.startUs }
        return segments.mapNotNullTo(HashSet()) { it.endUs.takeIf(starts::contains) }
    }

    /** Merge touching coverage here; explicit joins are reinserted separately. */
    private fun mergeCoverage(segments: List<AudioSegment>): List<AudioSegment> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedWith(compareBy(AudioSegment::startUs, AudioSegment::endUs))
        return buildList {
            var current = sorted.first()
            for (next in sorted.drop(1)) {
                if (next.startUs <= current.endUs) {
                    current = AudioSegment(current.startUs, maxOf(current.endUs, next.endUs))
                } else {
                    add(current)
                    current = next
                }
            }
            add(current)
        }
    }

    private fun splitCoverage(coverage: List<AudioSegment>, boundaries: Set<Long>): List<AudioSegment> {
        val sortedBoundaries = boundaries.sorted()
        var index = 0
        return buildList {
            for (segment in coverage) {
                var start = segment.startUs
                while (index < sortedBoundaries.size && sortedBoundaries[index] <= start) index++
                while (index < sortedBoundaries.size && sortedBoundaries[index] < segment.endUs) {
                    val boundary = sortedBoundaries[index++]
                    add(AudioSegment(start, boundary))
                    start = boundary
                }
                add(AudioSegment(start, segment.endUs))
            }
        }
    }
}
