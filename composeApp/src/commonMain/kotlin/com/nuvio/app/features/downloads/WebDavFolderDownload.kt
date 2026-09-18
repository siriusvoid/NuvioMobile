package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.webdav.WebDavAddonService
import com.nuvio.app.features.webdav.WebDavFolder
import com.nuvio.app.features.webdav.WebDavIndex
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import com.nuvio.app.features.webdav.WebDavMatch
import com.nuvio.app.features.webdav.parseVideoId

/** One episode of a WebDAV folder, as a stream the normal download path takes. */
internal data class FolderEpisode(
    val season: Int,
    val episode: Int,
    val video: MetaVideo?,
    val stream: StreamItem,
    val sizeBytes: Long?,
)

/**
 * Every episode of the WebDAV folder a stream comes from. [pending] leaves out
 * episodes already downloaded or on their way, which a folder download skips.
 */
internal data class FolderDownloadPlan(
    val episodes: List<FolderEpisode>,
    val pending: List<FolderEpisode>,
) {
    /** Null when any pending file's size is unknown, so no partial total is shown as the whole. */
    val pendingBytes: Long? =
        pending.takeIf { list -> list.all { it.sizeBytes != null } }?.sumOf { it.sizeBytes ?: 0L }
}

internal object WebDavFolderDownload {
    /**
     * The plan for the folder holding [stream], or null when the stream isn't from a
     * WebDAV series folder with more than one episode in it.
     *
     * Each file is placed on its episode the way playback places it, so the folder
     * downloads what its episodes would play.
     */
    suspend fun plan(
        stream: StreamItem,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
    ): FolderDownloadPlan? {
        // Streams carry `addon:<manifest id>:<manifest url>`, not the bare manifest id.
        if (stream.addonId.split(':').getOrNull(1) != WebDavAddonService.ADDON_ID) return null
        val url = stream.directPlaybackUrl ?: return null
        val contentId = parseVideoId(videoId).contentId
        val (folder, match) = WebDavIndex.foldersForContentId(contentId)
            .firstOrNull { (folder, _) -> folder.files.any { it.url == url } }
            ?: return null
        if (!match.isSeries) return null

        val videos = MetaDetailsRepository.peek(parentMetaType, parentMetaId)?.videos.orEmpty()
        var episodes = folderEpisodes(folder, match, stream, videos)
        // Fewer episodes than the show has for these seasons: the saved listing may be
        // short, so the folder is listed again.
        val seasons = episodes.mapTo(mutableSetOf()) { it.season }
        if (episodes.size < videos.count { it.season in seasons }) {
            WebDavLibraryRepository.relistFolder(folder)?.let { relisted ->
                episodes = folderEpisodes(relisted, match, stream, videos)
            }
        }
        if (episodes.size < 2) return null

        val pending = episodes.filter { episode ->
            when (DownloadsRepository.episodeDownloadStatus(parentMetaId, episode.season, episode.episode)) {
                null, DownloadStatus.Failed -> true
                else -> false
            }
        }
        return FolderDownloadPlan(episodes = episodes, pending = pending)
    }

    private fun folderEpisodes(
        folder: WebDavFolder,
        match: WebDavMatch,
        stream: StreamItem,
        videos: List<MetaVideo>,
    ): List<FolderEpisode> =
        folder.files
            .mapNotNull { file ->
                val placement = WebDavAddonService.placeFile(file, match) ?: return@mapNotNull null
                val season = placement.season ?: 1
                FolderEpisode(
                    season = season,
                    episode = placement.episode,
                    video = videos.firstOrNull { it.season == season && it.episode == placement.episode },
                    stream = stream.copy(
                        url = file.url,
                        title = file.fileName,
                        description = "${folder.name}\n${file.fileName}",
                        behaviorHints = stream.behaviorHints.copy(
                            filename = file.fileName,
                            videoSize = file.sizeBytes,
                        ),
                    ),
                    sizeBytes = file.sizeBytes,
                )
            }
            // Two releases of one episode in a folder: the larger is the fuller copy.
            .groupBy { it.season to it.episode }
            .map { (_, copies) -> copies.maxBy { it.sizeBytes ?: 0L } }
            .sortedWith(compareBy({ it.season }, { it.episode }))

    /** Queues the pending episodes, in order, through the normal download path. */
    fun enqueue(
        plan: FolderDownloadPlan,
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
    ): Int = plan.pending.count { episode ->
        val result = DownloadsRepository.enqueueFromStream(
            contentType = contentType,
            videoId = episode.video?.id ?: "$parentMetaId:${episode.season}:${episode.episode}",
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            episodeTitle = episode.video?.title,
            episodeThumbnail = episode.video?.thumbnail,
            stream = episode.stream,
        )
        result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced
    }
}
