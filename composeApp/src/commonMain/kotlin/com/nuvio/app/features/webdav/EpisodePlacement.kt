package com.nuvio.app.features.webdav

import kotlin.math.abs

/** One episode as the metadata addon reports it. */
internal data class EpisodeSlot(
    val season: Int,
    val episode: Int,
    val releasedEpochSeconds: Long? = null,
)

internal data class Placement(
    val season: Int,
    val episode: Int,
    val step: PlacementStep,
)

/**
 * Decides which season and episode a file belongs to.
 *
 * The rungs run in order and the first one that produces a slot the metadata
 * actually contains wins. Nothing guesses past the last rung: an unplaced file
 * goes to review, because a wrongly placed episode quietly corrupts watch
 * progress and anything syncing from it.
 */
internal object EpisodePlacement {

    fun place(
        parsedEpisode: Int?,
        parsedSeason: Int?,
        mapperSeason: Int?,
        packSize: Int?,
        entryStartEpochSeconds: Long?,
        episodes: List<EpisodeSlot>,
    ): Placement? {
        if (parsedEpisode == null) return null

        // The metadata addon may be unreachable. A season from the name or the mapper is
        // data rather than a guess, so use it rather than dropping the folder entirely.
        if (episodes.isEmpty()) {
            val known = parsedSeason ?: mapperSeason ?: return null
            return Placement(
                season = known,
                episode = parsedEpisode,
                step = if (parsedSeason != null) {
                    PlacementStep.ExplicitSeasonEpisode
                } else {
                    PlacementStep.MapperSeason
                },
            )
        }

        // A special stays in season 0 or nowhere: flattening it into a numbered season
        // would drop an OVA in the middle of the run.
        if (parsedSeason == 0) {
            return exact(episodes, 0, parsedEpisode)
                ?.let { Placement(it.season, it.episode, PlacementStep.ExplicitSeasonEpisode) }
                ?: episodes.firstOrNull { it.season == 0 }
                    ?.let { Placement(0, parsedEpisode, PlacementStep.ExplicitSeasonEpisode) }
        }

        // 1 · The filename said so.
        if (parsedSeason != null) {
            exact(episodes, parsedSeason, parsedEpisode)?.let {
                return Placement(it.season, it.episode, PlacementStep.ExplicitSeasonEpisode)
            }
            // The stated season exists but not that episode number — still trust the name
            // over the mapper, which is what put S02 packs onto season 1.
            if (episodes.any { it.season == parsedSeason }) {
                return Placement(parsedSeason, parsedEpisode, PlacementStep.ExplicitSeasonEpisode)
            }
        }

        // 2 · The mapper said so. This is the normal path for cour-titled anime.
        if (mapperSeason != null) {
            exact(episodes, mapperSeason, parsedEpisode)?.let {
                return Placement(it.season, it.episode, PlacementStep.MapperSeason)
            }
        }

        val seasons = episodes.groupBy { it.season }

        // 3 · Exactly one season has as many episodes as the pack.
        if (packSize != null && packSize > 1) {
            val fits = seasons.filter { (_, slots) -> slots.size == packSize }
            if (fits.size == 1) {
                val season = fits.keys.first()
                exact(episodes, season, parsedEpisode)?.let {
                    return Placement(it.season, it.episode, PlacementStep.EpisodeCountFit)
                }
            }
        }

        // 4 · The season that started closest to when this entry aired.
        if (entryStartEpochSeconds != null) {
            val closest = seasons
                .mapNotNull { (season, slots) ->
                    val firstAir = slots
                        .sortedBy { it.episode }
                        .firstNotNullOfOrNull { it.releasedEpochSeconds }
                        ?: return@mapNotNull null
                    season to abs(firstAir - entryStartEpochSeconds)
                }
                .minByOrNull { it.second }

            if (closest != null && closest.second <= MAX_AIR_DATE_DISTANCE_SECONDS) {
                exact(episodes, closest.first, parsedEpisode)?.let {
                    return Placement(it.season, it.episode, PlacementStep.AirDateAnchor)
                }
            }
        }

        // 5 · Treat the number as absolute across the whole show. Long-runners land here.
        val ordered = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val index = parsedEpisode - 1
        if (index in ordered.indices) {
            val slot = ordered[index]
            return Placement(slot.season, slot.episode, PlacementStep.FlattenedAbsolute)
        }

        return null
    }

    private fun exact(episodes: List<EpisodeSlot>, season: Int, episode: Int): EpisodeSlot? =
        episodes.firstOrNull { it.season == season && it.episode == episode }

    /** Two years: wide enough for delayed cours, narrow enough to separate seasons. */
    private const val MAX_AIR_DATE_DISTANCE_SECONDS = 63_072_000L
}
