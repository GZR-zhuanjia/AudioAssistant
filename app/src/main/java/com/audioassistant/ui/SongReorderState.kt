package com.audioassistant.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

internal fun songListKey(id: String) = "song:$id"

/** Order is provisional until release. Cancellation leaves the saved project
 * untouched, and autosave runs once for a completed drag, rather than every pixel. */
@Stable
internal class SongReorderState(
    private val list: LazyListState,
    private val commit: (List<String>) -> Unit,
) {
    var activeId: String? by mutableStateOf(null)
        private set
    var orderedIds: List<String>? by mutableStateOf(null)
        private set
    private var originalIds = emptyList<String>()
    private var desiredTop by mutableStateOf(0f)
    private var itemHeight = 0

    val isDragging get() = activeId != null

    fun begin(id: String, ids: List<String>): Boolean {
        if (isDragging || ids.size < 2 || id !in ids) return false
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == songListKey(id) } ?: return false
        originalIds = ids.toList()
        orderedIds = originalIds
        desiredTop = item.offset.toFloat()
        itemHeight = item.size
        activeId = id
        return true
    }

    fun dragBy(deltaY: Float) {
        if (!isDragging || !deltaY.isFinite()) return
        val layout = list.layoutInfo
        // Keep the card center near the viewport, including rows taller than it.
        // A partially visible starting row may already exceed these bounds: let
        // it move inward naturally instead of snapping on the first drag delta.
        val lower = minOf(layout.viewportStartOffset - itemHeight / 2f, desiredTop)
        val upper = maxOf(layout.viewportEndOffset - itemHeight / 2f, desiredTop)
        desiredTop = (desiredTop + deltaY).coerceIn(lower, upper)
        moveIfNeeded()
    }

    fun translationFor(id: String): Float {
        if (id != activeId) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == songListKey(id) } ?: return 0f
        return desiredTop - item.offset
    }

    fun moveIfNeeded() {
        val id = activeId ?: return
        val order = orderedIds ?: return
        val visible = list.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val songId = (item.key as? String)?.takeIf { it.startsWith("song:") }?.removePrefix("song:")
            songId?.takeIf { it in order }?.let { it to item }
        }.sortedBy { it.second.offset }
        val visibleIds = visible.map { it.first }
        // State updates precede LazyColumn remeasurement. Wait for its new key
        // order before crossing again, otherwise stale positions reverse a move.
        if (visibleIds != order.filter { it in visibleIds }) return
        val source = visible.firstOrNull { it.first == id }?.second ?: return
        val sourceIndex = order.indexOf(id)
        val center = desiredTop + itemHeight / 2f
        val target = if (center > source.center) {
            visible.lastOrNull { (otherId, item) -> order.indexOf(otherId) > sourceIndex && center > item.center }
        } else {
            visible.firstOrNull { (otherId, item) -> order.indexOf(otherId) < sourceIndex && center < item.center }
        } ?: return
        val next = order.toMutableList()
        next.removeAt(sourceIndex)
        next.add(order.indexOf(target.first), id)
        val anchorIndex = list.firstVisibleItemIndex
        val anchorOffset = list.firstVisibleItemScrollOffset
        orderedIds = next
        // A stable-key list normally follows its first visible key when that key
        // moves. During a drag this would jump the viewport away from the finger.
        list.requestScrollToItem(anchorIndex, anchorOffset)
    }

    fun scrollDistance(edge: Float, maxStep: Float): Float {
        val id = activeId ?: return 0f
        val order = orderedIds ?: return 0f
        val index = order.indexOf(id)
        val layout = list.layoutInfo
        val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
        val zone = minOf(edge, viewportHeight / 2f)
        if (zone <= 0f) return 0f
        // Non-overlapping center zones also work in short multiwindow viewports.
        val center = desiredTop + itemHeight / 2f
        val topPenetration = layout.viewportStartOffset + zone - center
        val bottomPenetration = center - (layout.viewportEndOffset - zone)
        return when {
            index > 0 && topPenetration > 0 -> -maxStep * (topPenetration / zone).coerceIn(0f, 1f)
            index < order.lastIndex && bottomPenetration > 0 -> maxStep * (bottomPenetration / zone).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun finish() {
        val next = orderedIds
        val changed = next != null && next != originalIds
        cancel()
        if (changed) commit(next!!)
    }

    fun cancel() { activeId = null; orderedIds = null; originalIds = emptyList() }
    fun cancelIfChanged(ids: List<String>) { if (isDragging && ids != originalIds) cancel() }

    private val LazyListItemInfo.center get() = offset + size / 2f
}

@Composable
internal fun rememberSongReorderState(list: LazyListState, onCommit: (List<String>) -> Unit): SongReorderState {
    val currentCommit by rememberUpdatedState(onCommit)
    val state = remember(list) { SongReorderState(list) { currentCommit(it) } }
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxStep = with(density) { 12.dp.toPx() }
    LaunchedEffect(state.activeId) {
        if (state.isDragging) {
            list.stopScroll()
            while (isActive && state.isDragging) {
                withFrameNanos { }
                val distance = state.scrollDistance(edge, maxStep)
                if (distance != 0f) list.scrollBy(distance)
                // Scrolling changes content coordinates even with a stationary finger.
                state.moveIfNeeded()
            }
        }
    }
    return state
}
