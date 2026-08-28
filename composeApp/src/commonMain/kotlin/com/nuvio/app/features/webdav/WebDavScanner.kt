package com.nuvio.app.features.webdav

import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class WebDavScanResult(
    val folders: List<WebDavFolder>,
    val totalFolderCount: Int,
    val reusedFolderCount: Int,
    val windowStart: Int,
    val windowEnd: Int,
)

/**
 * Lists the newest folders on a debrid WebDAV share.
 *
 * Debrid shares are wide and shallow — one folder per torrent, thousands of them —
 * and both Real-Debrid and TorBox are slow and rate limited. So a scan is one
 * listing of the root plus at most [WebDavSource.windowSize] folder listings, run
 * two at a time, with folders whose modified time is unchanged reused from the
 * existing index instead of re-listed.
 */
internal class WebDavScanner(
    private val source: WebDavSource,
    private val client: WebDavClient,
) {
    private val log = Logger.withTag("WebDavScanner")

    private val prefix: String
        get() = listOf(
            WebDavUrl.decode(WebDavUrl.pathOf(source.baseUrl)),
            WebDavUrl.normalizeRootPath(source.rootPath),
        ).filter { it.isNotBlank() }.joinToString("/")

    suspend fun scanWindow(
        known: Map<String, WebDavFolder>,
        windowStart: Int = 0,
        onProgress: (done: Int, planned: Int, files: Int) -> Unit = { _, _, _ -> },
    ): Result<WebDavScanResult> {
        val rootEntries = client.listDirectory(source.rootPath).getOrElse { error ->
            return Result.failure(error)
        }

        val folderEntries = rootEntries
            .filter { it.isCollection }
            .sortedByDescending { it.lastModifiedEpochSeconds ?: Long.MIN_VALUE }

        val window = folderEntries
            .drop(windowStart)
            .take(source.windowSize.coerceAtLeast(1))

        if (window.isEmpty()) {
            return Result.success(
                WebDavScanResult(
                    folders = emptyList(),
                    totalFolderCount = folderEntries.size,
                    reusedFolderCount = 0,
                    windowStart = windowStart,
                    windowEnd = windowStart,
                ),
            )
        }

        val gate = Semaphore(MAX_CONCURRENT_LISTINGS)
        val tally = ScanTally(planned = window.size, onProgress = onProgress)

        val folders = coroutineScope {
            window.map { entry ->
                async {
                    val relativePath = entry.decodedPathRelativeTo(prefix)
                    val name = entry.decodedName()
                    val modifiedAt = entry.lastModifiedEpochSeconds

                    val cached = known[relativePath]
                    if (cached != null && cached.modifiedAt != null && cached.modifiedAt == modifiedAt) {
                        tally.record(fileCount = cached.files.size, reusedFolder = true)
                        return@async cached
                    }

                    val collected = gate.withPermit {
                        collectFiles(
                            path = relativePath,
                            depth = 0,
                        )
                    }
                    tally.record(fileCount = collected.videos.size, reusedFolder = false)

                    WebDavFolder(
                        sourceId = source.id,
                        path = relativePath,
                        name = name,
                        modifiedAt = modifiedAt,
                        files = collected.videos,
                        subtitles = collected.subtitles,
                    )
                }
            }.awaitAll()
        }

        return Result.success(
            WebDavScanResult(
                folders = folders.filter { it.files.isNotEmpty() },
                totalFolderCount = folderEntries.size,
                reusedFolderCount = tally.reusedFolders(),
                windowStart = windowStart,
                windowEnd = windowStart + window.size,
            ),
        )
    }

    /** Progress counters shared by the two concurrent listing coroutines. */
    private class ScanTally(
        private val planned: Int,
        private val onProgress: (done: Int, planned: Int, files: Int) -> Unit,
    ) {
        private val mutex = Mutex()
        private var done = 0
        private var files = 0
        private var reused = 0

        suspend fun record(fileCount: Int, reusedFolder: Boolean) {
            val snapshotDone: Int
            val snapshotFiles: Int
            mutex.withLock {
                done++
                files += fileCount
                if (reusedFolder) reused++
                snapshotDone = done
                snapshotFiles = files
            }
            onProgress(snapshotDone, planned, snapshotFiles)
        }

        fun reusedFolders(): Int = reused
    }

    private data class CollectedFiles(
        val videos: List<WebDavFile>,
        val subtitles: List<WebDavFile>,
    )

    /** Walks one torrent folder. Season packs sometimes nest, so a little depth is allowed. */
    private suspend fun collectFiles(path: String, depth: Int): CollectedFiles {
        if (depth > MAX_FOLDER_DEPTH) return CollectedFiles(emptyList(), emptyList())

        val entries = client.listDirectory(pathUnderRoot(path)).getOrElse { error ->
            log.w(error) { "Could not list $path" }
            return CollectedFiles(emptyList(), emptyList())
        }

        val videos = ArrayList<WebDavFile>()
        val subtitles = ArrayList<WebDavFile>()

        entries.filterNot { it.isCollection }.forEach { entry ->
            val name = entry.decodedName()
            val file = WebDavFile(
                fileName = name,
                url = WebDavUrl.resolveHref(source.baseUrl, entry.href),
                sizeBytes = entry.contentLength,
            )
            when {
                name.isVideoFile() &&
                    (entry.contentLength ?: Long.MAX_VALUE) >= MIN_VIDEO_BYTES &&
                    !name.looksLikeSample() -> videos.add(file)

                name.isSubtitleFile() -> subtitles.add(file)
            }
        }

        if (videos.size < MAX_FILES_PER_FOLDER) {
            entries.filter { it.isCollection }.forEach { child ->
                val childPath = child.decodedPathRelativeTo(prefix)
                if (childPath.isNotBlank() && childPath != path) {
                    val nested = collectFiles(childPath, depth + 1)
                    videos.addAll(nested.videos)
                    subtitles.addAll(nested.subtitles)
                }
            }
        }

        return CollectedFiles(
            videos = videos.take(MAX_FILES_PER_FOLDER),
            subtitles = subtitles.take(MAX_FILES_PER_FOLDER),
        )
    }

    private fun pathUnderRoot(relativePath: String): String {
        val root = WebDavUrl.normalizeRootPath(source.rootPath)
        return listOf(root, relativePath).filter { it.isNotBlank() }.joinToString("/")
    }

    private companion object {
        const val MAX_CONCURRENT_LISTINGS = 2
        const val MAX_FOLDER_DEPTH = 3
        const val MAX_FILES_PER_FOLDER = 400

        /**
         * Only obviously empty files are skipped. A size floor is the wrong tool for
         * junk: short specials are real content — Room Camp episodes run three minutes
         * and sit well under any threshold that would exclude a sample.
         */
        const val MIN_VIDEO_BYTES = 2L * 1024L * 1024L
    }
}
