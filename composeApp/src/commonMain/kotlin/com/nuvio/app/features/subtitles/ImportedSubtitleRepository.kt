package com.nuvio.app.features.subtitles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The imported subtitle library.
 *
 * Files are copied into the app on import and matched to the show whose details
 * page started the import. Packs leave again once the show has been watched
 * through, unless the pack was marked to keep.
 */
internal object ImportedSubtitleRepository {
    private val log = Logger.withTag("ImportedSubs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(ImportedSubtitlesUiState())
    val uiState: StateFlow<ImportedSubtitlesUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        _uiState.value = ImportedSubtitlesUiState(packs = loadPacks(), loaded = true)
        scope.launch {
            WatchedRepository.uiState.collect { state ->
                if (state.watchedKeys.isNotEmpty()) removeWatchedPacks(state.watchedKeys)
            }
        }
    }

    fun hasPacks(): Boolean = _uiState.value.packs.isNotEmpty()

    fun packsFor(metaId: String): List<ImportedSubtitlePack> =
        _uiState.value.packs.filter { it.metaId == metaId }

    /**
     * Copies the picked files in and places them on the show's episodes. Returns
     * how many files were taken; zero means nothing the picker returned was a
     * subtitle file.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun import(meta: MetaDetails, picked: List<PickedSubtitleFile>): Int {
        ensureLoaded()
        val subtitleFiles = picked.filter { it.fileName.isSubtitleFileName() }
        if (subtitleFiles.isEmpty()) return 0

        val packId = Uuid.random().toString()
        val adopted = subtitleFiles.mapNotNull { file ->
            val relativePath = ImportedSubtitleStorage.adopt(
                sourcePath = file.sourcePath,
                packId = packId,
                fileName = file.fileName,
            ) ?: return@mapNotNull null
            ImportedSubtitleMatcher.parse(file.fileName).copy(relativePath = relativePath)
        }
        if (adopted.isEmpty()) {
            ImportedSubtitleStorage.deletePack(packId)
            return 0
        }

        val sourceName = subtitleFiles.firstNotNullOfOrNull { it.sourceName }
        // Offline, or an unknown title, simply leaves the season to the placement
        // ladder rather than holding the import up.
        val mapperSeason = withTimeoutOrNull(SEASON_LOOKUP_TIMEOUT_MS) {
            ImportedSubtitleMatcher.databaseSeason(
                ImportedSubtitleMatcher.releaseTitle(adopted.map { it.fileName }),
            )
        }
        val pack = ImportedSubtitlePack(
            id = packId,
            metaId = meta.id,
            metaType = meta.type,
            showName = meta.name,
            importedAt = EpisodeReleaseDatePlatform.nowEpochMs(),
            sourceName = sourceName,
            mapperSeason = mapperSeason,
            files = adopted,
        ).placed(meta)

        // Importing the same folder again replaces the earlier copy rather than
        // offering the episode twice. Two fansub groups ship identical file names for
        // the same show, so the folder has to match as well — otherwise a second
        // translation would silently delete the first.
        val replacedNames = pack.files.mapTo(mutableSetOf()) { it.fileName }
        val remaining = _uiState.value.packs.mapNotNull { existing ->
            if (existing.metaId != meta.id || existing.sourceName != pack.sourceName) {
                return@mapNotNull existing
            }
            val (superseded, kept) = existing.files.partition { it.fileName in replacedNames }
            superseded.forEach { ImportedSubtitleStorage.deleteFile(it.relativePath) }
            if (kept.isEmpty()) {
                ImportedSubtitleStorage.deletePack(existing.id)
                null
            } else {
                existing.copy(files = kept)
            }
        }

        publish(remaining + pack)
        log.i { "Imported ${pack.files.size} subtitles for ${meta.name}, ${pack.matchedCount} matched" }
        return pack.files.size
    }

    /** Re-places a pack after the season or the offset was corrected in settings. */
    fun updatePlacement(packId: String, meta: MetaDetails?, seasonOverride: Int?, episodeOffset: Int) {
        val packs = _uiState.value.packs.map { pack ->
            if (pack.id != packId) {
                pack
            } else {
                pack.copy(
                    seasonOverride = seasonOverride,
                    episodeOffset = episodeOffset,
                ).placed(meta)
            }
        }
        publish(packs)
    }

    fun setKeepAfterWatching(packId: String, keep: Boolean) {
        publish(
            _uiState.value.packs.map { pack ->
                if (pack.id == packId) pack.copy(keepAfterWatching = keep) else pack
            },
        )
    }

    fun deletePack(packId: String) {
        ImportedSubtitleStorage.deletePack(packId)
        publish(_uiState.value.packs.filterNot { it.id == packId })
    }

    fun deleteAllFor(metaId: String) {
        _uiState.value.packs.filter { it.metaId == metaId }.forEach {
            ImportedSubtitleStorage.deletePack(it.id)
        }
        publish(_uiState.value.packs.filterNot { it.metaId == metaId })
    }

