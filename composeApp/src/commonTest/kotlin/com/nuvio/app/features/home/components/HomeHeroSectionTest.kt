package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeHeroSectionTest {

    @Test
    fun `mobile hero height follows viewport height when provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(692.08f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero height leaves room for continue watching card section`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(24f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero can shrink below default minimum to fit short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(268f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `loop span starts mid range aligned to the first item`() {
        val itemCount = 6
        val initial = heroLoopInitialPage(itemCount)

        assertEquals(0, heroFloorMod(initial, itemCount))
        assertTrue(initial > 0)
        assertTrue(initial < heroLoopPageCount(itemCount))
    }

    @Test
    fun `a single item does not loop`() {
        assertEquals(0, heroLoopPageCount(0))
        assertEquals(1, heroLoopPageCount(1))
        assertEquals(0, heroLoopInitialPage(1))
    }

    @Test
    fun `floor mod wraps pages below zero`() {
        assertEquals(5, heroFloorMod(-1, 6))
        assertEquals(0, heroFloorMod(-6, 6))
        assertEquals(1, heroFloorMod(7, 6))
    }

    @Test
    fun `wrapping past the last item advances exactly one page`() {
        val itemCount = 6
        val pageCount = heroLoopPageCount(itemCount)
        val lastItemPage = heroLoopInitialPage(itemCount) + itemCount - 1
        assertEquals(itemCount - 1, heroFloorMod(lastItemPage, itemCount))

        val next = resolveHeroTargetPage(
            startPage = lastItemPage,
            pageCount = pageCount,
            totalDx = -400f,
            velocityX = 0f,
            widthPx = 400f,
        )

        assertEquals(lastItemPage + 1, next)
        assertEquals(0, heroFloorMod(next, itemCount))
    }

    @Test
    fun `wrapping back from the first item retreats exactly one page`() {
        val itemCount = 6
        val pageCount = heroLoopPageCount(itemCount)
        val firstItemPage = heroLoopInitialPage(itemCount)

        val previous = resolveHeroTargetPage(
            startPage = firstItemPage,
            pageCount = pageCount,
            totalDx = 400f,
            velocityX = 0f,
            widthPx = 400f,
        )

        assertEquals(firstItemPage - 1, previous)
        assertEquals(itemCount - 1, heroFloorMod(previous, itemCount))
    }

    @Test
    fun `dot taps take the shortest route around the loop`() {
        val itemCount = 6
        val pageCount = heroLoopPageCount(itemCount)
        val from = heroLoopInitialPage(itemCount)

        assertEquals(from - 1, heroNearestLoopPage(from, itemCount - 1, itemCount, pageCount))
        assertEquals(from + 1, heroNearestLoopPage(from, 1, itemCount, pageCount))
        assertEquals(from, heroNearestLoopPage(from, 0, itemCount, pageCount))
    }

    @Test
    fun `loop anchor maps to the first item for every supported hero count`() {
        // HOME_HERO_ITEM_LIMIT is 8; the anchor must divide evenly by any count up to it.
        (1..8).forEach { count ->
            assertEquals(0, heroFloorMod(heroLoopInitialPage(count), count))
        }
    }

    @Test
    fun `page span does not change as hero items are republished`() {
        // A span tied to the item count would resize under the pager and could strand
        // currentPage outside the new range when the hero list shrinks.
        assertEquals(1, (2..8).map { heroLoopPageCount(it) }.toSet().size)
    }
}
