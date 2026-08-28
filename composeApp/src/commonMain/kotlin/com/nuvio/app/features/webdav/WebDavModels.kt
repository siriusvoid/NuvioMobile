package com.nuvio.app.features.webdav

import kotlinx.serialization.Serializable

/** Known debrid WebDAV endpoints. Custom covers anything else the user points at. */
enum class WebDavProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultRootPath: String,
    /** Username is fixed by the provider (TorBox accepts the API key with a literal username). */
    val fixedUsername: String? = null,
) {
    RealDebrid(
        id = "realdebrid",
        displayName = "Real-Debrid",
        defaultBaseUrl = "https://dav.real-debrid.com",
        defaultRootPath = "torrents",
    ),
    Torbox(
        id = "torbox",
        displayName = "TorBox",
        defaultBaseUrl = "https://webdav.torbox.app",
        defaultRootPath = "",
        fixedUsername = "torbox",
    ),
    Custom(
        id = "custom",
        displayName = "Custom WebDAV",
        defaultBaseUrl = "",
        defaultRootPath = "",
    );

    companion object {
        fun fromId(id: String?): WebDavProvider =
            entries.firstOrNull { it.id == id } ?: Custom
    }
}

/**
 * A configured WebDAV account. The password never lives here — it is held by
 * [WebDavCredentialStorage], keyed by [id].
 */
@Serializable
data class WebDavSource(
    val id: String,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    /** Path below [baseUrl] that holds one folder per torrent. Empty means the server root. */
    val rootPath: String = "",
    /** How many of the newest folders a scan refreshes. */
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
) {
    val provider: WebDavProvider get() = WebDavProvider.fromId(providerId)

    companion object {
        const val DEFAULT_WINDOW_SIZE = 50
    }
}

/** One playable file discovered inside a torrent folder. */
@Serializable
data class WebDavFile(
    val fileName: String,
    /** Absolute, percent-encoded URL ready to hand to the player. */
    val url: String,
    val sizeBytes: Long? = null,
)

/** A torrent folder plus everything worth playing inside it. */
@Serializable
data class WebDavFolder(
    val sourceId: String,
    /** Path relative to the source root; stable identity for overrides. */
    val path: String,
    val name: String,
    val modifiedAt: Long? = null,
    val files: List<WebDavFile> = emptyList(),
    val subtitles: List<WebDavFile> = emptyList(),
) {
    val key: String get() = "$sourceId|$path"
}

/** Which rung of the placement ladder decided a match — surfaced in the review screen. */
enum class PlacementStep(val label: String) {
    ExplicitSeasonEpisode("filename S/E"),
    MapperSeason("mapper season"),
    EpisodeCountFit("episode count"),
    AirDateAnchor("air date"),
    FlattenedAbsolute("flattened"),
    Manual("manual"),
    Unresolved("unresolved"),
}

/** The resolved identity of one torrent folder. */
@Serializable
data class WebDavMatch(
    val folderKey: String,
    val sourceId: String,
    val folderPath: String,
    /** Id in whatever space the installed metadata addon serves, e.g. tt0972656. */
    val contentId: String,
    val contentType: String,
    val title: String,
    val poster: String? = null,
    /** Name and artwork as the metadata addon serves them — localized, and matching
     *  what the details page shows. The search-database values are the fallback. */
    val metaName: String? = null,
    val metaPoster: String? = null,
    val season: Int? = null,
    /** Added to the parsed episode number before placement. */
    val episodeOffset: Int = 0,
    val step: String = PlacementStep.Unresolved.name,
    val confidence: Float = 0f,
    val userSet: Boolean = false,
    val excluded: Boolean = false,
) {
    val placementStep: PlacementStep
        get() = runCatching { PlacementStep.valueOf(step) }.getOrDefault(PlacementStep.Unresolved)

    val isSeries: Boolean get() = contentType == CONTENT_TYPE_SERIES

    /** What the catalogue should show. */
    val displayName: String get() = metaName?.takeIf { it.isNotBlank() } ?: title

    val displayPoster: String? get() = metaPoster?.takeIf { it.isNotBlank() } ?: poster

    companion object {
        const val CONTENT_TYPE_SERIES = "series"
        const val CONTENT_TYPE_MOVIE = "movie"
    }
}

/** What a release name yields before any network lookup. */
data class ParsedRelease(
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeRange: IntRange? = null,
    val year: Int? = null,
    val group: String? = null,
    val isSpecial: Boolean = false,
) {
    val hasExplicitSeason: Boolean get() = season != null
}

enum class ScanPhase { Idle, Listing, Folders, Matching, Done, Failed }

data class WebDavScanProgress(
    val sourceId: String,
    val phase: ScanPhase = ScanPhase.Idle,
    val foldersDone: Int = 0,
    val foldersPlanned: Int = 0,
    val filesFound: Int = 0,
    val matchesResolved: Int = 0,
    val knownFolderCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isRunning: Boolean get() = phase == ScanPhase.Listing || phase == ScanPhase.Folders || phase == ScanPhase.Matching
}

data class WebDavUiState(
    val sources: List<WebDavSource> = emptyList(),
    val progress: Map<String, WebDavScanProgress> = emptyMap(),
    val folderCounts: Map<String, Int> = emptyMap(),
    val fileCounts: Map<String, Int> = emptyMap(),
    val matchedCounts: Map<String, Int> = emptyMap(),
    val loaded: Boolean = false,
)

sealed interface WebDavConnectionResult {
    data class Success(val entryCount: Int) : WebDavConnectionResult
    data class Failure(val message: String) : WebDavConnectionResult
}
