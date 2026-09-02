package com.nuvio.app.features.webdav

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Split of a Stremio video id into its content id and any trailing numbers. */
internal data class VideoIdParts(
    val contentId: String,
    val season: Int?,
    val episode: Int?,
)

private val PREFIXED_ID_SPACES = setOf(
    "kitsu", "mal", "myanimelist", "anilist", "anidb",
    "tmdb", "tvdb", "tvdbc", "tvmaze", "trakt", "webdav",
)

/**
 * Video ids differ by id space: Cinemeta-style ids carry three parts
 * (`tt0972656:4:1`) while anime addons carry two (`kitsu:6480:1`, episode with
 * no season). Both shapes have to parse, since the installed metadata addon can
 * serve either depending on how it is configured.
 */
internal fun parseVideoId(raw: String): VideoIdParts {
    val parts = raw.split(':')
    if (parts.isEmpty()) return VideoIdParts(raw, null, null)

    val prefixed = parts.size >= 2 && parts[0].lowercase() in PREFIXED_ID_SPACES
    val baseCount = if (prefixed) 2 else 1
    val contentId = parts.take(baseCount).joinToString(":")
    val trailing = parts.drop(baseCount).mapNotNull { it.toIntOrNull() }

    return when (trailing.size) {
        0 -> VideoIdParts(contentId, null, null)
        1 -> VideoIdParts(contentId, null, trailing[0])
        else -> VideoIdParts(contentId, trailing[0], trailing[1])
    }
}

/**
 * The scanned folders and their resolved matches.
 *
 * The window refreshes but the index accumulates: folders stay after they drop
 * out of the newest 50, so the library is not limited to what one scan can reach.
 *
 * Nothing here infers a deletion: a folder leaves only when the scanner has
 * confirmed it with the server, so a listing that quietly omits entries can never
 * remove anything.
 */
internal object WebDavIndex {
    private val log = Logger.withTag("WebDavIndex")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()
    private val foldersBySource = mutableMapOf<String, List<WebDavFolder>>()
    private val matchesByKey = mutableMapOf<String, WebDavMatch>()
    private var matchesLoaded = false
    private var reverseIndex: Map<String, List<String>>? = null

    suspend fun folders(sourceId: String): List<WebDavFolder> = mutex.withLock {
        loadFoldersLocked(sourceId)
    }

    suspend fun allFolders(): List<WebDavFolder> = mutex.withLock {
        foldersBySource.values.flatten()
    }

    /**
     * Merges a scan window into the accumulated index.
     *
     * [deletedPaths] are folders the server has confirmed are gone; their matches go
     * with them. Everything else is kept, whether or not this scan saw it.
     */
    suspend fun mergeFolders(
        sourceId: String,
        scanned: List<WebDavFolder>,
        deletedPaths: Set<String> = emptySet(),
    ) {
        mutex.withLock {
            loadMatchesLocked()
            val existing = loadFoldersLocked(sourceId)
            val byPath = LinkedHashMap<String, WebDavFolder>(existing.size + scanned.size)
            val dropped = ArrayList<String>()

            existing.forEach { folder ->
                if (folder.path in deletedPaths) dropped.add(folder.key) else byPath[folder.path] = folder
            }
            scanned.forEach { byPath[it.path] = it }

            val merged = byPath.values.toList()
            foldersBySource[sourceId] = merged
            reverseIndex = null
            persistFoldersLocked(sourceId, merged)

            if (dropped.isNotEmpty()) {
                dropped.forEach(matchesByKey::remove)
                persistMatchesLocked()
            }
        }
    }

    suspend fun deleteSource(sourceId: String) {
        mutex.withLock {
            foldersBySource.remove(sourceId)
            val removedKeys = matchesByKey.values
                .filter { it.sourceId == sourceId }
                .map { it.folderKey }
            removedKeys.forEach(matchesByKey::remove)
            reverseIndex = null
            WebDavStorage.deleteIndex(sourceId)
            persistMatchesLocked()
        }
    }

    suspend fun matches(): Map<String, WebDavMatch> = mutex.withLock {
        loadMatchesLocked()
        matchesByKey.toMap()
    }

    suspend fun match(folderKey: String): WebDavMatch? = mutex.withLock {
        loadMatchesLocked()
        matchesByKey[folderKey]
    }

    suspend fun putMatch(match: WebDavMatch) {
        mutex.withLock {
            loadMatchesLocked()
            matchesByKey[match.folderKey] = match
            reverseIndex = null
            persistMatchesLocked()
        }
    }

