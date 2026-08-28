package com.nuvio.app.features.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeReleaseParserTest {

    @Test
    fun `folder name keeps the fuller title and yields the pack range`() {
        val parsed = AnimeReleaseParser.parseFolder("[No] Hidamari Sketch x Honeycomb 01-12 (Hi10P BD 720p)")

        assertEquals("Hidamari Sketch x Honeycomb", parsed.title)
        assertEquals(1..12, parsed.episodeRange)
        assertEquals("No", parsed.group)
    }

    @Test
    fun `file name with underscores and a glued group tag yields the episode`() {
        val parsed = AnimeReleaseParser.parseFile("[No]Hidamari_Sketch_Honeycomb_-_01[21E9EE6F].mkv")

        assertEquals(1, parsed.episode)
        assertEquals("Hidamari Sketch Honeycomb", parsed.title)
    }

    @Test
    fun `trailing crc32 is never read as an episode number`() {
        val parsed = AnimeReleaseParser.parseFile("[No]Hidamari_Sketch_Honeycomb_-_12[A9064EBB].mkv")

        assertEquals(12, parsed.episode)
        assertTrue(!parsed.title.contains("A9064EBB"))
    }

    @Test
    fun `version suffix does not break the episode number`() {
        val parsed = AnimeReleaseParser.parseFile(
            "[Erai-raws] Kusuriya no Hitorigoto 2nd Season - 05v2 [1080p][Multiple Subtitle].mkv",
        )

        assertEquals(5, parsed.episode)
        assertEquals(2, parsed.season)
        // The season words stay in the title: the anime databases index the cour by name.
        assertTrue(parsed.title.contains("2nd Season"))
    }

    @Test
    fun `scene style names still parse`() {
        val parsed = AnimeReleaseParser.parseFile("Mushoku.Tensei.S02E11.1080p.BluRay.x265.mkv")

        assertEquals(2, parsed.season)
        assertEquals(11, parsed.episode)
        assertEquals("Mushoku Tensei", parsed.title)
    }

    @Test
    fun `long absolute numbers survive`() {
        val parsed = AnimeReleaseParser.parseFile("[Judas] One Piece - 1085 [1080p][HEVC x265 10bit].mkv")

        assertEquals(1085, parsed.episode)
        assertEquals("One Piece", parsed.title)
    }

    @Test
    fun `a four digit year is not mistaken for an episode`() {
        val parsed = AnimeReleaseParser.parseFolder("Some Movie (2012) [BD 1080p]")

        assertEquals(2012, parsed.year)
        assertNull(parsed.episode)
    }

    @Test
    fun `specials are flagged`() {
        val parsed = AnimeReleaseParser.parseFile("[Group] Show Name - NCOP01 [1080p].mkv")

        assertTrue(parsed.isSpecial)
    }

    @Test
    fun `titles compare across the missing multiplication sign`() {
        val score = AnimeReleaseParser.similarity(
            "Hidamari Sketch Honeycomb",
            "Hidamari Sketch x Honeycomb",
        )

        assertTrue(score > 0.7f, "expected a strong match, got $score")
    }

    @Test
    fun `dot separated scene folders yield their season`() {
        val parsed = AnimeReleaseParser.parseFolder(
            "Laid-Back.Camp.S02.1080p.BluRay.10-Bit.Dual-Audio.AAC.FLAC2.0.x265-YURASUKA",
        )

        assertEquals(2, parsed.season)
        assertTrue(parsed.title.startsWith("Laid-Back Camp"), "got '${parsed.title}'")
    }

    @Test
    fun `a trailing number in a folder name stays part of the title`() {
        val parsed = AnimeReleaseParser.parseFolder("Hidamari Sketch x 365 [BD-HDHWP]")

        assertEquals("Hidamari Sketch x 365", parsed.title)
        assertNull(parsed.episode)
    }

    @Test
    fun `a single file torrent still parses as a file`() {
        val parsed = AnimeReleaseParser.parseFolder(
            "[Kakumei Subs] Sousou no Frieren - S02E09 [F057C2CA].mkv",
        )

        assertEquals(2, parsed.season)
        assertEquals(9, parsed.episode)
    }

    @Test
    fun `pack folders keep their episode range`() {
        val parsed = AnimeReleaseParser.parseFolder(
            "[Erai-raws] Shingeki no Kyojin - The Final Season - 01 ~ 16 [720p][Multiple Subtitle]",
        )

        assertEquals(1..16, parsed.episodeRange)
    }

    @Test
    fun `brace groups do not hide the episode number`() {
        val parsed = AnimeReleaseParser.parseFile(
            "[Cervoz] Ao Haru Ride - 12 {Bluray.720p.10bits}{Jap-Fr.Aac}{Fr-For.Sub} [F313EB62].mkv",
        )

        assertEquals(12, parsed.episode)
        assertEquals("Ao Haru Ride", parsed.title)
    }

    @Test
    fun `an episode number in its own bracket is found`() {
        val parsed = AnimeReleaseParser.parseFile(
            "Flying_Witch_[08]_[AniLibria_TV]_[HDTV-Rip_720p].mkv",
        )

        assertEquals(8, parsed.episode)
        assertEquals("Flying Witch", parsed.title)
    }

    @Test
    fun `a leftover dash number is not part of the title`() {
        val parsed = AnimeReleaseParser.parseFile(
            "[ForeForFour] Air in Summer - 01 - S00E02 [9B95F871].mkv",
        )

        assertEquals("Air in Summer", parsed.title)
        assertEquals(0, parsed.season)
    }

    @Test
    fun `sibling shows in one folder parse to different titles`() {
        val air = AnimeReleaseParser.parseFile("[ForeForFour] Air (2005) - 01 [FE322F19].mkv")
        val summer = AnimeReleaseParser.parseFile(
            "[ForeForFour] Air in Summer - 01 - S00E02 [9B95F871].mkv",
        )

        assertEquals("Air", air.title)
        assertEquals("Air in Summer", summer.title)
    }

    @Test
    fun `a special is numbered and put in season zero`() {
        val parsed = AnimeReleaseParser.parseFile("Hidamari Sketch x 365 SP2 BD x264-8.mkv")

        assertEquals(0, parsed.season)
        assertEquals(2, parsed.episode)
        assertTrue(parsed.isSpecial)
        assertEquals("Hidamari Sketch x 365", parsed.title)
    }

    @Test
    fun `an unnumbered special still lands in season zero`() {
        val parsed = AnimeReleaseParser.parseFile("[Group] Show Name - OVA [1080p].mkv")

        assertEquals(0, parsed.season)
        assertTrue(parsed.isSpecial)
    }

    @Test
    fun `a bracketed year is not read as an episode`() {
        val parsed = AnimeReleaseParser.parseFile("[Group] Some Show [2016] [1080p].mkv")

        assertNull(parsed.episode)
    }

    @Test
    fun `a technical tail is trimmed so a bare episode number is found`() {
        val parsed = AnimeReleaseParser.parseFile("Hidamari Sketch x 365 01 BD x264-8.mkv")

        assertEquals(1, parsed.episode)
        assertEquals("Hidamari Sketch x 365", parsed.title)
    }
}

