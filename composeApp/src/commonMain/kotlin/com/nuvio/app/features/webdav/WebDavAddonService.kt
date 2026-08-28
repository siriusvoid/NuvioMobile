package com.nuvio.app.features.webdav

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Answers addon requests for the WebDAV library.
 *
 * The library is exposed to the rest of the app as a virtual addon: every addon
 * read already funnels through `fetchAddonResponseText`, so serving this scheme
 * locally gives home rows, catalogue paging, search, streams, Library and
 * Continue Watching without touching any of them.
 *
 * Only `catalog` and `stream` are declared. Matched items carry the id space the
 * installed metadata addon serves, so that addon answers `meta` and the details
 * page is the normal one.
 */
internal object WebDavAddonService {
    const val SCHEME = "nuvio-webdav://"
    const val HOST = "library"
    const val MANIFEST_URL = "${SCHEME}$HOST/manifest.json"
    const val ADDON_ID = "nuvio.webdav.library"
    const val ADDON_NAME = "WebDAV library"

    private const val PAGE_SIZE = 100

    fun handles(url: String): Boolean = url.startsWith(SCHEME, ignoreCase = true)

    fun catalogId(sourceId: String): String = "webdav.$sourceId"

    /** The manifest as the app's own model, used when injecting the virtual addon. */
    fun manifest(sources: List<WebDavSource>): AddonManifest {
        // A single catalogue per source: films and series sit together, each item
        // carrying its own type so the app still routes it correctly.
        val catalogs = sources.filter { it.enabled }.map { source ->
            AddonCatalog(
                type = WebDavMatch.CONTENT_TYPE_SERIES,
                id = catalogId(source.id),
                name = source.displayName,
                extra = listOf(
                    AddonExtraProperty(name = "skip"),
                    AddonExtraProperty(name = "search"),
                ),
            )
        }

        return AddonManifest(
            id = ADDON_ID,
            name = ADDON_NAME,
            description = "Anime from your debrid WebDAV, mapped to your metadata addon.",
            version = "1.0.0",
            logoUrl = null,
            resources = listOf(
                AddonResource(
                    name = "catalog",
                    types = listOf(WebDavMatch.CONTENT_TYPE_SERIES, WebDavMatch.CONTENT_TYPE_MOVIE),
                ),
                // No idPrefixes: the app then asks about every id the user opens, which is
                // how a debrid copy shows up as an extra source on a normally-found show.
                AddonResource(
                    name = "stream",
                    types = listOf(WebDavMatch.CONTENT_TYPE_SERIES, WebDavMatch.CONTENT_TYPE_MOVIE),
                ),
            ),
            types = listOf(WebDavMatch.CONTENT_TYPE_SERIES, WebDavMatch.CONTENT_TYPE_MOVIE),
            idPrefixes = emptyList(),
            catalogs = catalogs,
            transportUrl = MANIFEST_URL,
        )
    }

    suspend fun respond(url: String): String {
        val withoutScheme = url.removePrefix(SCHEME)
        val path = withoutScheme.substringAfter('/', missingDelimiterValue = "").substringBefore('?')
        val segments = path.split('/').filter { it.isNotBlank() }

        return when (segments.firstOrNull()) {
            "manifest.json" -> manifestJson()
            "catalog" -> catalogJson(segments)
            "stream" -> streamJson(segments)
            else -> emptyResponse()
        }
    }

