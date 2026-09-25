package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.downloads_episode_code
import nuvio.composeapp.generated.resources.downloads_live_progress_count
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

/**
 * Feeds the system progress UI of downloads (DownloadsBackgroundTaskManager.swift).
 * Downloads started together show as one batch — "3 of 12", with the batch's
 * combined size — rather than whichever file moved last.
 */
internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"

    /** Where the Live Activity this replaced read its state; cleared so no stale copy stays behind. */
    private const val legacyPayloadKey = "nuvio.downloads.live_status.payload"

    /** Units a download counts for when the batch's size isn't known in bytes. */
    private const val UNITS_PER_ITEM = 1_000L

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null

    /** Creation time of the current batch's first download; null while nothing is unfinished. */
    private var batchStartEpochMs: Long? = null

    private var downloadingIds: Set<String> = emptySet()
    private var startToken = 0L

    init {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(legacyPayloadKey)
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        // Only a download that begins or resumes may bring the progress UI up again:
        // it can be shown only for something the user just started.
        val nowDownloading = items.filter { it.status == DownloadStatus.Downloading }.mapTo(mutableSetOf()) { it.id }
        if (!downloadingIds.containsAll(nowDownloading)) startToken++
        downloadingIds = nowDownloading

        val payload = buildPayload(items)?.let(json::encodeToString)
        if (payload == lastPayload) return
        lastPayload = payload

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, payload)
    }

    private fun buildPayload(items: List<DownloadItem>): DownloadsLiveStatusPayload? {
        val active = items.filter { it.status != DownloadStatus.Completed }
        if (active.isEmpty()) {
            batchStartEpochMs = null
            return null
        }

        // A batch lasts while anything in it is unfinished; downloads added meanwhile join it.
        val batchStart = batchStartEpochMs ?: active.minOf { it.createdAtEpochMs }.also { batchStartEpochMs = it }
        val batch = items.filter { it.createdAtEpochMs >= batchStart || it.status != DownloadStatus.Completed }
        val completed = batch.count { it.status == DownloadStatus.Completed }

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
        val (completedUnits, totalUnits) = progressUnits(batch)

        return DownloadsLiveStatusPayload(
            title = when {
                !isBatch || sameShow -> primary.title
                else -> runBlocking { getString(Res.string.compose_settings_root_downloads_title) }
            },
            subtitle = if (isBatch) {
                val count = runBlocking { getString(Res.string.downloads_live_progress_count, completed, batch.size) }
                listOfNotNull(count, primary.episodeLabel()).joinToString(" · ")
            } else {
                listOfNotNull(primary.episodeLabel(), primary.displaySubtitle.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
            },
            status = status.name,
            completedUnits = completedUnits,
            totalUnits = totalUnits,
            startToken = startToken,
        )
    }

    /**
     * Bytes when every size in the batch is known, so the bar moves with each chunk;
     * otherwise a share per download, filled by the bytes of those whose size is known.
     */
    private fun progressUnits(batch: List<DownloadItem>): Pair<Long, Long> {
        if (batch.all { (it.totalBytes ?: 0L) > 0L }) {
            val downloaded = batch.sumOf { item ->
                if (item.status == DownloadStatus.Completed) item.totalBytes ?: item.downloadedBytes else item.downloadedBytes
            }
            return downloaded to batch.sumOf { it.totalBytes ?: 0L }
        }
        val completed = batch.sumOf { item ->
            if (item.status == DownloadStatus.Completed) {
                UNITS_PER_ITEM
            } else {
                (item.progressFraction * UNITS_PER_ITEM).toLong()
            }
        }
        return completed to batch.size * UNITS_PER_ITEM
    }

    private fun DownloadItem.episodeLabel(): String? {
        val season = seasonNumber ?: return null
        val episode = episodeNumber ?: return null
        return runBlocking { getString(Res.string.downloads_episode_code, season, episode) }
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
    val title: String,
    /** "3 of 12 · S1 E4" for a batch, "S1 E4 · Episode title" for one download. */
    val subtitle: String,
    val status: String,
    val completedUnits: Long,
    val totalUnits: Long,
    /** Changes when a download begins or resumes. */
    val startToken: Long,
)
