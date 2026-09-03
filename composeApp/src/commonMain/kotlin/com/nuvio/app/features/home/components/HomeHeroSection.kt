package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.heroStretchHeight
import com.nuvio.app.core.ui.heroStretchZoom
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val HERO_BACKGROUND_PARALLAX = 0.055f
private const val HERO_BACKGROUND_SCALE = 1.14f
private const val HERO_CONTENT_PARALLAX = 0.18f
private const val HERO_SCROLL_PARALLAX = 0.3f
private const val HERO_SCROLL_DOWN_SCALE_MULTIPLIER = 0.0001f
private const val HERO_SCROLL_UP_SCALE_MULTIPLIER = 0.002f
private const val HERO_SCROLL_MAX_SCALE = 1.3f
private const val HERO_SWIPE_THRESHOLD_FRACTION = 0.16f
private const val HERO_SWIPE_VELOCITY_THRESHOLD = 300f
private const val HERO_AUTO_SCROLL_INTERVAL_MS = 8_000L

// Long enough that ordinary jank does not read as an off-screen view.
private const val HERO_FRAME_WAIT_TIMEOUT_MS = 500L
private const val MOBILE_HERO_VIEWPORT_RATIO = 0.82f
private const val MOBILE_HERO_MIN_HEIGHT_DP = 360f
private const val MOBILE_HERO_MAX_HEIGHT_DP = 760f

internal data class HomeHeroLayout(
    val isTablet: Boolean,
    val heroHeight: Dp,
    val contentMaxWidth: Dp,
    val contentWidthFraction: Float,
    val contentHorizontalPadding: Dp,
    val contentVerticalPadding: Dp,
    val bottomFadeHeight: Dp,
    val logoWidthFraction: Float,
)