class VideoIdParsingTest {

    @Test
    fun `imdb style ids carry season and episode`() {
        val parts = parseVideoId("tt0972656:4:1")

        assertEquals("tt0972656", parts.contentId)
        assertEquals(4, parts.season)
        assertEquals(1, parts.episode)
    }

    @Test
    fun `anime style ids carry an episode with no season`() {
        val parts = parseVideoId("kitsu:6480:5")

        assertEquals("kitsu:6480", parts.contentId)
        assertNull(parts.season)
        assertEquals(5, parts.episode)
    }

    @Test
    fun `a bare movie id has no numbers`() {
        val parts = parseVideoId("tt0972656")

        assertEquals("tt0972656", parts.contentId)
        assertNull(parts.season)
        assertNull(parts.episode)
    }
}

class WebDavXmlTest {

    @Test
    fun `namespace prefixes are ignored and entries are read`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/torrents/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/torrents/%5BNo%5D%20Hidamari%20Sketch/</D:href>
                <D:propstat><D:prop>
                  <D:displayname>[No] Hidamari Sketch</D:displayname>
                  <D:resourcetype><D:collection/></D:resourcetype>
                  <D:getlastmodified>Tue, 05 Oct 2012 12:00:00 GMT</D:getlastmodified>
                </D:prop></D:propstat>
              </D:response>
              <lp1:response xmlns:lp1="DAV:">
                <lp1:href>/torrents/show/ep01.mkv</lp1:href>
                <lp1:propstat><lp1:prop>
                  <lp1:resourcetype/>
                  <lp1:getcontentlength>1234567890</lp1:getcontentlength>
                </lp1:prop></lp1:propstat>
              </lp1:response>
            </D:multistatus>
        """.trimIndent()

        val entries = WebDavXml.parseMultistatus(xml)

        assertEquals(3, entries.size)
        assertTrue(entries[1].isCollection)
        assertEquals("[No] Hidamari Sketch", entries[1].displayName)
        assertNotNull(entries[1].lastModifiedEpochSeconds)
        assertTrue(!entries[2].isCollection)
        assertEquals(1234567890L, entries[2].contentLength)
    }

    @Test
    fun `percent encoded hrefs decode back to the original name`() {
        val decoded = WebDavUrl.decode("/torrents/%5BNo%5D%20Hidamari%20Sketch%20x%20Honeycomb/")

        assertEquals("/torrents/[No] Hidamari Sketch x Honeycomb/", decoded)
    }

    @Test
    fun `http dates parse to epoch seconds`() {
        val epoch = parseHttpDateToEpochSeconds("Tue, 05 Oct 2012 12:00:00 GMT")

        assertEquals(1349438400L, epoch)
    }
}

class EpisodePlacementTest {

    private val fourSeasons = buildList {
        // Four cours of twelve, aired 2007, 2008, 2010 and 2012.
        val starts = listOf(1167609600L, 1199145600L, 1262304000L, 1349438400L)
        starts.forEachIndexed { index, start ->
            (1..12).forEach { episode ->
                add(
                    EpisodeSlot(
                        season = index + 1,
                        episode = episode,
                        releasedEpochSeconds = start + episode * 604_800L,
                    ),
                )
            }
        }
    }

    @Test
    fun `the mapper season wins when it fits`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 1,
            parsedSeason = null,
            mapperSeason = 4,
            packSize = 12,
            entryStartEpochSeconds = null,
            episodes = fourSeasons,
        )

        assertNotNull(placement)
        assertEquals(4, placement.season)
        assertEquals(1, placement.episode)
        assertEquals(PlacementStep.MapperSeason, placement.step)
    }

    @Test
    fun `air date anchoring separates cours of equal length`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 1,
            parsedSeason = null,
            mapperSeason = null,
            packSize = 12,
            entryStartEpochSeconds = 1349438400L,
            episodes = fourSeasons,
        )

        assertNotNull(placement)
        assertEquals(4, placement.season)
        assertEquals(PlacementStep.AirDateAnchor, placement.step)
    }

    @Test
    fun `absolute numbering falls through to flattening`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 40,
            parsedSeason = null,
            mapperSeason = null,
            packSize = null,
            entryStartEpochSeconds = null,
            episodes = fourSeasons,
        )

        assertNotNull(placement)
        assertEquals(4, placement.season)
        assertEquals(4, placement.episode)
        assertEquals(PlacementStep.FlattenedAbsolute, placement.step)
    }

    @Test
    fun `nothing is invented when the episode does not exist`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 900,
            parsedSeason = null,
            mapperSeason = null,
            packSize = null,
            entryStartEpochSeconds = null,
            episodes = fourSeasons,
        )

        assertNull(placement)
    }

    @Test
    fun `a special never gets flattened into a numbered season`() {
        val withSpecials = fourSeasons + EpisodeSlot(season = 0, episode = 2)

        val placement = EpisodePlacement.place(
            parsedEpisode = 2,
            parsedSeason = 0,
            mapperSeason = 1,
            packSize = null,
            entryStartEpochSeconds = null,
            episodes = withSpecials,
        )

        assertNotNull(placement)
        assertEquals(0, placement.season)
        assertEquals(2, placement.episode)
    }

    @Test
    fun `a special with no specials season stays unplaced`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 2,
            parsedSeason = 0,
            mapperSeason = 1,
            packSize = null,
            entryStartEpochSeconds = null,
            episodes = fourSeasons,
        )

        assertNull(placement)
    }

    @Test
    fun `a season stated in the name beats the mapper`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 1,
            parsedSeason = 2,
            mapperSeason = 1,
            packSize = 12,
            entryStartEpochSeconds = null,
            episodes = fourSeasons,
        )

        assertNotNull(placement)
        assertEquals(2, placement.season)
        assertEquals(PlacementStep.ExplicitSeasonEpisode, placement.step)
    }

    @Test
    fun `a known season survives an unreachable metadata addon`() {
        val placement = EpisodePlacement.place(
            parsedEpisode = 5,
            parsedSeason = null,
            mapperSeason = 4,
            packSize = 12,
            entryStartEpochSeconds = null,
            episodes = emptyList(),
        )

        assertNotNull(placement)
        assertEquals(4, placement.season)
        assertEquals(5, placement.episode)
    }
}
