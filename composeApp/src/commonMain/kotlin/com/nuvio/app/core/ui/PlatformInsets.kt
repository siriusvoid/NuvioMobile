package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets

/** Physical display-safe top inset, excluding any enclosing native toolbar. */
@Composable
internal expect fun platformPhysicalTopInset(): Dp

/**
 * The top inset this view reports moves under us. The system tab bar minimizes while a push is in
 * flight and expands again a beat after the pop, and a native bar a screen is pushed under does the
 * same, so a root screen reflows twice per visit to a detail page — on this iPad the reported inset
 * swings between 140dp and 86dp while the window's own stays at 32dp. Hold the widest inset seen
 * for a given window inset instead, and lay out against the expanded chrome throughout; the window
 * inset is the one thing a transition never perturbs, so a rotation still resets the reading.
 */
@Composable
internal fun nuvioStableTopInset(): Dp {
    val reported = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val widest = remember(platformPhysicalTopInset()) { mutableStateOf(reported) }
    SideEffect {
        if (reported > widest.value) widest.value = reported
    }
    return maxOf(widest.value, reported)
}

internal val LocalNuvioBottomNavigationOverlayPadding = staticCompositionLocalOf { 0.dp }

/** True only inside the tab host, where the system tab bar is actually on screen. */
internal val LocalNuvioSystemTabBarActive = staticCompositionLocalOf { false }

/**
 * Trimmed off the reported top inset while the floating tab bar is up. The system reserves more
 * room than the bar visually occupies, and the surplus reads as dead space above every tab title.
 */
internal val NuvioFloatingTabBarTopTrim: Dp = 48.dp

/** CompositionLocal providing the shared [NuvioNavBarScrollState] so child screens can attach the nestedScrollConnection. */
val LocalNuvioNavBarScrollState = staticCompositionLocalOf<NuvioNavBarScrollState?> { null }

@Composable
internal fun nuvioSafeBottomPadding(extra: Dp = 0.dp): Dp {
	val navigationBarBottom = nuvioBottomNavigationBarInsets()
		.asPaddingValues()
		.calculateBottomPadding()
	return navigationBarBottom.coerceAtLeast(nuvioPlatformExtraBottomPadding) +
		LocalNuvioBottomNavigationOverlayPadding.current +
		extra
}