@Composable
fun HomeHeroSection(
    items: List<MetaPreview>,
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
    listState: LazyListState? = null,
    stretchPx: () -> Float = { 0f },
    onItemClick: ((MetaPreview) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val itemCount = items.size
    val pageCount = heroLoopPageCount(itemCount)
    val initialPage = remember(itemCount) { heroLoopInitialPage(itemCount) }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val coroutineScope = rememberCoroutineScope()
    val autoScrollPage = pagerState.currentPage

    // Keyed on the page so a manual swipe restarts the wait, but it re-arms itself rather than
    // relying on that key alone: the scroll below runs detached, and an interrupted one can leave
    // the pager parked between pages without ever moving currentPage. A single-shot effect would
    // then never fire again and the hero would sit there mid-slide for good.
    LaunchedEffect(autoScrollPage, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(HERO_AUTO_SCROLL_INTERVAL_MS)
            // The interval keeps running while the app is away, but the frame clock does not, and
            // a scroll animation started without one makes no progress at all — it lands parked
            // between pages, which is the freeze seen on reopening. A frame that does not arrive
            // promptly means this view is off screen, so skip the tick and come back round; the
            // wait stays bounded, because blocking here on a frame would be the same trap again.
            if (withTimeoutOrNull(HERO_FRAME_WAIT_TIMEOUT_MS) { withFrameNanos { } } == null) {
                continue
            }
            while (pagerState.isScrollInProgress) {
                delay(100L)
            }

            val nextPage = pagerState.currentPage + 1
            coroutineScope.launch {
                if (nextPage < pageCount) {
                    pagerState.animateScrollToPage(nextPage)
                } else {
                    pagerState.scrollToPage(heroLoopInitialPage(itemCount))
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .homeHeroPagerGesture(
                pagerState = pagerState,
                pageCount = pageCount,
                coroutineScope = coroutineScope,
            )
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
    ) {
        val layout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )
        val heroWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heroHeightPx = with(LocalDensity.current) { layout.heroHeight.toPx() }
        val scrollOffsetPx by remember(listState, heroHeightPx) {
            derivedStateOf {
                when {
                    listState == null -> 0f
                    listState.firstVisibleItemIndex > 0 -> heroHeightPx
                    else -> listState.firstVisibleItemScrollOffset.toFloat()
                }
            }
        }
        val heroScrollScale = heroBackgroundScrollScale(scrollOffsetPx)
        val heroScrollTranslationY = heroBackgroundScrollTranslationY(scrollOffsetPx)
        val currentPage = pagerState.currentPage
        val visiblePages = listOf(currentPage - 1, currentPage, currentPage + 1)
            .filter { page -> page in 0 until pageCount }
            .mapNotNull { page ->
                val pageOffset = heroPageOffset(pagerState, page)
                val visibility = (1f - abs(pageOffset)).coerceIn(0f, 1f)
                if (visibility <= 0f) {
                    null
                } else {
                    HeroPageLayer(
                        page = page,
                        itemIndex = heroFloorMod(page, itemCount),
                        visibility = visibility,
                        offset = pageOffset,
                    )
                }
            }
            .sortedBy(HeroPageLayer::visibility)
        val currentItem = visiblePages
            .lastOrNull()
            ?.itemIndex
            ?.let(items::get)
            ?: items[heroFloorMod(currentPage, itemCount)]

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heroStretchHeight(layout.heroHeight, stretchPx),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.01f },
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }

            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(layout.heroHeight)
                        .heroStretchZoom(stretchPx),
                ) {
                    visiblePages.forEach { layer ->
                        AsyncImage(
                            model = items[layer.itemIndex].banner ?: items[layer.itemIndex].poster,
                            contentDescription = items[layer.itemIndex].name,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_BACKGROUND_PARALLAX
                                    translationY = heroScrollTranslationY
                                    scaleX = HERO_BACKGROUND_SCALE * heroScrollScale
                                    scaleY = HERO_BACKGROUND_SCALE * heroScrollScale
                                },
                            alignment = if (layout.isTablet) Alignment.TopCenter else Alignment.Center,
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.02f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.12f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.34f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                                ),
                            ),
                        ),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(layout.bottomFadeHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            horizontal = layout.contentHorizontalPadding,
                            vertical = layout.contentVerticalPadding,
                        ),
                    horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(layout.contentWidthFraction)
                            .widthIn(max = layout.contentMaxWidth),
                        contentAlignment = if (layout.isTablet) Alignment.CenterStart else Alignment.Center,
                    ) {
                        visiblePages.forEach { layer ->
                            Box(
                                modifier = Modifier.graphicsLayer {
                                    alpha = layer.visibility
                                    translationX = -layer.offset * heroWidthPx * HERO_CONTENT_PARALLAX
                                },
                            ) {
                                HeroContentBlock(
                                    item = items[layer.itemIndex],
                                    layout = layout,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                    }

                    if (!layout.isTablet) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier
                                .clickable(enabled = onItemClick != null) {
                                    onItemClick?.invoke(currentItem)
                                },
                            color = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(40.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.home_view_details),
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (items.size > 1) {
                        Spacer(modifier = Modifier.height(if (layout.isTablet) 14.dp else 12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items.forEachIndexed { index, _ ->
                                val activeFraction = listOf(currentPage - 1, currentPage, currentPage + 1)
                                    .filter { page ->
                                        page in 0 until pageCount &&
                                            heroFloorMod(page, itemCount) == index
                                    }
                                    .maxOfOrNull { page -> heroPageVisibility(pagerState, page) }
                                    ?: 0f
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    heroNearestLoopPage(
                                                        fromPage = pagerState.currentPage,
                                                        targetItemIndex = index,
                                                        itemCount = itemCount,
                                                        pageCount = pageCount,
                                                    ),
                                                )
                                            }
                                        }
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onBackground)
                                        .graphicsLayer {
                                            alpha = 0.35f + (0.57f * activeFraction)
                                        }
                                        .width(8.dp + (24.dp * activeFraction))
                                        .height(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class HeroPageLayer(
    val page: Int,
    val itemIndex: Int,
    val visibility: Float,
    val offset: Float,
)

private fun heroPageOffset(
    pagerState: PagerState,
    page: Int,
): Float = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

private fun heroPageVisibility(
    pagerState: PagerState,
    page: Int,
): Float {
    return (1f - abs(heroPageOffset(pagerState, page))).coerceIn(0f, 1f)
}

@Composable
fun HomeHeroReservedSpace(
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
    ) {
        val layout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight),
        )
    }
}

@Composable
private fun HeroContentBlock(
    item: MetaPreview,
    layout: HomeHeroLayout,
    onItemClick: ((MetaPreview) -> Unit)?,
) {
    var logoLoadError by remember(item.type, item.id, item.logo) {
        mutableStateOf(false)
    }
    val logoUrl = item.logo?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (layout.isTablet) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxWidth(layout.logoWidthFraction)
                    .aspectRatio(2.6f)
                    .clickable(enabled = onItemClick != null) {
                        onItemClick?.invoke(item)
                    },
                alignment = if (layout.isTablet) Alignment.CenterStart else Alignment.Center,
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else {
            Text(
                text = item.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onItemClick != null) {
                        onItemClick?.invoke(item)
                    },
                style = if (layout.isTablet) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.displaySmall
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                textAlign = if (layout.isTablet) TextAlign.Start else TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun homeHeroLayout(
    maxWidthDp: Float,
    viewportHeightDp: Float? = null,
    mobileBelowSectionHeightHintDp: Float? = null,
): HomeHeroLayout =
    when {
        maxWidthDp >= 1200f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = (maxWidthDp * 0.42f).dp.coerceIn(360.dp, 440.dp),
            contentMaxWidth = 640.dp,
            contentWidthFraction = 0.56f,
            contentHorizontalPadding = 56.dp,
            contentVerticalPadding = 22.dp,
            bottomFadeHeight = 190.dp,
            logoWidthFraction = 0.58f,
        )
        maxWidthDp >= 840f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = (maxWidthDp * 0.46f).dp.coerceIn(340.dp, 420.dp),
            contentMaxWidth = 560.dp,
            contentWidthFraction = 0.62f,
            contentHorizontalPadding = 40.dp,
            contentVerticalPadding = 20.dp,
            bottomFadeHeight = 180.dp,
            logoWidthFraction = 0.56f,
        )
        maxWidthDp >= 600f -> HomeHeroLayout(
            isTablet = true,
            heroHeight = (maxWidthDp * 0.58f).dp.coerceIn(320.dp, 380.dp),
            contentMaxWidth = 520.dp,
            contentWidthFraction = 0.72f,
            contentHorizontalPadding = 32.dp,
            contentVerticalPadding = 18.dp,
            bottomFadeHeight = 170.dp,
            logoWidthFraction = 0.54f,
        )
        else -> HomeHeroLayout(
            isTablet = false,
            heroHeight = mobileHeroHeight(
                maxWidthDp = maxWidthDp,
                viewportHeightDp = viewportHeightDp,
                mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHintDp,
            ),
            contentMaxWidth = 480.dp,
            contentWidthFraction = 1f,
            contentHorizontalPadding = 24.dp,
            contentVerticalPadding = 16.dp,
            bottomFadeHeight = 220.dp,
            logoWidthFraction = 0.62f,
        )
    }

private fun mobileHeroHeight(
    maxWidthDp: Float,
    viewportHeightDp: Float?,
    mobileBelowSectionHeightHintDp: Float?,
): Dp {
    val viewportDrivenHeight = viewportHeightDp?.let { (it * MOBILE_HERO_VIEWPORT_RATIO).dp }
    val widthFallbackHeight = (maxWidthDp * 1.16f).dp
    val baseHeight = viewportDrivenHeight ?: widthFallbackHeight

    val maxAllowedFromViewportDp = if (viewportHeightDp != null && mobileBelowSectionHeightHintDp != null) {
        viewportHeightDp - mobileBelowSectionHeightHintDp
    } else {
        null
    }
    val cappedHeight = if (maxAllowedFromViewportDp != null) {
        val maxAllowedFromViewport = maxAllowedFromViewportDp.dp
        baseHeight.coerceAtMost(maxAllowedFromViewport)
    } else {
        baseHeight
    }
    val minHeight = if (maxAllowedFromViewportDp != null) {
        minOf(MOBILE_HERO_MIN_HEIGHT_DP, maxAllowedFromViewportDp.coerceAtLeast(0f)).dp
    } else {
        MOBILE_HERO_MIN_HEIGHT_DP.dp
    }

    return cappedHeight.coerceIn(minHeight, MOBILE_HERO_MAX_HEIGHT_DP.dp)
}

private fun heroBackgroundScrollScale(scrollOffsetPx: Float): Float {
    val scaleIncrease = if (scrollOffsetPx < 0f) {
        abs(scrollOffsetPx) * HERO_SCROLL_UP_SCALE_MULTIPLIER
    } else {
        scrollOffsetPx * HERO_SCROLL_DOWN_SCALE_MULTIPLIER
    }
    return (1f + scaleIncrease).coerceAtMost(HERO_SCROLL_MAX_SCALE)
}

private fun heroBackgroundScrollTranslationY(scrollOffsetPx: Float): Float {
    return scrollOffsetPx * HERO_SCROLL_PARALLAX
}

private fun Modifier.homeHeroPagerGesture(
    pagerState: PagerState,
    pageCount: Int,
    coroutineScope: CoroutineScope,
): Modifier {
    if (pageCount <= 1) return this

    return pointerInput(pagerState, pageCount) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            val widthPx = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply {
                addPosition(down.uptimeMillis, down.position)
            }
            val startPage = pagerState.currentPage
            var totalDx = 0f
            var totalDy = 0f
            var dragging = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (!change.pressed) {
                    if (dragging) {
                        val targetPage = resolveHeroTargetPage(
                            startPage = startPage,
                            pageCount = pageCount,
                            totalDx = totalDx,
                            velocityX = velocityTracker.calculateVelocity().x,
                            widthPx = widthPx,
                        )
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                    break
                }

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!dragging) {
                    val horizontalDrag =
                        abs(totalDx) > viewConfiguration.touchSlop && abs(totalDx) > abs(totalDy)
                    val verticalDrag =
                        abs(totalDy) > viewConfiguration.touchSlop && abs(totalDy) > abs(totalDx)

                    when {
                        verticalDrag -> break
                        horizontalDrag -> dragging = true
                        else -> continue
                    }
                }

                pagerState.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}

internal fun resolveHeroTargetPage(
    startPage: Int,
    pageCount: Int,
    totalDx: Float,
    velocityX: Float,
    widthPx: Float,
): Int {
    val thresholdPassed = abs(totalDx) > widthPx * HERO_SWIPE_THRESHOLD_FRACTION ||
        abs(velocityX) > HERO_SWIPE_VELOCITY_THRESHOLD
    if (!thresholdPassed) return startPage

    // Neighbouring virtual pages always exist, so wrapping is a single step in the
    // direction of the swipe rather than a jump across the whole list.
    return when {
        totalDx > 0f -> (startPage - 1).coerceAtLeast(0)
        totalDx < 0f -> (startPage + 1).coerceAtMost(pageCount - 1)
        else -> startPage
    }
}

// Lowest common multiple of 1..HOME_HERO_ITEM_LIMIT (8), so the anchor below divides
// evenly by every hero count the repository can produce and always maps to the first item.
private const val HERO_LOOP_ALIGNMENT = 840

// Fixed span: it must NOT depend on the hero count, otherwise republished hero items
// resize the range under the pager and can strand currentPage outside it. Sized for
// headroom, not infinity: from the mid-point this is ~4200 auto-advances (>9h of
// continuous playback) in either direction, while keeping the total scroll extent
// small enough to stay well inside exact integer range.
private const val HERO_LOOP_PAGE_COUNT = 8_400

internal fun heroFloorMod(value: Int, divisor: Int): Int =
    if (divisor <= 0) 0 else ((value % divisor) + divisor) % divisor

/** Virtual page span: wide enough that the ends are never reached in practice. */
internal fun heroLoopPageCount(itemCount: Int): Int =
    if (itemCount > 1) HERO_LOOP_PAGE_COUNT else itemCount

/** Starts mid-span on a multiple of the alignment, so the first item is shown first. */
internal fun heroLoopInitialPage(itemCount: Int): Int {
    if (itemCount <= 1) return 0
    val middle = HERO_LOOP_PAGE_COUNT / 2
    return middle - heroFloorMod(middle, HERO_LOOP_ALIGNMENT)
}

/** Nearest virtual page showing [targetItemIndex], so dot taps take the shortest route. */
internal fun heroNearestLoopPage(
    fromPage: Int,
    targetItemIndex: Int,
    itemCount: Int,
    pageCount: Int,
): Int {
    if (itemCount <= 0) return 0
    var delta = targetItemIndex - heroFloorMod(fromPage, itemCount)
    if (delta > itemCount / 2) delta -= itemCount
    if (delta < -(itemCount / 2)) delta += itemCount
    return (fromPage + delta).coerceIn(0, pageCount - 1)
}
