package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateCanceling
import platform.Foundation.NSURLSessionTaskStateCompleted
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val BACKGROUND_SESSION_IDENTIFIER = "com.nuvio.app.downloads"
private const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0
private const val PROGRESS_MIN_INTERVAL_SECONDS = 0.5
private const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L
/**
 * Transfers handed to iOS at once; the rest wait here. The session's own
 * per-host limit is ignored by the background download daemon.
 */
private const val MAX_ACTIVE_TRANSFERS = 3

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

/**
 * iOS relaunches the app, in the background if need be, when a transfer of the
 * download session finishes while the app is not running. Recreating the
 * session reconnects it to its delegate; the handler is called once every
 * event has been delivered.
 */
fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    if (identifier != BACKGROUND_SESSION_IDENTIFIER) {
        completionHandler()
        return
    }
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    BackgroundDownloads.ensureSession()
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val downloadId = request.item.id
        onMain {
            createParentDirectory("${DownloadFolder.directoryPath()}/${request.destinationFileName}")
            DownloadSubtitlePreparation.start(downloadId, request)
            BackgroundDownloads.start(
                downloadId = downloadId,
                request = request,
                callbacks = DownloadCallbacks(onProgress, onSuccess, onFailure),
            )
        }
        return IosDownloadsTaskHandle(downloadId)
    }

    // A download left running keeps going in the system's download daemon, so it
    // stays Downloading; starting it again reattaches to that transfer.
    actual fun restoreItem(item: DownloadItem): DownloadItem = item

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path).also { removeFolderIfEmpty(path) }
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${DownloadFolder.directoryPath()}/$fileName")
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val destinationPath = "${DownloadFolder.directoryPath()}/$destinationFileName"
        DownloadSubtitles.remove(NSURL.fileURLWithPath(destinationPath).absoluteString!!)
        onMain { BackgroundDownloads.discard(destinationFileName) }
        removePathIfExists(resumeDataPath(destinationFileName))
        // Left by the data-task downloader this one replaced.
        return removePathIfExists("$destinationPath.part").also { removeFolderIfEmpty(destinationPath) }
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path ->
                return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
            }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        // The folder may have changed since; app storage is where everything went before.
        return listOf(DownloadFolder.directoryPath(), appStorageDirectoryPath())
            .distinct()
            .map { "$it/$fileName" }
            .firstOrNull { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.toFileUri()
    }

    actual fun isFileGone(localFileUri: String?, destinationFileName: String): Boolean {
        if (resolveLocalFileUri(localFileUri, destinationFileName) != null) return false
        val path = localFileUri?.toLocalPath() ?: return false
        val manager = NSFileManager.defaultManager

        // iCloud Drive offloads a file and leaves `.name.icloud` in its place.
        val name = path.substringAfterLast('/')
        if (manager.fileExistsAtPath("${path.substringBeforeLast('/')}/.$name.icloud")) return false

        // The folder it was saved into must be reachable, or nothing is known about the
        // file. App storage always is — under a new container path after an app update,
        // which the lookup above has already tried. A chosen folder may not be (a drive
        // unplugged), so its own path is checked.
        if (path.contains("/Documents/nuvio_downloads/")) return true
        val downloadsRoot = path.removeSuffix("/${destinationFileName.trim()}")
        if (downloadsRoot == path || !manager.fileExistsAtPath(downloadsRoot)) return false
        return true
    }

    actual fun isFolderGone(destinationFileName: String): Boolean {
        val folder = destinationFileName.substringBeforeLast('/', missingDelimiterValue = "")
        // Flat names from before per-show folders, or a chosen folder out of reach.
        if (folder.isBlank() || DownloadFolder.isChosenFolderUnavailable()) return false
        return !NSFileManager.defaultManager.fileExistsAtPath("${DownloadFolder.directoryPath()}/$folder")
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(DownloadFolder.directoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }
}

