package com.pocketfx.ui.converter

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

private const val TargetKeyPrefix = "target-"
internal fun targetKey(code: String) = TargetKeyPrefix + code

internal class TargetDragState(private val list: LazyListState) {
    private data class Drag(val code: String, val height: Int, val grabOffset: Float, val initial: List<String>)

    private var drag by mutableStateOf<Drag?>(null)
    private var fingerY by mutableFloatStateOf(0f)
    private var hasMoved = false
    private var activeOrder by mutableStateOf<List<String>?>(null)
    private var pendingOrder by mutableStateOf<List<String>?>(null)

    val draggedCode: String? get() = drag?.code
    val order: List<String>? get() = activeOrder ?: pendingOrder

    fun targetAt(y: Float): String? {
        val layout = list.layoutInfo
        val itemY = y + layout.viewportStartOffset
        return layout.visibleItemsInfo.firstOrNull { it.code != null && itemY >= it.offset && itemY < it.offset + it.size }?.code
    }

    fun start(code: String, y: Float, saved: List<String>): Boolean {
        val displayed = order ?: saved
        if (displayed.size < 2 || code !in displayed) return false
        val layout = list.layoutInfo
        val item = layout.visibleItemsInfo.find { it.key == targetKey(code) } ?: return false
        fingerY = y
        hasMoved = false
        drag = Drag(code, item.size, y + layout.viewportStartOffset - item.offset, displayed)
        activeOrder = displayed
        return true
    }

    fun moveFinger(y: Float) {
        if (y != fingerY) hasMoved = true
        fingerY = y
        updateOrder()
    }

    fun updateOrder() {
        val current = drag ?: return
        val displayed = activeOrder ?: return
        val info = list.layoutInfo
        val from = displayed.indexOf(current.code)
        val center = fingerY + info.viewportStartOffset - current.grabOffset + current.height / 2f
        var to = from
        for (item in info.visibleItemsInfo) {
            val code = item.code ?: continue
            val index = displayed.indexOf(code)
            val midpoint = item.offset + item.size / 2f
            if (index in 0 until from && center < midpoint) to = minOf(to, index)
            if (index > from && center > midpoint) to = maxOf(to, index)
        }
        if (to != from) activeOrder = displayed.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun translationY(): Float {
        val current = drag ?: return 0f
        val layout = list.layoutInfo
        val item = layout.visibleItemsInfo.find { it.key == targetKey(current.code) } ?: return 0f
        val first = layout.visibleItemsInfo.first { it.code != null }
        val last = layout.visibleItemsInfo.last { it.code != null }
        val start = maxOf(layout.viewportStartOffset, first.offset).toFloat()
        val end = maxOf(start, minOf(layout.viewportEndOffset, last.offset + last.size) - current.height.toFloat())
        return (fingerY + layout.viewportStartOffset - current.grabOffset).coerceIn(start, end) - item.offset
    }

    fun autoScrollSpeed(edgeSize: Float): Float {
        if (!hasMoved) return 0f
        val current = drag ?: return 0f
        val displayed = activeOrder ?: return 0f
        val layout = list.layoutInfo
        val finger = fingerY + layout.viewportStartOffset
        val area = minOf(edgeSize, (layout.viewportEndOffset - layout.viewportStartOffset) / 3f)
        if (area <= 0f) return 0f
        val topEdge = layout.viewportStartOffset + area
        val bottomEdge = layout.viewportEndOffset - area
        val intensity = when {
            finger < topEdge -> -(topEdge - finger) / area
            finger > bottomEdge -> (finger - bottomEdge) / area
            else -> return 0f
        }
        val edgeCode = if (intensity < 0f) displayed.first() else displayed.last()
        val boundary = layout.visibleItemsInfo.find { it.key == targetKey(edgeCode) }
        if (boundary != null) {
            if (intensity < 0f && boundary.offset >= layout.viewportStartOffset) return 0f
            if (intensity > 0f && boundary.offset + boundary.size <= layout.viewportEndOffset) return 0f
        }
        return intensity.coerceIn(-1f, 1f) * current.height * 12f
    }

    fun finish(): List<String>? {
        val initial = drag?.initial
        val final = activeOrder?.takeUnless { it == initial }
        if (final != null) pendingOrder = final
        cancel()
        return final
    }

    fun cancel() {
        drag = null
        activeOrder = null
    }

    fun clearPreview() {
        cancel()
        pendingOrder = null
    }

    fun reconcile(saved: List<String>, writeFailed: Boolean) {
        val displayed = order ?: return
        if (writeFailed || displayed.size != saved.size || displayed.toSet() != saved.toSet()) {
            clearPreview()
        } else if (pendingOrder == saved) {
            pendingOrder = null
        }
    }

    private val LazyListItemInfo.code: String?
        get() = (key as? String)?.takeIf { it.startsWith(TargetKeyPrefix) }?.removePrefix(TargetKeyPrefix)
}

internal fun Modifier.targetReorderGestures(
    state: TargetDragState,
    savedTargets: () -> List<String>,
    onStart: () -> Unit,
    onDrop: (List<String>) -> Unit,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The amount and menu own their initial touch. Only unclaimed touches may start a reorder.
        val code = state.targetAt(down.position.y)
        if (down.isConsumed || code == null) return@awaitEachGesture
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!state.start(code, held.position.y, savedTargets())) return@awaitEachGesture
        onStart()
        try {
            // After a long press, claim movement before LazyColumn can treat it as a scroll.
            while (state.draggedCode != null) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == held.id } ?: break
                if (change.isConsumed) break
                if (!change.pressed) {
                    change.consume()
                    state.finish()?.let(onDrop)
                    break
                }
                if (change.position != change.previousPosition) {
                    change.consume()
                    state.moveFinger(change.position.y)
                }
            }
        } finally {
            state.cancel()
        }
    }
}
