package com.nuvio.app.features.subtitles

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.webdav.AnimeReleaseParser
import com.nuvio.app.features.webdav.AnimeSearchClient
import com.nuvio.app.features.webdav.ArmMappingClient
import com.nuvio.app.features.webdav.EpisodePlacement
import com.nuvio.app.features.webdav.EpisodeSlot

/**
 * Decides which episode each imported file belongs to.
 *
 * The show is already known — the import starts from its details page — so this
 * is only the numbering problem, and it runs the same ladder the WebDAV library
 * uses: a season the name states wins, then a season that fits the pack size,
 * then the number read as absolute across the whole run. Specials keep season 0.
 */
internal object ImportedSubtitleMatcher {

    /** Reads the episode number out of a file name, before any placement. */
    fun parse(fileName: String): ImportedSubtitleFile {
        val parsed = AnimeReleaseParser.parseFile(fileName)
        return ImportedSubtitleFile(
            fileName = fileName,
            relativePath = "",
            parsedEpisode = parsed.episode,
            parsedSeason = parsed.season,
        )
    }

    /** A season stated by the folder the files came from, e.g. "… 2nd Season". */
    fun seasonHint(sourceName: String?): Int? {
        val name = sourceName?.takeIf { it.isNotBlank() } ?: return null
        return AnimeReleaseParser.parseFolder(name).season
    }

    /** The release title the files agree on, used to look the season up. */
    fun releaseTitle(fileNames: List<String>): String? = fileNames
        .map { AnimeReleaseParser.parseFile(it).title }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    /**
     * The season the anime databases put a cour-titled release in.
     *
     * "Hidamari Sketch x Honeycomb" is its own database entry numbered from one, but
     * the metadata addon serves it as season 4 of Hidamari Sketch. Without this the
     * episode numbers read as absolute and the whole pack lands on season 1.
     */
    suspend fun databaseSeason(title: String?): Int? {
        val query = title?.takeIf { it.isNotBlank() } ?: return null
        val hits = AnimeSearchClient.search(query)
        val best = hits
            .map { hit -> hit to (hit.allTitles.maxOfOrNull { AnimeReleaseParser.similarity(query, it) } ?: 0f) }
            .maxByOrNull { it.second }
            ?: return null
        if (best.second < MIN_TITLE_SIMILARITY) return null
        return ArmMappingClient.lookup(best.first.source, best.first.id)?.season
    }

    /** Below this the search hit is a different show, and its season would mislead. */
    private const val MIN_TITLE_SIMILARITY = 0.7f

    /**
     * Fills in season, episode and the metadata addon's video id for every file.
     * Re-runnable: settings changes to [seasonOverride] or [episodeOffset] come
     * back through here rather than being applied on top of an earlier result.
     */
    fun place(
        files: List<ImportedSubtitleFile>,
        meta: MetaDetails?,
        seasonHint: Int?,
        seasonOverride: Int?,
        episodeOffset: Int,
        isMovie: Boolean,
        metaId: String,
    ): List<ImportedSubtitleFile> {
        if (isMovie) {
            return files.map { file ->
                file.copy(videoId = metaId, season = null, episode = null)
            }
        }

        val videos = meta?.videos.orEmpty()
        val slots = videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            EpisodeSlot(
                season = season,
                episode = episode,
                releasedEpochSeconds = null,
            )
        }
        val videoIds = videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            (season to episode) to video.id
        }.toMap()

        val numberedSlots = slots.filter { it.season != 0 }

        return files.map { file ->
            val isSpecial = file.parsedSeason == 0
            val episodeNumber = file.parsedEpisode?.plus(episodeOffset)
            val placement = EpisodePlacement.place(
                parsedEpisode = episodeNumber,
                // An override picked in settings replaces the name's own season, but a
                // special stays a special: nobody overrides a pack onto season 0 by hand.
                parsedSeason = if (isSpecial) 0 else seasonOverride ?: file.parsedSeason,
                mapperSeason = seasonHint,
                packSize = files.size,
                entryStartEpochSeconds = null,
                // Specials are placed among specials and numbered episodes among the
                // numbered ones. Reading "- 01" as absolute over a list that starts with
                // an OVA would drop the first episode of the run onto that OVA.
                episodes = if (isSpecial) slots else numberedSlots,
            )
            file.copy(
                season = placement?.season,
                episode = placement?.episode,
                videoId = placement?.let { videoIds[it.season to it.episode] },
            )
        }
    }
}
