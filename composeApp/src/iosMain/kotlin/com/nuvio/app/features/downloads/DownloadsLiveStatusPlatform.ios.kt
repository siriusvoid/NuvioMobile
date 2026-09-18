package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.downloads_live_progress_count
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

/**
 * Feeds the downloads Live Activity. Downloads started together show as one
 * batch — "3 of 12", with the batch's combined size — rather than whichever
 * file moved last.
 */
internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null

    private var batchId: String? = null
    private var batchStartEpochMs = 0L

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val payload = buildPayload(items)?.let(json::encodeToString)

        if (payload == lastPayload) return
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun buildPayload(items: List<DownloadItem>): DownloadsLiveStatusPayload? {
        val active = items.filter { it.status != DownloadStatus.Completed }
        if (active.isEmpty()) {
            batchId = null
            return null
        }

        // A batch lasts while anything in it is unfinished; downloads added meanwhile join it.
        val currentBatchId = batchId ?: active.minBy { it.createdAtEpochMs }.id.also { id ->
            batchId = id
            batchStartEpochMs = active.minOf { it.createdAtEpochMs }
        }
        val batch = items.filter { it.createdAtEpochMs >= batchStartEpochMs || it.status != DownloadStatus.Completed }
        val completed = batch.count { it.status == DownloadStatus.Completed }

        val downloadedBytes = batch.sumOf { item ->
            if (item.status == DownloadStatus.Completed) item.totalBytes ?: item.downloadedBytes else item.downloadedBytes
        }
        val totalBytes = batch.takeIf { list -> list.all { (it.totalBytes ?: 0L) > 0L } }?.sumOf { it.totalBytes ?: 0L }
        val fraction = when {
            totalBytes != null -> (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
            batch.size > 1 -> completed.toDouble() / batch.size.toDouble()
            else -> null
        }

        val status = when {
            active.any { it.status == DownloadStatus.Downloading } -> DownloadStatus.Downloading
            active.any { it.status == DownloadStatus.Paused } -> DownloadStatus.Paused
            else -> DownloadStatus.Failed
        }

        val primary = active
            .sortedWith(compareBy<DownloadItem> { statusPriority(it.status) }.thenBy { it.createdAtEpochMs })
            .first()
        val isBatch = batch.size > 1
        val sameShow = batch.map { it.parentMetaId }.distinct().size == 1

        return DownloadsLiveStatusPayload(
            id = currentBatchId,
            title = when {
                !isBatch || sameShow -> primary.title
                else -> runBlocking { getString(Res.string.compose_settings_root_downloads_title) }
            },
            subtitle = if (isBatch) "" else primary.displaySubtitle,
            detail = if (isBatch) {
                val count = runBlocking { getString(Res.string.downloads_live_progress_count, completed, batch.size) }
                listOfNotNull(count, primary.episodeLabel()).joinToString(" · ")
            } else {
                null
            },
            status = status.name,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = fraction?.let { (it * 100.0).toInt().coerceIn(0, 100) } ?: -1,
        )
    }

    private fun DownloadItem.episodeLabel(): String? {
        val season = seasonNumber ?: return null
        val episode = episodeNumber ?: return null
        return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    }

    private fun statusPriority(status: DownloadStatus): Int = when (status) {
        DownloadStatus.Downloading -> 0
        DownloadStatus.Paused -> 1
        DownloadStatus.Failed -> 2
        DownloadStatus.Completed -> 3
    }
}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    /** "3 of 12 · S01E04" for a batch; changes as it goes, unlike the title. */
    val detail: String? = null,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val progressPercent: Int,
)
