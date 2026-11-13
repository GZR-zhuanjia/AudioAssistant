package com.audioassistant.domain

/** Bounded, per-song undo/redo. Snapshots copy segment lists to prevent mutable-list aliasing. */
class SongHistory(initial: SongItem, private val maxEntries: Int = 100) {
    private val past = ArrayDeque<SongItem>()
    private val future = ArrayDeque<SongItem>()

    var current: SongItem = snapshot(initial)
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    init {
        require(maxEntries > 0) { "History must retain at least one edit" }
    }

    fun commit(song: SongItem): SongItem {
        require(song.id == current.id) { "Use reset to switch the history to another song" }
        val next = snapshot(song)
        if (next == current) return current
        past.addLast(current)
        while (past.size > maxEntries) past.removeFirst()
        future.clear()
        current = next
        return current
    }

    fun edit(transform: (SongItem) -> SongItem): SongItem = commit(transform(current))

    fun undo(): SongItem {
        if (past.isEmpty()) return current
        future.addLast(current)
        current = past.removeLast()
        return current
    }

    fun redo(): SongItem {
        if (future.isEmpty()) return current
        past.addLast(current)
        current = future.removeLast()
        return current
    }

    fun reset(song: SongItem): SongItem {
        past.clear()
        future.clear()
        current = snapshot(song)
        return current
    }

    private fun snapshot(song: SongItem): SongItem = song.copy(segments = song.segments.toList())
}