    /**
     * Subtitles for one episode. The video id is the metadata addon's own, so it
     * is an exact hit; season and episode cover a show whose id space moved under
     * an already-imported pack.
     */
    fun subtitlesFor(
        videoId: String,
        metaId: String?,
        season: Int?,
        episode: Int?,
    ): List<ImportedSubtitleMatch> {
        ensureLoaded()
        return _uiState.value.packs.flatMap { pack ->
            pack.files
                .filter { file ->
                    when {
                        file.videoId == videoId -> true
                        metaId == null || pack.metaId != metaId -> false
                        season == null || episode == null -> false
                        else -> file.season == season && file.episode == episode
                    }
                }
                .filter { ImportedSubtitleStorage.exists(it.relativePath) }
                .map { file -> ImportedSubtitleMatch(pack = pack, file = file) }
        }
    }

    /** The absolute path an already-resolved subtitle sits at right now. */
    fun absolutePath(relativePath: String): String =
        ImportedSubtitleStorage.absolutePath(relativePath)

    /**
     * The path a stored subtitle sits at now, given one handed out earlier. Only
     * the pack and file name are matched, so a selection saved before an app
     * update still resolves once iOS has renamed the container underneath it.
     * Anything not in the index — an addon's http url — comes back null.
     */
    fun resolveStoredPath(path: String): String? =
        storedFile(path)?.let { ImportedSubtitleStorage.absolutePath(it.relativePath) }

    /**
     * Text of a stored subtitle, looked up by the path this repository published.
     * Matching against the index first keeps this from reading anything else.
     */
    fun readSubtitleText(path: String): String? =
        storedFile(path)?.let { ImportedSubtitleStorage.readText(it.relativePath) }

    private fun storedFile(path: String): ImportedSubtitleFile? {
        val fileName = path.substringAfterLast('/')
        val packId = path.removeSuffix("/$fileName").substringAfterLast('/')
        if (fileName.isBlank() || packId.isBlank()) return null
        val relativePath = "$packId/$fileName"
        return _uiState.value.packs
            .asSequence()
            .flatMap { it.files.asSequence() }
            .firstOrNull { it.relativePath == relativePath }
    }

    private fun ImportedSubtitlePack.placed(meta: MetaDetails?): ImportedSubtitlePack =
        copy(
            files = ImportedSubtitleMatcher.place(
                files = files,
                meta = meta,
                seasonHint = ImportedSubtitleMatcher.seasonHint(sourceName) ?: mapperSeason,
                seasonOverride = seasonOverride,
                episodeOffset = episodeOffset,
                isMovie = metaType.equals("movie", ignoreCase = true),
                metaId = metaId,
            ),
        )

    /**
     * Drops a pack once every episode it covers has been watched. A pack whose
     * files never matched an episode is left alone: there is nothing to compare
     * it against, and deleting it would lose a file the user could still place.
     */
    private fun removeWatchedPacks(watchedKeys: Set<String>) {
        val expired = _uiState.value.packs.filter { pack ->
            if (pack.keepAfterWatching) return@filter false
            val matched = pack.files.filter { it.isMatched }
            if (matched.isEmpty() || matched.size != pack.files.size) return@filter false
            matched.all { file ->
                watchedItemKeys(
                    type = pack.metaType,
                    id = pack.metaId,
                    season = file.season,
                    episode = file.episode,
                ).any(watchedKeys::contains)
            }
        }
        if (expired.isEmpty()) return

        expired.forEach { pack ->
            ImportedSubtitleStorage.deletePack(pack.id)
            log.i { "Removed watched subtitle pack for ${pack.showName}" }
        }
        val expiredIds = expired.mapTo(mutableSetOf()) { it.id }
        publish(_uiState.value.packs.filterNot { it.id in expiredIds })
    }

    private fun publish(packs: List<ImportedSubtitlePack>) {
        val ordered = packs.sortedWith(
            compareBy({ it.showName.lowercase() }, { it.importedAt }),
        )
        _uiState.update { it.copy(packs = ordered, loaded = true) }
        runCatching { ImportedSubtitleStorage.saveIndex(json.encodeToString(ordered)) }
            .onFailure { error -> log.w(error) { "Could not save the subtitle index" } }
        AddonRepository.syncVirtualAddons()
    }

    private fun loadPacks(): List<ImportedSubtitlePack> {
        val payload = ImportedSubtitleStorage.loadIndex()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<ImportedSubtitlePack>>(payload) }
            .getOrElse { error ->
                log.w(error) { "Could not read the subtitle index" }
                emptyList()
            }
    }
}

/** Long enough for two lookups on a slow connection, short enough not to stall. */
private const val SEASON_LOOKUP_TIMEOUT_MS = 8_000L

/** A stored subtitle that answers for the episode being played. */
internal data class ImportedSubtitleMatch(
    val pack: ImportedSubtitlePack,
    val file: ImportedSubtitleFile,
)
