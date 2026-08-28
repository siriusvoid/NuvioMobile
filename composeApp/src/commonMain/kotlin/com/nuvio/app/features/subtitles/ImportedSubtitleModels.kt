package com.nuvio.app.features.subtitles

import kotlinx.serialization.Serializable

/**
 * Subtitles the user brought in from the Files app, kept on this device only.
 *
 * A pack is one import: the files picked in a single go, tied to the show whose
 * details page started the import. Nothing here syncs to an account.
 */
@Serializable
internal data class ImportedSubtitlePack(
    val id: String,
    /** Id in whatever space the metadata addon serves, e.g. tt0972656. */
    val metaId: String,
    val metaType: String,
    val showName: String,
    val language: String = IMPORTED_SUBTITLE_LANGUAGE,
    val importedAt: Long = 0L,
    /** Folder the files came from, when a folder was picked. Also a season hint. */
    val sourceName: String? = null,
    /** Season the anime databases place this release in, resolved once at import. */
    val mapperSeason: Int? = null,
    /** Season the whole pack was forced onto from settings. */
    val seasonOverride: Int? = null,
    /** Added to every parsed episode number before placement, set from settings. */
    val episodeOffset: Int = 0,
    /** Opts the pack out of removal once the show is watched through. */
    val keepAfterWatching: Boolean = false,
    val files: List<ImportedSubtitleFile> = emptyList(),
) {
    val matchedCount: Int get() = files.count { it.isMatched }
}

@Serializable
internal data class ImportedSubtitleFile(
    val fileName: String,
    /**
     * Path below the imported-subtitles root. The iOS container directory is
     * renamed by app updates, so an absolute path stored today is dead tomorrow.
     */
    val relativePath: String,
    /** Episode number as the file name spells it, before any placement. */
    val parsedEpisode: Int? = null,
    /** Season the file name states, if it states one. */
    val parsedSeason: Int? = null,
    /** The metadata addon's own video id — the exact key the player asks with. */
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isMatched: Boolean get() = videoId != null
}

internal data class ImportedSubtitlesUiState(
    val packs: List<ImportedSubtitlePack> = emptyList(),
    val loaded: Boolean = false,
    val isImporting: Boolean = false,
)

/** Everything is imported as Russian: it is the only language this is used for. */
internal const val IMPORTED_SUBTITLE_LANGUAGE = "rus"

internal val SUBTITLE_FILE_EXTENSIONS = setOf(
    "ass", "ssa", "srt", "vtt", "sub", "sbv", "smi", "ttml", "dfxp",
)

internal fun String.isSubtitleFileName(): Boolean =
    substringAfterLast('.', missingDelimiterValue = "").lowercase() in SUBTITLE_FILE_EXTENSIONS