private class IosDownloadsTaskHandle(
    private val downloadId: String,
) : DownloadsTaskHandle {
    override fun cancel() {
        onMain {
            DownloadSubtitlePreparation.cancel(downloadId)
            BackgroundDownloads.pause(downloadId)
        }
    }
}

private class DownloadCallbacks(
    val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    val onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    val onFailure: (message: String) -> Unit,
)

/**
 * Subtitles saved with a download — the stream's own and the user's imported
 * ones — are prepared while the video transfers. The transfer itself has no
 * coroutine to hang that on, so each preparation is tracked per download and
 * cancelled with it. Main thread only.
 */
private object DownloadSubtitlePreparation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    fun start(downloadId: String, request: DownloadPlatformRequest) {
        jobs.remove(downloadId)?.cancel()
        val destinationPath = "${DownloadFolder.directoryPath()}/${request.destinationFileName}"
        val destinationUri = NSURL.fileURLWithPath(destinationPath).absoluteString ?: return
        val job = scope.launch {
            try {
                DownloadSubtitles.prepare(request.item, destinationUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Bundled subtitles are best effort; the video download never fails over them.
            }
        }
        jobs[downloadId] = job
        job.invokeOnCompletion {
            onMain { if (jobs[downloadId] === job) jobs.remove(downloadId) }
        }
    }

    fun cancel(downloadId: String) {
        jobs.remove(downloadId)?.cancel()
    }
}

/**
 * Downloads run as tasks of one background URLSession, so the system keeps them
 * going while the app is suspended or not running at all.
 *
 * Each task carries its download id and file name in its description. That is
 * enough to finish a transfer that outlived the process which started it: the
 * file is moved into place, and the next start of that download finds it there.
 *
 * All bookkeeping lives on the main thread. The delegate queue only moves the
 * finished file, which can be slow when the downloads folder is on another volume.
 */
@OptIn(ExperimentalForeignApi::class)
private val BackgroundDownloads: BackgroundDownloadsCoordinator by lazy { BackgroundDownloadsCoordinator() }

private class ActiveDownload(
    val task: NSURLSessionDownloadTask,
    val fileName: String,
    /** Null for a transfer adopted from an earlier process until the repository starts it again. */
    var request: DownloadPlatformRequest?,
    var callbacks: DownloadCallbacks?,
    val resumedFromData: Boolean,
) {
    var lastProgressBytes = -1L
    var lastProgressTimestampSeconds = 0.0

    /**
     * Whether a delegate callback's task is this one. Compared by identifier: the
     * task object handed to the delegate is not guaranteed to be the same Kotlin
     * reference as the one the session returned.
     */
    fun owns(other: NSURLSessionTask): Boolean = task.taskIdentifier == other.taskIdentifier
}

/** A pause waiting for its resume data; a start requested meanwhile runs once it is written. */
private class PendingPause(val fileName: String) {
    var discarded = false
    var deferredStart: (() -> Unit)? = null
}

