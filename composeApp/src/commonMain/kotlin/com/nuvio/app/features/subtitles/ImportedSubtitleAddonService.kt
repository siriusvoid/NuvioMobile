package com.nuvio.app.features.subtitles

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.webdav.WebDavUrl
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Answers subtitle requests for the imported library.
 *
 * The library is exposed as a virtual addon so the player needs no special case:
 * every addon read funnels through `fetchAddonResponseText`, so serving this
 * scheme locally puts imported files in the subtitle menu, in the right language
 * group, eligible for the preferred-language auto-selection, like any other
 * external subtitle. The url is a local path — mpv reads it straight off disk.
 */
internal object ImportedSubtitleAddonService {
    const val SCHEME = "nuvio-localsubs://"
    private const val HOST = "imported"
    const val MANIFEST_URL = "${SCHEME}$HOST/manifest.json"
    const val ADDON_ID = "nuvio.imported.subtitles"
    const val ADDON_NAME = "Imported subtitles"

    private const val DESCRIPTION = "Subtitle files you imported from this device."
    private const val CONTENT_TYPE_SERIES = "series"
    private const val CONTENT_TYPE_MOVIE = "movie"

    fun handles(url: String): Boolean = url.startsWith(SCHEME, ignoreCase = true)

    fun manifest(): AddonManifest = AddonManifest(
        id = ADDON_ID,
        name = ADDON_NAME,
        description = DESCRIPTION,
        version = "1.0.0",
        logoUrl = null,
        resources = listOf(
            // No idPrefixes: the app then asks about every id the user plays, which is
            // what lets a pack answer whatever id space its metadata addon serves.
            AddonResource(
                name = "subtitles",
                types = listOf(CONTENT_TYPE_SERIES, CONTENT_TYPE_MOVIE),
            ),
        ),
        types = listOf(CONTENT_TYPE_SERIES, CONTENT_TYPE_MOVIE),
        idPrefixes = emptyList(),
        catalogs = emptyList(),
        transportUrl = MANIFEST_URL,
    )

    fun respond(url: String): String {
        val withoutScheme = url.removePrefix(SCHEME)
        val path = withoutScheme.substringAfter('/', missingDelimiterValue = "").substringBefore('?')
        val segments = path.split('/').filter { it.isNotBlank() }

        return when (segments.firstOrNull()) {
            "manifest.json" -> manifestJson()
            "subtitles" -> subtitlesJson(segments)
            else -> emptyResponse()
        }
    }

    private fun manifestJson(): String = buildJsonObject {
        put("id", ADDON_ID)
        put("name", ADDON_NAME)
        put("version", "1.0.0")
        put("description", DESCRIPTION)
        putJsonArray("types") {
            add(CONTENT_TYPE_SERIES)
            add(CONTENT_TYPE_MOVIE)
        }
        putJsonArray("resources") {
            add(
                buildJsonObject {
                    put("name", "subtitles")
                    putJsonArray("types") {
                        add(CONTENT_TYPE_SERIES)
                        add(CONTENT_TYPE_MOVIE)
                    }
                },
            )
        }
    }.toString()

    /** subtitles/{type}/{videoId}.json */
    private fun subtitlesJson(segments: List<String>): String {
        val rawId = segments.getOrNull(2)?.removeSuffix(".json") ?: return emptyResponse()
        val videoId = WebDavUrl.decode(rawId).takeIf { it.isNotBlank() } ?: return emptyResponse()
        val identity = VideoIdentity.parse(videoId)

        val matches = ImportedSubtitleRepository.subtitlesFor(
            videoId = videoId,
            metaId = identity.metaId,
            season = identity.season,
            episode = identity.episode,
        )
        if (matches.isEmpty()) return emptyResponse()

        return buildJsonObject {
            putJsonArray("subtitles") {
                matches.forEach { match ->
                    add(
                        buildJsonObject {
                            put("id", "${match.pack.id}:${match.file.fileName}")
                            put("url", ImportedSubtitleRepository.absolutePath(match.file.relativePath))
                            put("lang", match.pack.language)
                            // The folder is what tells one fansub group's translation
                            // from another once both are imported for the same episode.
                            put("name", match.pack.sourceName?.takeIf { it.isNotBlank() } ?: ADDON_NAME)
                        },
                    )
                }
            }
        }.toString()
    }

    private fun emptyResponse(): String = buildJsonObject {
        putJsonArray("subtitles") { }
    }.toString()
}

/** The show, season and episode a playback id spells out. */
internal data class VideoIdentity(
    val metaId: String?,
    val season: Int?,
    val episode: Int?,
) {
    companion object {
        /** `tt0972656:1:5` and `kitsu:12345:5` both appear as playback ids. */
        fun parse(videoId: String): VideoIdentity {
            val segments = videoId.split(':')
            if (segments.size >= 3) {
                val season = segments[segments.lastIndex - 1].toIntOrNull()
                val episode = segments.last().toIntOrNull()
                if (season != null && episode != null) {
                    return VideoIdentity(
                        metaId = segments.dropLast(2).joinToString(":"),
                        season = season,
                        episode = episode,
                    )
                }
            }
            if (segments.size >= 2) {
                val episode = segments.last().toIntOrNull()
                if (episode != null) {
                    return VideoIdentity(
                        metaId = segments.dropLast(1).joinToString(":"),
                        season = null,
                        episode = episode,
                    )
                }
            }
            return VideoIdentity(metaId = videoId, season = null, episode = null)
        }
    }
}
