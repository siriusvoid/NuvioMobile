package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.isIos
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        hasLoaded = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val displayTitle = title.ifBlank { stream.streamLabel }
        val isEpisode = seasonNumber != null && episodeNumber != null
        val baseName = buildBaseFileName(
            title = displayTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            releaseYear = if (isEpisode) null else releaseYear(parentMetaType, parentMetaId),
        )
        val fileName = uniqueFileName(
            // A show's episodes share a folder named after it; a movie gets its own.
            folder = if (isIos) {
                if (isEpisode) displayTitle.toSafeFileName().ifBlank { "Download" }.fitFileNameLimit() else baseName.fitFileNameLimit()
            } else {
                null
            },
            baseName = baseName,
            extension = sourceUrl.fileExtensionFromUrl(),
            takenNames = currentItems.mapTo(mutableSetOf()) { it.fileName.lowercase() },
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            // Known up front when the source lists it, so queued downloads count toward the total.
            totalBytes = stream.behaviorHints.videoSize?.takeIf { it > 0L },
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    /** Status of the download already made or running for an episode, if there is one. */
    fun episodeDownloadStatus(parentMetaId: String, seasonNumber: Int, episodeNumber: Int): DownloadStatus? {
        ensureLoaded()
        val key = buildLogicalKey(parentMetaId, seasonNumber, episodeNumber)
        return _uiState.value.items.firstOrNull { it.logicalContentKey == key }?.status
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    fun pauseDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::pauseDownload)
    }

    /** Resumes paused and failed downloads; the queue then takes them in episode order. */
    fun resumeDownloads(downloadIds: Collection<String>) {
        downloadIds.forEach(::resumeDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
    }

    /**
     * Drops downloads deleted outside the app, in the Files app for example: finished
     * ones whose file is gone, and paused or failed ones whose folder is gone, along
     * with what is left of their subtitles, partial data and folder.
     */
    fun pruneMissingFiles() {
        ensureLoaded()
        val gone = _uiState.value.items.filter { item ->
            when (item.status) {
                DownloadStatus.Completed -> DownloadsPlatformDownloader.isFileGone(item.localFileUri, item.fileName)
                DownloadStatus.Paused, DownloadStatus.Failed -> DownloadsPlatformDownloader.isFolderGone(item.fileName)
                DownloadStatus.Downloading -> false
            }
        }
        if (gone.isEmpty()) return

        gone.forEach { DownloadsPlatformDownloader.removePartialFile(it.fileName) }
        val goneIds = gone.mapTo(mutableSetOf()) { it.id }
        publish(_uiState.value.items.filterNot { it.id in goneIds })
        persist()
    }

    /** Deletes several downloads, their files and subtitles with them, in one update. */
    fun deleteDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        val ids = downloadIds.toSet()
        val deleting = _uiState.value.items.filter { it.id in ids }
        if (deleting.isEmpty()) return

        deleting.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
        }
        publish(_uiState.value.items.filterNot { it.id in ids })
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach(::startDownload)
        pruneMissingFiles()
    }

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

/**
 * The title as the app shows it, the way media apps expect: `Title S01E01` for an
 * episode, `Title (2023)` for a movie. Its subtitles are named after it.
 */
private fun buildBaseFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    releaseYear: Int?,
): String {
    val name = title.toSafeFileName().ifBlank { "Download" }
    return when {
        seasonNumber != null && episodeNumber != null ->
            "$name S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
        releaseYear != null -> "$name ($releaseYear)"
        else -> name
    }
}

/**
 * Path below the downloads folder: `Show/Show S01E01.mkv` on iOS, the bare file
 * name elsewhere. Adds ` (2)`, ` (3)`… when another download already has the name.
 */
private fun uniqueFileName(folder: String?, baseName: String, extension: String, takenNames: Set<String>): String {
    val prefix = folder?.let { "$it/" }.orEmpty()
    val base = baseName.fitFileNameLimit()
    var candidate = "$prefix$base.$extension"
    var attempt = 2
    while (
        candidate.lowercase() in takenNames ||
        DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri = null, destinationFileName = candidate) != null
    ) {
        candidate = "$prefix$base ($attempt).$extension"
        attempt++
    }
    return candidate
}

private fun releaseYear(metaType: String, metaId: String): Int? {
    val meta = MetaDetailsRepository.peek(metaType, metaId) ?: return null
    return listOfNotNull(meta.releaseInfo)
        .firstNotNullOfOrNull { Regex("\\b(18|19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
}

/** `/` and `:` are the only characters iOS won't take in a file name; `: ` reads as ` - `. */
private fun String.toSafeFileName(): String =
    replace(Regex("\\s*:\\s*"), " - ")
        .replace('/', '-')
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimStart('.')

/**
 * Titles are kept whole. Only one longer than [MAX_BASE_NAME_BYTES] is shortened,
 * so that with a subtitle's label and extension added it still fits the 255-byte
 * file name limit and can be saved.
 */
private fun String.fitFileNameLimit(): String {
    var value = this
    while (value.encodeToByteArray().size > MAX_BASE_NAME_BYTES && value.isNotEmpty()) {
        value = value.dropLast(1)
    }
    return value.trimEnd()
}

private const val MAX_BASE_NAME_BYTES = 200

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
