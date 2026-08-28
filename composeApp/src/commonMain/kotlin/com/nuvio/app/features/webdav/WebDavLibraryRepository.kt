package com.nuvio.app.features.webdav

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.util.date.GMTDate
import kotlinx.serialization.json.Json

/** One folder as the review screen shows it. */
data class MatchReviewRow(
    val folderKey: String,
    val sourceId: String,
    val folderName: String,
    val fileCount: Int,
    val match: WebDavMatch?,
)

/**
 * Owns the WebDAV sources, their scans and the resulting matches.
 *
 * Everything here is device-local. The virtual addon that exposes the results to
 * the rest of the app is refreshed whenever the index changes.
 */
object WebDavLibraryRepository {
    private val log = Logger.withTag("WebDavLibrary")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(WebDavUiState())
    val uiState: StateFlow<WebDavUiState> = _uiState.asStateFlow()

    private val scanJobs = mutableMapOf<String, Job>()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        val sources = loadSources()
        WebDavSourceRegistry.publish(sources.map { it.id })
        _uiState.value = WebDavUiState(sources = sources, loaded = true)
        scope.launch { refreshCounts() }
    }

    fun hasEnabledSources(): Boolean = _uiState.value.sources.any { it.enabled }

    // ---------------------------------------------------------------- sources

    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String,
    ): WebDavConnectionResult {
        val normalizedBase = WebDavUrl.normalizeBaseUrl(baseUrl)
        if (normalizedBase.isBlank()) {
            return WebDavConnectionResult.Failure("Enter the server address first.")
        }
        val client = WebDavClient(normalizedBase, username.trim(), password.trim())
        return client.testConnection(WebDavUrl.normalizeRootPath(rootPath))
    }

    suspend fun addSource(
        provider: WebDavProvider,
        displayName: String,
        baseUrl: String,
        username: String,
        password: String,
        rootPath: String,
        windowSize: Int = WebDavSource.DEFAULT_WINDOW_SIZE,
    ): Result<WebDavSource> {
        val normalizedBase = WebDavUrl.normalizeBaseUrl(baseUrl)
        val effectiveUsername = (provider.fixedUsername ?: username).trim()
        val trimmedPassword = password.trim()
        val normalizedRoot = WebDavUrl.normalizeRootPath(rootPath)

        val test = WebDavClient(normalizedBase, effectiveUsername, trimmedPassword)
            .testConnection(normalizedRoot)
        if (test is WebDavConnectionResult.Failure) {
            return Result.failure(IllegalStateException(test.message))
        }

        val source = WebDavSource(
            id = "webdav-${GMTDate().timestamp}",
            providerId = provider.id,
            displayName = displayName.ifBlank { provider.displayName },
            baseUrl = normalizedBase,
            username = effectiveUsername,
            rootPath = normalizedRoot,
            windowSize = windowSize,
        )

        WebDavStorage.savePassword(source.id, trimmedPassword)
        val updated = _uiState.value.sources + source
        persistSources(updated)
        AddonRepository.syncWebDavAddon()
        scan(source.id)
        return Result.success(source)
    }

    fun removeSource(sourceId: String) {
        scanJobs.remove(sourceId)?.cancel()
        val updated = _uiState.value.sources.filterNot { it.id == sourceId }
        persistSources(updated)
        WebDavStorage.deletePassword(sourceId)
        scope.launch {
            WebDavIndex.deleteSource(sourceId)
            refreshCounts()
            AddonRepository.syncWebDavAddon()
        }
    }

    fun setEnabled(sourceId: String, enabled: Boolean) {
        val updated = _uiState.value.sources.map {
            if (it.id == sourceId) it.copy(enabled = enabled) else it
        }
        persistSources(updated)
        AddonRepository.syncWebDavAddon()
    }

    fun setWindowSize(sourceId: String, windowSize: Int) {
        val updated = _uiState.value.sources.map {
            if (it.id == sourceId) it.copy(windowSize = windowSize.coerceIn(10, 500)) else it
        }
        persistSources(updated)
    }

    fun playbackHeaders(sourceId: String): Map<String, String> {
        val source = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return emptyMap()
        val password = WebDavStorage.loadPassword(sourceId).orEmpty()
        return WebDavClient(source.baseUrl, source.username, password).playbackHeaders()
    }

    // ------------------------------------------------------------------ scans

    fun scan(sourceId: String, windowStart: Int = 0) {
        if (scanJobs[sourceId]?.isActive == true) return
        val source = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return

        scanJobs[sourceId] = scope.launch {
            publishProgress(sourceId) { it.copy(phase = ScanPhase.Listing, errorMessage = null) }

            val password = WebDavStorage.loadPassword(sourceId).orEmpty()
            val client = WebDavClient(source.baseUrl, source.username, password)
            val scanner = WebDavScanner(source, client)
            val known = WebDavIndex.folders(sourceId).associateBy { it.path }

            val result = scanner.scanWindow(
                known = known,
                windowStart = windowStart,
                onProgress = { done, planned, files ->
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.Folders,
                            foldersDone = done,
                            foldersPlanned = planned,
                            filesFound = files,
                        )
                    }
                },
            )

            result.fold(
                onSuccess = { scan ->
                    WebDavIndex.mergeFolders(sourceId, scan.folders)
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.Matching,
                            knownFolderCount = scan.totalFolderCount,
                        )
                    }
                    matchFolders(source, scan.folders)
                    markScanned(sourceId)
                    publishProgress(sourceId) { it.copy(phase = ScanPhase.Done) }
                    refreshCounts()
                    AddonRepository.syncWebDavAddon()
                },
                onFailure = { error ->
                    log.w(error) { "Scan failed for $sourceId" }
                    publishProgress(sourceId) {
                        it.copy(
                            phase = ScanPhase.Failed,
                            errorMessage = error.message ?: "The scan could not finish.",
                        )
                    }
                },
            )
        }
    }

    /**
     * Clears everything indexed for a source and scans it again. Needed when the scan
     * rules themselves change: unchanged folders are otherwise reused from the index
     * and never re-listed.
     */
    fun rebuild(sourceId: String) {
        scanJobs.remove(sourceId)?.cancel()
        scope.launch {
            WebDavIndex.deleteSource(sourceId)
            refreshCounts()
            scan(sourceId)
        }
    }

    // ---------------------------------------------------------------- matching

    private suspend fun matchFolders(source: WebDavSource, folders: List<WebDavFolder>) {
        val existing = WebDavIndex.matches()
        val resolved = ArrayList<WebDavMatch>()

        folders.forEachIndexed { index, folder ->
            val current = existing[folder.key]
            if (current != null &&
                (current.userSet || current.excluded ||
                    current.placementStep != PlacementStep.Unresolved)
            ) {
                return@forEachIndexed
            }

            val match = runCatching { resolveFolder(source, folder) }
                .getOrElse { error ->
                    log.w(error) { "Could not resolve ${folder.name}" }
                    null
                }
            if (match != null) resolved.add(match)

            publishProgress(source.id) {
                it.copy(phase = ScanPhase.Matching, matchesResolved = index + 1)
            }
        }

        WebDavIndex.putMatches(resolved)
    }

    private suspend fun resolveFolder(source: WebDavSource, folder: WebDavFolder): WebDavMatch? {
        val parsed = AnimeReleaseParser.parseFolder(folder.name)
        if (parsed.title.isBlank()) return null

        val files = folder.files

        val hits = AnimeSearchClient.search(parsed.title)
        if (hits.isEmpty()) return null

        val packSize = files.size
        val scored = hits
            .map { hit -> hit to scoreHit(hit, parsed, packSize) }
            .sortedByDescending { it.second }
        val (hit, confidence) = scored.first()
        if (confidence < MIN_CONFIDENCE) return null

        // Obscure titles have no entry in the mapper. The metadata addon serves anime id
        // spaces directly, so fall back to the search hit's own id rather than dropping
        // the folder — that is what left Natsuiro Kiseki unmatched entirely.
        val arm = ArmMappingClient.lookup(hit.source, hit.id)
        val isMovie = hit.isMovie || arm?.media.equals("MOVIE", ignoreCase = true)
        val contentType = if (isMovie) WebDavMatch.CONTENT_TYPE_MOVIE else WebDavMatch.CONTENT_TYPE_SERIES
        val contentId = pickContentId(arm, contentType, hit) ?: return null

        val meta = fetchMeta(contentType, contentId)

        if (contentType == WebDavMatch.CONTENT_TYPE_MOVIE) {
            return WebDavMatch(
                folderKey = folder.key,
                sourceId = source.id,
                folderPath = folder.path,
                contentId = contentId,
                contentType = contentType,
                title = hit.title,
                poster = hit.poster,
                metaName = meta?.name,
                metaPoster = meta?.poster,
                step = PlacementStep.MapperSeason.name,
                confidence = confidence,
            )
        }

        val episodes = meta.toEpisodeSlots()
        val firstEpisode = files
            .mapNotNull { AnimeReleaseParser.parseFile(it.fileName).episode }
            .minOrNull()
            ?: parsed.episodeRange?.first
            ?: parsed.episode

        val placement = EpisodePlacement.place(
            parsedEpisode = firstEpisode,
            parsedSeason = parsed.season,
            mapperSeason = arm?.season,
            packSize = packSize,
            entryStartEpochSeconds = hit.startDateEpochSeconds,
            episodes = episodes,
        )

        return WebDavMatch(
            folderKey = folder.key,
            sourceId = source.id,
            folderPath = folder.path,
            contentId = contentId,
            contentType = contentType,
            title = hit.title,
            poster = hit.poster,
            metaName = meta?.name,
            metaPoster = meta?.poster,
            season = placement?.season ?: arm?.season,
            // The offset falls out of placement: for a pack numbered inside its own
            // cour it is zero, and for an absolute-numbered long-runner it shifts the
            // whole folder onto the right season.
            episodeOffset = if (placement != null && firstEpisode != null) {
                placement.episode - firstEpisode
            } else {
                0
            },
            step = (placement?.step ?: PlacementStep.Unresolved).name,
            confidence = confidence,
        )
    }

    /**
     * The installed metadata addon's view of this item. Used for the episode list and
     * for the catalogue's name and artwork, so rows read the same as the details page.
     */
    private suspend fun fetchMeta(contentType: String, contentId: String): MetaDetails? =
        withTimeoutOrNull(META_TIMEOUT_MS) {
            MetaDetailsRepository.fetch(type = contentType, id = contentId, cacheResult = true)
        }

    private fun MetaDetails?.toEpisodeSlots(): List<EpisodeSlot> {
        val meta = this ?: return emptyList()
        return meta.videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            EpisodeSlot(
                season = season,
                episode = episode,
                releasedEpochSeconds = parseIsoDateToEpochSeconds(video.released),
            )
        }
    }

    private fun scoreHit(hit: AnimeSearchHit, parsed: ParsedRelease, packSize: Int): Float {
        val titleScore = hit.allTitles.maxOfOrNull { candidate ->
            AnimeReleaseParser.similarity(parsed.title, candidate)
        } ?: 0f

        var score = titleScore
        if (hit.episodeCount != null && packSize > 1 && hit.episodeCount == packSize) score += 0.12f
        if (parsed.episodeRange != null && hit.episodeCount == parsed.episodeRange.last) score += 0.08f

        // A cour is its own entry in the anime databases, so a season stated in the
        // release name should pull the matching entry up and push the others down.
        parsed.season?.let { season ->
            val titleSeason = hit.allTitles.firstNotNullOfOrNull { seasonNumberIn(it) }
            when {
                titleSeason == season -> score += 0.15f
                titleSeason != null -> score -= 0.20f
                season > 1 -> score -= 0.05f
            }
        }
        if (hit.subtype?.lowercase() in setOf("special", "ova", "ona") && !parsed.isSpecial) {
            score -= 0.15f
        }
        return score.coerceIn(0f, 1f)
    }

    private val ordinalSeasonInTitle =
        Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+Season", RegexOption.IGNORE_CASE)
    private val wordSeasonInTitle =
        Regex("\\bSeason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)

    /** The season a database title names, e.g. "2nd Season" or "Season 2". */
    private fun seasonNumberIn(title: String): Int? =
        ordinalSeasonInTitle.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: wordSeasonInTitle.find(title)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Emits the id space the installed metadata addon actually serves, so mapped
     * items are the same objects as the ones already in the user's catalogue.
     */
    private fun pickContentId(
        arm: ArmIds?,
        contentType: String,
        hit: AnimeSearchHit,
    ): String? {
        val candidates = buildList {
            arm?.imdb?.let { add(it) }
            arm?.themoviedb?.let { add("tmdb:$it") }
            arm?.thetvdb?.let { add("tvdb:$it") }
            arm?.kitsu?.let { add("kitsu:$it") }
            arm?.myanimelist?.let { add("mal:$it") }
            arm?.anilist?.let { add("anilist:$it") }
            arm?.anidb?.let { add("anidb:$it") }
            // Last resort: the id the search itself returned.
            when (hit.source) {
                AnimeSearchHit.SOURCE_KITSU -> add("kitsu:${hit.id}")
                AnimeSearchHit.SOURCE_MAL -> add("mal:${hit.id}")
            }
        }
        if (candidates.isEmpty()) return null

        val servedPrefixes = AddonRepository.uiState.value.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "meta" && resource.types.any { type ->
                        type == contentType || type.endsWith(".$contentType") || type == "anime"
                    }
                }
            }
            .flatMap { manifest ->
                manifest.resources.filter { it.name == "meta" }.flatMap { it.idPrefixes } +
                    manifest.idPrefixes
            }
            .filter { it.isNotBlank() }
            .distinct()

        return candidates.firstOrNull { candidate ->
            servedPrefixes.any { prefix -> candidate.startsWith(prefix) }
        } ?: candidates.first()
    }

    // ----------------------------------------------------------------- review

    /** Newest torrent first, matching the catalogue order. */
    suspend fun reviewRows(sourceId: String): List<MatchReviewRow> {
        val matches = WebDavIndex.matches()
        return WebDavIndex.folders(sourceId)
            .sortedByDescending { it.modifiedAt ?: Long.MIN_VALUE }
            .map { folder ->
                MatchReviewRow(
                    folderKey = folder.key,
                    sourceId = sourceId,
                    folderName = folder.name,
                    fileCount = folder.files.size,
                    match = matches[folder.key],
                )
            }
    }

    internal suspend fun searchForOverride(query: String): List<AnimeSearchHit> =
        AnimeSearchClient.search(query)

    /** Applies a manual correction. Rescans never overwrite it. */
    internal suspend fun applyOverride(
        folderKey: String,
        hit: AnimeSearchHit,
        season: Int?,
        episodeOffset: Int,
        treatAsMovie: Boolean,
    ): Result<WebDavMatch> {
        val sourceId = folderKey.substringBefore('|')
        val folderPath = folderKey.substringAfter('|')
        val arm = ArmMappingClient.lookup(hit.source, hit.id)
        val contentType = if (treatAsMovie || hit.isMovie) {
            WebDavMatch.CONTENT_TYPE_MOVIE
        } else {
            WebDavMatch.CONTENT_TYPE_SERIES
        }
        val contentId = pickContentId(arm, contentType, hit)
            ?: return Result.failure(IllegalStateException("No id mapping exists for ${hit.title}."))

        val match = WebDavMatch(
            folderKey = folderKey,
            sourceId = sourceId,
            folderPath = folderPath,
            contentId = contentId,
            contentType = contentType,
            title = hit.title,
            poster = hit.poster,
            season = season ?: arm?.season,
            episodeOffset = episodeOffset,
            step = PlacementStep.Manual.name,
            confidence = 1f,
            userSet = true,
        )
        WebDavIndex.putMatch(match)
        refreshCounts()
        AddonRepository.syncWebDavAddon()
        return Result.success(match)
    }

    suspend fun setExcluded(folderKey: String, excluded: Boolean) {
        val current = WebDavIndex.match(folderKey) ?: return
        WebDavIndex.putMatch(current.copy(excluded = excluded, userSet = true))
        refreshCounts()
        AddonRepository.syncWebDavAddon()
    }

    suspend fun rematch(folderKey: String) {
        val sourceId = folderKey.substringBefore('|')
        val source = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return
        val folder = WebDavIndex.folders(sourceId).firstOrNull { it.key == folderKey } ?: return
        WebDavIndex.removeMatch(folderKey)
        val match = runCatching { resolveFolder(source, folder) }.getOrNull()
        if (match != null) WebDavIndex.putMatch(match)
        refreshCounts()
        AddonRepository.syncWebDavAddon()
    }

    // ------------------------------------------------------------------ state

    private fun loadSources(): List<WebDavSource> {
        val payload = WebDavStorage.loadSources()
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<WebDavSource>>(payload) }
            .getOrElse { error ->
                log.w(error) { "Could not read stored WebDAV sources" }
                emptyList()
            }
    }

    private fun persistSources(sources: List<WebDavSource>) {
        _uiState.update { it.copy(sources = sources, loaded = true) }
        WebDavSourceRegistry.publish(sources.map { it.id })
        runCatching { WebDavStorage.saveSources(json.encodeToString(sources)) }
            .onFailure { log.w(it) { "Could not persist WebDAV sources" } }
    }

    private fun markScanned(sourceId: String) {
        val updated = _uiState.value.sources.map { source ->
            if (source.id == sourceId) {
                source.copy(lastScanAt = GMTDate().timestamp)
            } else {
                source
            }
        }
        persistSources(updated)
    }

    private fun publishProgress(
        sourceId: String,
        transform: (WebDavScanProgress) -> WebDavScanProgress,
    ) {
        _uiState.update { state ->
            val current = state.progress[sourceId] ?: WebDavScanProgress(sourceId = sourceId)
            state.copy(progress = state.progress + (sourceId to transform(current)))
        }
    }

    private suspend fun refreshCounts() {
        val matches = WebDavIndex.matches()
        val folderCounts = mutableMapOf<String, Int>()
        val fileCounts = mutableMapOf<String, Int>()
        val matchedCounts = mutableMapOf<String, Int>()

        _uiState.value.sources.forEach { source ->
            val folders = WebDavIndex.folders(source.id)
            folderCounts[source.id] = folders.size
            fileCounts[source.id] = folders.sumOf { it.files.size }
            matchedCounts[source.id] = folders.count { folder ->
                matches[folder.key]?.let { !it.excluded } == true
            }
        }

        _uiState.update {
            it.copy(
                folderCounts = folderCounts,
                fileCounts = fileCounts,
                matchedCounts = matchedCounts,
            )
        }
    }

    private const val MIN_CONFIDENCE = 0.55f
    private const val META_TIMEOUT_MS = 8_000L
}
