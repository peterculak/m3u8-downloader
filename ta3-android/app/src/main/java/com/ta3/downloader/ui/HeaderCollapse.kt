package com.ta3.downloader.ui

import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * Collapse-on-scroll behaviour for the app header: scrolling content up slides the header away;
 * it comes back when the user drags down on the sticky bar below it, or keeps dragging down past the top of the content. Only active while [enabled].
 */
class HeaderCollapse : NestedScrollConnection {
    /** 0 = fully shown, -height = fully hidden. */
    var offset by mutableFloatStateOf(0f)
    var height = 0f
    var enabled = false

    fun reset() { offset = 0f }

    // Scrolling content up (finger up) hides the header straight away. Scrolling back down does NOT
    // bring it back — dragging down on the sticky search/chips bar does (see revealHeaderOnDrag), and so does
    // dragging down once the content is already at its top (onPostScroll).
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || height <= 0f || available.y >= 0f) return Offset.Zero
        val new = (offset + available.y).coerceIn(-height, 0f)
        val consumed = new - offset
        offset = new
        return Offset(0f, consumed)
    }

    // At the very top of the content: if the user keeps dragging down past the first item, pull the
    // header back in too. (Flings are ignored so a fast flick to the top doesn't pop it open.)
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || height <= 0f || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
        val new = (offset + available.y).coerceIn(-height, 0f)
        val used = new - offset
        offset = new
        return Offset(0f, used)
    }

    /** Slide the header fully back in. */
    suspend fun reveal() {
        if (offset != 0f) animate(offset, 0f) { v, _ -> offset = v }
    }

    /** Don't leave the header half-hidden: settle to the nearest end. */
    suspend fun settle() {
        if (enabled && offset != 0f && offset != -height) {
            val target = if (offset < -height / 2f) -height else 0f
            animate(offset, target) { v, _ -> offset = v }
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle()
        return Velocity.Zero
    }
}

/** Lays the content out at full height but only reserves the part not yet scrolled away. */
fun Modifier.collapsibleHeader(c: HeaderCollapse): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
        c.height = p.height.toFloat()
        val off = c.offset.roundToInt().coerceIn(-p.height, 0)
        layout(p.width, p.height + off) { p.placeRelative(0, off) }
    }

/** Scroll positions that outlive tab switches (owned by the ViewModel). */
val LocalScroll = androidx.compose.runtime.staticCompositionLocalOf { com.ta3.downloader.BrowseScroll() }

val LocalHeaderCollapse = androidx.compose.runtime.staticCompositionLocalOf { HeaderCollapse() }

/** Put on the sticky bar under the header: dragging it down pulls the main header back in (dragging up hides it). */
@androidx.compose.runtime.Composable
fun Modifier.revealHeaderOnDrag(): Modifier {
    val c = LocalHeaderCollapse.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.pointerInput(c) {
        detectVerticalDragGestures(
            onDragEnd = { scope.launch { c.settle() } },
            onDragCancel = { scope.launch { c.settle() } }
        ) { change, dy ->
            if (c.enabled && c.height > 0f) {
                change.consume()
                c.offset = (c.offset + dy).coerceIn(-c.height, 0f)
            }
        }
    }
}
