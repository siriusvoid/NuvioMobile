package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    /**
     * True only when a finished download's file is known to be deleted, as opposed
     * to out of reach for now (a drive unplugged, a file iCloud has offloaded).
     */
    fun isFileGone(localFileUri: String?, destinationFileName: String): Boolean

    /**
     * True when an unfinished download's show or movie folder was deleted. The folder
     * is made when the download starts, so it can only be missing if removed.
     */
    fun isFolderGone(destinationFileName: String): Boolean

    fun openDownloadsDirectory(): Boolean
}