    suspend fun putMatches(matches: List<WebDavMatch>) {
        if (matches.isEmpty()) return
        mutex.withLock {
            loadMatchesLocked()
            matches.forEach { matchesByKey[it.folderKey] = it }
            reverseIndex = null
            persistMatchesLocked()
        }
    }

    suspend fun removeMatch(folderKey: String) {
        mutex.withLock {
            loadMatchesLocked()
            matchesByKey.remove(folderKey)
            reverseIndex = null
            persistMatchesLocked()
        }
    }

    /** Folders that resolved to [contentId], with their match. Used on every stream request. */
    suspend fun foldersForContentId(contentId: String): List<Pair<WebDavFolder, WebDavMatch>> =
        mutex.withLock {
            loadMatchesLocked()
            val index = reverseIndex ?: buildReverseIndexLocked()
            val folderKeys = index[contentId].orEmpty()
            if (folderKeys.isEmpty()) return@withLock emptyList()

            val allFolders = foldersBySource.values.flatten().associateBy { it.key }
            folderKeys.mapNotNull { key ->
                val match = matchesByKey[key] ?: return@mapNotNull null
                if (match.excluded) return@mapNotNull null
                val folder = allFolders[match.folderKey] ?: return@mapNotNull null
                folder to match
            }
        }

    /**
     * Every matched item, deduplicated by content id — the catalogue rows, newest
     * torrent first so recent additions are at the front.
     */
    suspend fun catalogEntries(sourceId: String?, contentType: String? = null): List<WebDavMatch> =
        mutex.withLock {
            loadMatchesLocked()
            ensureAllSourcesLoadedLocked()
            val modifiedByFolderKey = foldersBySource.values.flatten()
                .associate { it.key to (it.modifiedAt ?: Long.MIN_VALUE) }

            matchesByKey.values
                .asSequence()
                .filter { !it.excluded }
                .filter { contentType == null || it.contentType == contentType }
                .filter { sourceId == null || it.sourceId == sourceId }
                .groupBy { it.contentId }
                .map { (_, group) ->
                    group.maxBy { modifiedByFolderKey[it.folderKey] ?: Long.MIN_VALUE }
                }
                .sortedWith(
                    compareByDescending<WebDavMatch> {
                        modifiedByFolderKey[it.folderKey] ?: Long.MIN_VALUE
                    }.thenBy { it.title.lowercase() },
                )
                .toList()
        }

    private fun loadFoldersLocked(sourceId: String): List<WebDavFolder> {
        foldersBySource[sourceId]?.let { return it }
        val payload = WebDavStorage.loadIndex(sourceId)
        val folders = if (payload.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<WebDavFolder>>(payload) }
                .getOrElse { error ->
                    log.w(error) { "Could not read the index for $sourceId — starting empty" }
                    emptyList()
                }
        }
        foldersBySource[sourceId] = folders
        return folders
    }

    private fun ensureAllSourcesLoadedLocked() {
        WebDavSourceRegistry.knownSourceIds().forEach { sourceId ->
            if (!foldersBySource.containsKey(sourceId)) loadFoldersLocked(sourceId)
        }
    }

    private fun persistFoldersLocked(sourceId: String, folders: List<WebDavFolder>) {
        runCatching { WebDavStorage.saveIndex(sourceId, json.encodeToString(folders)) }
            .onFailure { log.w(it) { "Could not persist the index for $sourceId" } }
    }

    private fun loadMatchesLocked() {
        if (matchesLoaded) return
        matchesLoaded = true
        val payload = WebDavStorage.loadMatches()
        if (payload.isNullOrBlank()) return
        runCatching { json.decodeFromString<List<WebDavMatch>>(payload) }
            .onSuccess { stored -> stored.forEach { matchesByKey[it.folderKey] = it } }
            .onFailure { log.w(it) { "Could not read stored matches" } }
    }

    private fun persistMatchesLocked() {
        runCatching {
            WebDavStorage.saveMatches(json.encodeToString(matchesByKey.values.toList()))
        }.onFailure { log.w(it) { "Could not persist matches" } }
    }

    private fun buildReverseIndexLocked(): Map<String, List<String>> {
        ensureAllSourcesLoadedLocked()
        val index = matchesByKey.values
            .filterNot { it.excluded }
            .groupBy { it.contentId }
            .mapValues { (_, matches) -> matches.map { it.folderKey } }
        reverseIndex = index
        return index
    }
}

/** Lets the index load folder files for sources it has not been handed directly. */
internal object WebDavSourceRegistry {
    private var sourceIds: List<String> = emptyList()

    fun publish(ids: List<String>) {
        sourceIds = ids
    }

    fun knownSourceIds(): List<String> = sourceIds
}