    private fun manifestJson(): String {
        val sources = WebDavLibraryRepository.uiState.value.sources.filter { it.enabled }
        return buildJsonObject {
            put("id", ADDON_ID)
            put("name", ADDON_NAME)
            put("version", "1.0.0")
            put("description", "Anime from your debrid WebDAV, mapped to your metadata addon.")
            putJsonArray("types") {
                add(WebDavMatch.CONTENT_TYPE_SERIES)
                add(WebDavMatch.CONTENT_TYPE_MOVIE)
            }
            putJsonArray("resources") {
                add(
                    buildJsonObject {
                        put("name", "catalog")
                        putJsonArray("types") {
                            add(WebDavMatch.CONTENT_TYPE_SERIES)
                            add(WebDavMatch.CONTENT_TYPE_MOVIE)
                        }
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "stream")
                        putJsonArray("types") {
                            add(WebDavMatch.CONTENT_TYPE_SERIES)
                            add(WebDavMatch.CONTENT_TYPE_MOVIE)
                        }
                    },
                )
            }
            putJsonArray("catalogs") {
                sources.forEach { source ->
                    add(
                        buildJsonObject {
                            put("type", WebDavMatch.CONTENT_TYPE_SERIES)
                            put("id", catalogId(source.id))
                            put("name", source.displayName)
                            putJsonArray("extra") {
                                add(buildJsonObject { put("name", "skip") })
                                add(buildJsonObject { put("name", "search") })
                            }
                        },
                    )
                }
            }
        }.toString()
    }

    /** catalog/{type}/{catalogId}.json, optionally with a `/skip=100&search=x.json` tail. */
    private suspend fun catalogJson(segments: List<String>): String {
        val rawId = segments.getOrNull(2)?.removeSuffix(".json") ?: return emptyResponse()
        val extras = parseExtras(segments.getOrNull(3)?.removeSuffix(".json"))

        val catalogId = WebDavUrl.decode(rawId)
        val sourceId = catalogId.removePrefix("webdav.").takeIf { it.isNotBlank() }

        val skip = extras["skip"]?.toIntOrNull() ?: 0
        val search = extras["search"]?.lowercase()

        val entries = WebDavIndex.catalogEntries(sourceId = sourceId)
            .let { matches ->
                if (search.isNullOrBlank()) {
                    matches
                } else {
                    matches.filter { it.title.lowercase().contains(search) }
                }
            }
            .drop(skip)
            .take(PAGE_SIZE)

        return buildJsonObject {
            putJsonArray("metas") {
                entries.forEach { match ->
                    add(
                        buildJsonObject {
                            put("id", match.contentId)
                            put("type", match.contentType)
                            put("name", match.displayName)
                            match.displayPoster?.let { put("poster", it) }
                            put("posterShape", "poster")
                        },
                    )
                }
            }
        }.toString()
    }

    /** stream/{type}/{videoId}.json */
    private suspend fun streamJson(segments: List<String>): String {
        val rawId = segments.getOrNull(2)?.removeSuffix(".json") ?: return emptyStreams()
        val videoId = WebDavUrl.decode(rawId)
        val parts = parseVideoId(videoId)

        val candidates = WebDavIndex.foldersForContentId(parts.contentId)
        if (candidates.isEmpty()) return emptyStreams()

        val streams = ArrayList<JsonObject>()
        candidates.forEach { (folder, match) ->
            val headers = WebDavLibraryRepository.playbackHeaders(match.sourceId)
            val files = selectFiles(folder, match, parts)
            files.forEach { file ->
                streams.add(buildStream(file, folder, match, headers))
            }
        }

        return buildJsonObject {
            put("streams", JsonArray(streams))
        }.toString()
    }

    private fun selectFiles(
        folder: WebDavFolder,
        match: WebDavMatch,
        parts: VideoIdParts,
    ): List<WebDavFile> {
        if (match.contentType == WebDavMatch.CONTENT_TYPE_MOVIE) {
            return folder.files.sortedByDescending { it.sizeBytes ?: 0L }.take(3)
        }


        val requestedEpisode = parts.episode ?: return emptyList()
        val requestedSeason = parts.season ?: match.season

        return folder.files.filter { file ->
            val parsed = AnimeReleaseParser.parseFile(file.fileName)
            val parsedEpisode = parsed.episode ?: return@filter false

            // A file that names its own season — specials as season 0 — is placed by it;
            // otherwise the folder's season stands in. Without this a special would also
            // answer for the numbered episode with the same number.
            val fileSeason = parsed.season ?: match.season
            if (requestedSeason != null && fileSeason != null && fileSeason != requestedSeason) {
                return@filter false
            }

            // The folder's offset applies to its numbered run, not to specials.
            val offset = if (parsed.season == 0) 0 else match.episodeOffset
            parsedEpisode + offset == requestedEpisode
        }
    }

    private fun buildStream(
        file: WebDavFile,
        folder: WebDavFolder,
        match: WebDavMatch,
        headers: Map<String, String>,
    ): JsonObject = buildJsonObject {
        val source = WebDavLibraryRepository.uiState.value.sources
            .firstOrNull { it.id == match.sourceId }
        put("name", source?.displayName ?: ADDON_NAME)
        put("title", file.fileName)
        put("description", "${folder.name}\n${file.fileName}")
        put("url", file.url)
        putJsonObject("behaviorHints") {
            put("filename", file.fileName)
            file.sizeBytes?.let { put("videoSize", it) }
            put("notWebReady", true)
            put("bingeGroup", "webdav-${match.sourceId}-${match.contentId}")
            if (headers.isNotEmpty()) {
                putJsonObject("proxyHeaders") {
                    putJsonObject("request") {
                        headers.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
        }
    }

    private fun parseExtras(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return WebDavUrl.decode(raw)
            .split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = "").trim()
                val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                if (key.isBlank()) null else key to WebDavUrl.decode(value)
            }
            .toMap()
    }

    private fun emptyResponse(): String = buildJsonObject { putJsonArray("metas") {} }.toString()

    private fun emptyStreams(): String = buildJsonObject { putJsonArray("streams") {} }.toString()
}
