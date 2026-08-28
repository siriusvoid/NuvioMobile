package com.nuvio.app.features.subtitles

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportedSubtitleMatcherTest {

    @Test
    fun `places a fansub season pack on the first season`() {
        val files = (1..10).map { episode ->
            ImportedSubtitleMatcher.parse("[HorribleSubs] Polar Bear Cafe - ${pad(episode)} [720p].ass")
        }

        val placed = place(files, meta(seasons = mapOf(1 to 12)))

        assertEquals(List(10) { 1 }, placed.map { it.season })
        assertEquals((1..10).toList(), placed.map { it.episode })
        assertEquals("s1e1", placed.first().videoId)
    }

    @Test
    fun `keeps a special in season zero`() {
        val files = listOf(
            ImportedSubtitleMatcher.parse("[ShinkaDan] Kokoro Library - 01 [DVDrip 960x720 H264 AC3] [Azazel & Chigusa].ass"),
            ImportedSubtitleMatcher.parse("[ShinkaDan] Kokoro Library - SP [DVDrip 960x720 H264 AAC] [Azazel & Chigusa].ass"),
        )

        val placed = place(files, meta(seasons = mapOf(0 to 1, 1 to 13)))

        assertEquals(1, placed[0].season)
        assertEquals(1, placed[0].episode)
        assertEquals(0, placed[1].season)
        assertEquals(1, placed[1].episode)
    }

    @Test
    fun `reads a scene name's own season and episode`() {
        val files = listOf(ImportedSubtitleMatcher.parse("Laid-Back.Camp.S02E03.1080p.srt"))

        val placed = place(files, meta(seasons = mapOf(1 to 12, 2 to 13)))

        assertEquals(2, placed.single().season)
        assertEquals(3, placed.single().episode)
    }

    @Test
    fun `flattens an absolute number onto the season that holds it`() {
        val files = listOf(ImportedSubtitleMatcher.parse("[Group] Long Runner - 15 [1080p].ass"))

        val placed = place(files, meta(seasons = mapOf(1 to 12, 2 to 12)))

        assertEquals(2, placed.single().season)
        assertEquals(3, placed.single().episode)
    }

    @Test
    fun `a season picked by hand overrides the flattened guess`() {
        val files = (1..12).map { episode ->
            ImportedSubtitleMatcher.parse("[Group] Second Cour - ${pad(episode)} [1080p].ass")
        }

        val placed = place(files, meta(seasons = mapOf(1 to 12, 2 to 12)), seasonOverride = 2)

        assertEquals(List(12) { 2 }, placed.map { it.season })
        assertEquals((1..12).toList(), placed.map { it.episode })
    }

    @Test
    fun `an offset shifts the whole pack`() {
        val files = (1..3).map { episode ->
            ImportedSubtitleMatcher.parse("[Group] Show - ${pad(episode)} [1080p].ass")
        }

        val placed = place(files, meta(seasons = mapOf(1 to 24)), episodeOffset = 12)

        assertEquals(listOf(13, 14, 15), placed.map { it.episode })
    }

    @Test
    fun `a file with no episode number stays unmatched`() {
        val files = listOf(ImportedSubtitleMatcher.parse("readme.srt"))

        val placed = place(files, meta(seasons = mapOf(1 to 12)))

        assertNull(placed.single().videoId)
        assertNull(placed.single().episode)
    }

    @Test
    fun `a film takes the show id itself`() {
        val files = listOf(ImportedSubtitleMatcher.parse("Perfect Blue [BDrip].ass"))

        val placed = ImportedSubtitleMatcher.place(
            files = files,
            meta = null,
            seasonHint = null,
            seasonOverride = null,
            episodeOffset = 0,
            isMovie = true,
            metaId = "tt0156887",
        )

        assertEquals("tt0156887", placed.single().videoId)
    }

    @Test
    fun `a season in the folder name places a relative pack`() {
        val files = (1..12).map { episode ->
            ImportedSubtitleMatcher.parse("[Group] Show - ${pad(episode)} [1080p].ass")
        }

        val placed = place(
            files = files,
            meta = meta(seasons = mapOf(1 to 12, 2 to 12)),
            seasonHint = ImportedSubtitleMatcher.seasonHint("Show 2nd Season [BD 1080p]"),
        )

        assertEquals(List(12) { 2 }, placed.map { it.season })
        assertEquals((1..12).toList(), placed.map { it.episode })
    }

    private fun place(
        files: List<ImportedSubtitleFile>,
        meta: MetaDetails,
        seasonHint: Int? = null,
        seasonOverride: Int? = null,
        episodeOffset: Int = 0,
    ): List<ImportedSubtitleFile> = ImportedSubtitleMatcher.place(
        files = files,
        meta = meta,
        seasonHint = seasonHint,
        seasonOverride = seasonOverride,
        episodeOffset = episodeOffset,
        isMovie = false,
        metaId = meta.id,
    )

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private fun meta(seasons: Map<Int, Int>): MetaDetails = MetaDetails(
        id = "show",
        type = "series",
        name = "Show",
        videos = seasons.flatMap { (season, episodeCount) ->
            (1..episodeCount).map { episode ->
                MetaVideo(
                    id = "s${season}e$episode",
                    title = "Episode $episode",
                    season = season,
                    episode = episode,
                )
            }
        },
    )
}