@OptIn(ExperimentalForeignApi::class)
private class BackgroundDownloadsCoordinator : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private var session: NSURLSession? = null
    private var existingTasksLoaded = false
    private val startsAwaitingExistingTasks = linkedMapOf<String, () -> Unit>()
    private val active = mutableMapOf<String, ActiveDownload>()

    /**
     * Starts past [MAX_ACTIVE_TRANSFERS], so a season comes down a few episodes at
     * a time and a server capping connections isn't hit with all of them. One that
     * starts while the app is in the background is left to iOS to schedule, which
     * may wait until the app is opened again.
     */
    private val queued = mutableMapOf<String, QueuedStart>()

    private class QueuedStart(val item: DownloadItem, val start: () -> Unit)
    private val pendingPauses = mutableMapOf<String, PendingPause>()
    private val discardedFileNames = mutableSetOf<String>()

    fun ensureSession(): NSURLSession {
        session?.let { return it }
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(
            BACKGROUND_SESSION_IDENTIFIER,
        ).apply {
            timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
            allowsCellularAccess = true
            allowsExpensiveNetworkAccess = true
            allowsConstrainedNetworkAccess = true
            sessionSendsLaunchEvents = true
            discretionary = false
        }
        val created = NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = this,
            delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
        session = created
        created.getAllTasksWithCompletionHandler { tasks ->
            val downloadTasks = tasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
            onMain { adoptExistingTasks(downloadTasks) }
        }
        return created
    }

    /** Transfers still running from an earlier process, so a start reattaches instead of duplicating them. */
    private fun adoptExistingTasks(tasks: List<NSURLSessionDownloadTask>) {
        tasks.forEach { task ->
            if (task.state == NSURLSessionTaskStateCompleted || task.state == NSURLSessionTaskStateCanceling) {
                return@forEach
            }
            val (downloadId, fileName) = task.downloadIdentity() ?: return@forEach
            if (downloadId in active) return@forEach
            active[downloadId] = ActiveDownload(
                task = task,
                fileName = fileName,
                request = null,
                callbacks = null,
                resumedFromData = false,
            )
        }
        existingTasksLoaded = true
        val starts = startsAwaitingExistingTasks.values.toList()
        startsAwaitingExistingTasks.clear()
        starts.forEach { it() }
    }

    fun start(
        downloadId: String,
        request: DownloadPlatformRequest,
        callbacks: DownloadCallbacks,
    ) {
        val session = ensureSession()
        queued.remove(downloadId)
        if (!existingTasksLoaded) {
            startsAwaitingExistingTasks[downloadId] = { start(downloadId, request, callbacks) }
            return
        }
        pendingPauses[downloadId]?.let { pending ->
            pending.deferredStart = { start(downloadId, request, callbacks) }
            return
        }

        val fileName = request.destinationFileName
        discardedFileNames.remove(fileName)

        active[downloadId]?.let { running ->
            running.request = request
            running.callbacks = callbacks
            return
        }

        // Finished while no callbacks were attached, e.g. with the app in the background.
        val destinationPath = "${DownloadFolder.directoryPath()}/$fileName"
        if (NSFileManager.defaultManager.fileExistsAtPath(destinationPath)) {
            callbacks.onSuccess(destinationPath.toFileUri(), fileSizeOrNull(destinationPath))
            return
        }

        if (active.size >= MAX_ACTIVE_TRANSFERS) {
            queued[downloadId] = QueuedStart(request.item) { start(downloadId, request, callbacks) }
            return
        }

        val resumeData = takeResumeData(fileName)
        val task = if (resumeData != null) {
            session.downloadTaskWithResumeData(resumeData)
        } else {
            removePathIfExists("$destinationPath.part")
            session.downloadTaskWithRequest(buildRequest(request))
        }
        task.taskDescription = downloadIdentity(downloadId, fileName)
        active[downloadId] = ActiveDownload(
            task = task,
            fileName = fileName,
            request = request,
            callbacks = callbacks,
            resumedFromData = resumeData != null,
        )
        if (resumeData == null) callbacks.onProgress(0L, null)
        task.resume()
    }

    fun pause(downloadId: String) {
        startsAwaitingExistingTasks.remove(downloadId)
        queued.remove(downloadId)
        pendingPauses[downloadId]?.deferredStart = null
        val download = active.remove(downloadId) ?: return
        startQueued()

        val pending = PendingPause(download.fileName)
        pendingPauses[downloadId] = pending
        download.task.cancelByProducingResumeData { resumeData ->
            onMain {
                if (pendingPauses[downloadId] === pending) pendingPauses.remove(downloadId)
                if (resumeData != null && !pending.discarded) {
                    writeResumeData(pending.fileName, resumeData)
                }
                pending.deferredStart?.invoke()
            }
        }
    }

    /** Fills free transfer slots from the queue, oldest download first, then by episode. */
    private fun startQueued() {
        while (active.size < MAX_ACTIVE_TRANSFERS && queued.isNotEmpty()) {
            val (downloadId, next) = queued.entries
                .minWith(
                    compareBy<Map.Entry<String, QueuedStart>>(
                        { it.value.item.createdAtEpochMs },
                        { it.value.item.seasonNumber ?: 0 },
                        { it.value.item.episodeNumber ?: 0 },
                    ),
                )
                .toPair()
            queued.remove(downloadId)
            next.start()
        }
    }

    /** The download is gone: drop its resume data, and its file should a transfer still land it. */
    fun discard(fileName: String) {
        discardedFileNames += fileName
        pendingPauses.values
            .filter { it.fileName == fileName }
            .forEach { it.discarded = true }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didResumeAtOffset: Long,
        expectedTotalBytes: Long,
    ) {
        onMain {
            reportProgress(downloadTask, didResumeAtOffset, expectedTotalBytes, force = true)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        onMain {
            reportProgress(downloadTask, totalBytesWritten, totalBytesExpectedToWrite, force = false)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        // The system deletes the temporary file once this returns, so it is moved here and now.
        val temporaryPath = didFinishDownloadingToURL.path.orEmpty()
        val (downloadId, fileName) = downloadTask.downloadIdentity() ?: run {
            removePathIfExists(temporaryPath)
            return
        }

        val statusCode = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        if (statusCode !in 200..299) {
            removePathIfExists(temporaryPath)
            onMain {
                finishWithFailure(
                    downloadTask,
                    runBlocking { getString(Res.string.network_request_failed_http, statusCode) },
                )
            }
            return
        }

        val destinationPath = "${DownloadFolder.directoryPath()}/$fileName"
        removePathIfExists(destinationPath)
        createParentDirectory(destinationPath)
        val moved = NSFileManager.defaultManager.moveItemAtPath(temporaryPath, destinationPath, null)
        if (!moved) {
            removePathIfExists(temporaryPath)
            onMain {
                finishWithFailure(
                    downloadTask,
                    runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                )
            }
            return
        }

        val finalSize = fileSizeOrNull(destinationPath)
        onMain {
            if (fileName in discardedFileNames) {
                removePathIfExists(destinationPath)
                return@onMain
            }
            val download = active[downloadId]?.takeIf { it.owns(downloadTask) } ?: return@onMain
            active.remove(downloadId)
            download.callbacks?.onSuccess(destinationPath.toFileUri(), finalSize)
            startQueued()
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val error = didCompleteWithError ?: return
        val resumeData = error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData
        val isCancelled = error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled
        val receivedAnyBytes = task.countOfBytesReceived > 0L

        onMain {
            val (downloadId, fileName) = task.downloadIdentity() ?: return@onMain
            val download = active[downloadId]
            if (download == null || !download.owns(task)) {
                // Paused, replaced, or a transfer cancelled while the app was not running
                // (a force quit): keep its resume data unless something newer owns the download.
                if (
                    resumeData != null &&
                    download == null &&
                    downloadId !in pendingPauses &&
                    fileName !in discardedFileNames
                ) {
                    writeResumeData(fileName, resumeData)
                }
                return@onMain
            }

            active.remove(downloadId)
            val request = download.request
            val callbacks = download.callbacks
            if (download.resumedFromData && resumeData == null && !receivedAnyBytes && !isCancelled) {
                // The resume data was refused (the file changed on the server, say): start over.
                if (request != null && callbacks != null) start(downloadId, request, callbacks)
                return@onMain
            }

            if (resumeData != null) writeResumeData(fileName, resumeData)
            callbacks?.onFailure(
                error.localizedDescription.ifBlank { runBlocking { getString(Res.string.download_failed) } },
            )
            startQueued()
        }
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        onMain {
            val identifier = session.configuration.identifier ?: return@onMain
            backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
        }
    }

    private fun finishWithFailure(task: NSURLSessionDownloadTask, message: String) {
        val (downloadId, _) = task.downloadIdentity() ?: return
        val download = active[downloadId]?.takeIf { it.owns(task) } ?: return
        active.remove(downloadId)
        download.callbacks?.onFailure(message)
        startQueued()
    }

    private fun reportProgress(
        task: NSURLSessionDownloadTask,
        downloadedBytes: Long,
        expectedBytes: Long,
        force: Boolean,
    ) {
        val (downloadId, _) = task.downloadIdentity() ?: return
        val download = active[downloadId]?.takeIf { it.owns(task) } ?: return
        val callbacks = download.callbacks ?: return
        val totalBytes = expectedBytes.takeIf { it > 0L }
        val now = NSDate().timeIntervalSince1970
        val reachedEnd = totalBytes != null && downloadedBytes >= totalBytes
        if (
            !force &&
            download.lastProgressBytes >= 0L &&
            !reachedEnd &&
            downloadedBytes - download.lastProgressBytes < PROGRESS_MIN_BYTE_DELTA &&
            now - download.lastProgressTimestampSeconds < PROGRESS_MIN_INTERVAL_SECONDS
        ) {
            return
        }
        download.lastProgressBytes = downloadedBytes
        download.lastProgressTimestampSeconds = now
        callbacks.onProgress(downloadedBytes.coerceAtLeast(0L), totalBytes)
    }

    private fun buildRequest(request: DownloadPlatformRequest): NSMutableURLRequest {
        val nativeRequest = NSMutableURLRequest(
            uRL = NSURL(string = request.sourceUrl),
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS,
        )
        nativeRequest.setHTTPMethod("GET")
        request.sourceHeaders.forEach { (key, value) ->
            nativeRequest.setValue(value, forHTTPHeaderField = key)
        }
        return nativeRequest
    }
}

private fun downloadIdentity(downloadId: String, fileName: String): String = "$downloadId\n$fileName"

private fun NSURLSessionTask.downloadIdentity(): Pair<String, String>? {
    val description = taskDescription ?: return null
    val downloadId = description.substringBefore('\n', missingDelimiterValue = "")
    val fileName = description.substringAfter('\n', missingDelimiterValue = "")
    if (downloadId.isBlank() || fileName.isBlank()) return null
    return downloadId to fileName
}

private fun onMain(block: () -> Unit) {
    if (NSThread.isMainThread) {
        block()
    } else {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

private fun String.toFileUri(): String = NSURL.fileURLWithPath(this).absoluteString ?: "file://$this"

@OptIn(ExperimentalForeignApi::class)
private fun resumeDataPath(fileName: String): String {
    val directory = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/nuvio_download_resume"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    // The file name may carry its show folder; the resume data sits flat.
    val key = fileName.replace("%", "%25").replace("/", "%2F")
    return "$directory/$key.resumedata"
}

@OptIn(ExperimentalForeignApi::class)
private fun createParentDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path.substringBeforeLast('/'),
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

/** Drops a show or movie folder once its last file is gone; never a downloads folder itself. */
@OptIn(ExperimentalForeignApi::class)
private fun removeFolderIfEmpty(filePath: String) {
    val folder = filePath.substringBeforeLast('/')
    if (folder == DownloadFolder.directoryPath().trimEnd('/') || folder == appStorageDirectoryPath().trimEnd('/')) return
    val manager = NSFileManager.defaultManager
    val remaining = manager.contentsOfDirectoryAtPath(folder, null)
        ?.filterIsInstance<String>()
        ?.filterNot { it == ".DS_Store" }
        ?: return
    if (remaining.isEmpty()) manager.removeItemAtPath(folder, null)
}

private fun writeResumeData(fileName: String, data: NSData) {
    data.writeToFile(resumeDataPath(fileName), atomically = true)
}

private fun takeResumeData(fileName: String): NSData? {
    val path = resumeDataPath(fileName)
    val data = NSData.dataWithContentsOfFile(path)
    removePathIfExists(path)
    return data?.takeIf { it.length > 0uL }
}

@OptIn(ExperimentalForeignApi::class)
private fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}
