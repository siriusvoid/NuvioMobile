package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.random.Random

/**
 * Publishes the regions that must not start an interactive back swipe.
 *
 * iOS 26's swipe-back-from-anywhere gesture lives on the navigation controller, so it
 * cannot be scoped to a view. Compose draws into a single UIView with no UIScrollView for
 * UIKit to defer to, which means a horizontal rail would pop the screen instead of
 * scrolling. Reporting the rails' bounds lets the iOS side reject touches that begin
 * inside them while leaving the rest of the screen swipeable.
 *
 * Rectangles are published in density-independent units, which map 1:1 to iOS points.
 */
internal expect fun publishSwipeBackExclusionRects(encoded: String)

/**
 * Identifies the host that owns the current composition.
 *
 * Native navigation gives every route its own Compose host, and each one reports bounds
 * in its own coordinate space. Tagging rectangles with their host lets a screen apply
 * only its own regions instead of inheriting the ones a screen further down the stack
 * published and has not disposed - a details screen's episode rail would otherwise keep
 * a matching band of the streams screen above it unswipeable. Empty means "every host",
 * which is what the single-host fallback wants.
 */
internal val LocalSwipeBackExclusionOwner = staticCompositionLocalOf { "" }

internal object SwipeBackExclusionRegistry {
    private val regions = mutableMapOf<String, String>()

    fun update(key: String, encodedRect: String?) {
        val changed = if (encodedRect == null) {
            regions.remove(key) != null
        } else {
            regions.put(key, encodedRect) != encodedRect
        }
        if (changed) publish()
    }

    private fun publish() {
        publishSwipeBackExclusionRects(regions.values.joinToString(separator = ";"))
    }
}

/**
 * Marks this element as a region where an interactive back swipe must not begin.
 * Apply to horizontally scrolling content that would otherwise fight the gesture.
 */
@Composable
internal fun Modifier.excludeFromSwipeBack(): Modifier {
    val key = remember { "swipe-back-exclusion-${Random.nextLong().toULong().toString(16)}" }
    val owner = LocalSwipeBackExclusionOwner.current
    val density = LocalDensity.current

    DisposableEffect(key) {
        onDispose { SwipeBackExclusionRegistry.update(key, null) }
    }

    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        if (bounds.width <= 0f || bounds.height <= 0f) {
            SwipeBackExclusionRegistry.update(key, null)
            return@onGloballyPositioned
        }
        with(density) {
            val rect = listOf(
                bounds.left.toDp().value,
                bounds.top.toDp().value,
                bounds.width.toDp().value,
                bounds.height.toDp().value,
            ).joinToString(separator = ",")
            SwipeBackExclusionRegistry.update(key, "$owner|$rect")
        }
    }
}
