package com.nuvio.app.features.downloads

import com.nuvio.app.core.i18n.localizedImportedSubtitlesAddonName
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.subtitles.ImportedSubtitleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Subtitles saved with a download: the stream's own subtitles and the user's
 * imported ones for that episode. Subtitle addons are not saved.
 *
 * Files sit next to the video and are named after it, so other players pick them
 * up: `Title S01E01.ass` when there is one, `Title S01E01.AniLibria.ass` for each
 * when there are several. A hidden manifest beside them keeps each track's
 * language and label for the player.
 */
internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(item: DownloadItem, localVideoUri: String): Unit = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val previous = readManifest(storage)
        if (previous != null && previous.complete && previous.tracks.all { storage.localFileUri(it.fileName) != null }) {
            return@withContext
        }
        previous?.tracks?.forEach { storage.delete(it.fileName) }
        storage.delete(legacyDirectory(storage))

        val sources = importedSources(item) + fetchStreamSources(item.sourceSubtitles)
        currentCoroutineContext().ensureActive()

        val saved = mutableListOf<DownloadedSubtitle>()
        val fileNames = subtitleFileNames(storage.videoFileName, sources)
        sources.zip(fileNames).forEach { (source, fileName) ->
            val written = runCatching {
                when (val content = source.content) {
                    is SubtitleContent.File -> storage.copy(content.path, fileName)
                    is SubtitleContent.Text -> storage.write(fileName, content.text).let { true }
                }
            }.getOrDefault(false)
            if (written) {
                saved += DownloadedSubtitle(source.sourceUrl, fileName, source.language, source.name)
            }
        }
        runCatching {
            storage.write(manifestName(storage), json.encodeToString(SubtitleManifest(complete = true, tracks = saved)))
        }
        Unit
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage) ?: return emptyList()
        return manifest.tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    /** Deletes a download's subtitles, in the current layout and the older subfolder one. */
    fun remove(localVideoUri: String) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        readManifest(storage)?.tracks?.forEach { storage.delete(it.fileName) }
        storage.delete(manifestName(storage))
        storage.delete(legacyDirectory(storage))
    }

    private fun importedSources(item: DownloadItem): List<SubtitleSource> =
        ImportedSubtitleRepository.subtitlesFor(
            videoId = item.videoId,
            metaId = item.parentMetaId,
            season = item.seasonNumber,
            episode = item.episodeNumber,
        ).map { match ->
            val path = ImportedSubtitleRepository.absolutePath(match.file.relativePath)
            val label = match.pack.sourceName?.takeIf { it.isNotBlank() }
            SubtitleSource(
                sourceUrl = path,
                language = match.pack.language,
                name = label ?: localizedImportedSubtitlesAddonName(),
                label = label,
                extension = match.file.fileName.substringAfterLast('.', missingDelimiterValue = "srt").lowercase(),
                // Copied byte for byte: imported files are often not UTF-8.
                content = SubtitleContent.File(path),
            )
        }

    private suspend fun fetchStreamSources(subtitles: List<StreamSubtitle>): List<SubtitleSource> =
        coroutineScope {
            subtitles
                .distinctBy { it.url }
                .filter { it.url.startsWith("https://", true) || it.url.startsWith("http://", true) }
                .map { subtitle ->
                    async {
                        val body = try {
                            withTimeoutOrNull(15_000L) {
                                httpGetTextWithHeaders(subtitle.url, subtitle.headers.orEmpty())
                            }?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        body?.let {
                            SubtitleSource(
                                sourceUrl = subtitle.url,
                                language = subtitle.language,
                                name = subtitle.name,
                                label = subtitle.name?.takeIf { name -> name.isNotBlank() } ?: subtitle.language,
                                extension = PlayerSubtitleCueParser.fileExtension(it, subtitle.url),
                                content = SubtitleContent.Text(it),
                            )
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest? {
        runCatching {
            storage.read(manifestName(storage))?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull()?.let { return it }

        // Downloads made before subtitles moved next to the video.
        val legacyDirectory = legacyDirectory(storage)
        return runCatching {
            storage.read("$legacyDirectory/manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull()?.let { manifest ->
            manifest.copy(tracks = manifest.tracks.map { it.copy(fileName = "$legacyDirectory/${it.fileName}") })
        }
    }

    private fun manifestName(storage: DownloadSubtitleStorage): String =
        MANIFEST_NAME_PREFIX + storage.videoFileName + MANIFEST_NAME_SUFFIX

    private fun legacyDirectory(storage: DownloadSubtitleStorage): String = "${storage.videoFileName}.subtitles"

    // Hidden, so the Files app shows only the video and its subtitles.
    private const val MANIFEST_NAME_PREFIX = "."
    private const val MANIFEST_NAME_SUFFIX = ".subtitles.json"
}

/**
 * `Title S01E01.ass` for a single subtitle. With several, each carries its label —
 * an imported pack's folder, which names the translation group, or a stream
 * subtitle's name — or its position when it has no label or shares it with another.
 */
internal fun subtitleFileNames(videoFileName: String, sources: List<SubtitleSource>): List<String> {
    val base = videoFileName.substringBeforeLast('.', missingDelimiterValue = videoFileName)
    if (sources.size == 1) return listOf("$base.${sources.single().extension}")

    val labels = sources.map { it.label?.toSubtitleLabel()?.takeIf { label -> label.isNotBlank() } }
    val used = mutableSetOf<String>()
    return sources.mapIndexed { index, source ->
        val label = labels[index]
        val token = if (label != null && labels.count { it.equals(label, ignoreCase = true) } == 1) {
            label
        } else {
            (index + 1).toString()
        }
        var name = "$base.$token.${source.extension}"
        var attempt = 2
        while (!used.add(name.lowercase())) {
            name = "$base.$token ($attempt).${source.extension}"
            attempt++
        }
        name
    }
}

/** Dots would read as extra name parts to other players; `/` and `:` can't be in a file name. */
private fun String.toSubtitleLabel(): String =
    replace('.', ' ')
        .replace(Regex("\\s*:\\s*"), " - ")
        .replace('/', '-')
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

internal data class SubtitleSource(
    val sourceUrl: String,
    val language: String,
    val name: String?,
    val label: String?,
    val extension: String,
    val content: SubtitleContent,
)

internal sealed interface SubtitleContent {
    data class File(val path: String) : SubtitleContent
    data class Text(val text: String) : SubtitleContent
}

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

/**
 * Files in the folder of a downloaded video, addressed by name relative to it.
 * A name may reach one folder down, for subtitles saved in the older layout.
 */
internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    val videoFileName: String
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun copy(sourcePath: String, fileName: String): Boolean
    fun localFileUri(fileName: String): String?
    fun delete(fileName: String)
}
