package com.nuvio.app.features.webdav

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One candidate title from an anime database. */
internal data class AnimeSearchHit(
    val source: String,
    val id: Int,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val startDateEpochSeconds: Long? = null,
    val subtype: String? = null,
    val poster: String? = null,
) {
    val isMovie: Boolean get() = subtype?.lowercase() == "movie"

    val allTitles: List<String> get() = listOf(title) + alternativeTitles

    companion object {
        const val SOURCE_KITSU = "kitsu"
        const val SOURCE_MAL = "myanimelist"
    }
}

/**
 * Title search against the anime databases.
 *
 * Kitsu is primary: it needs no key and answered every test query, while Jikan
 * returned gateway timeouts. Jikan is kept as a fallback because MAL sometimes
 * carries a title Kitsu spells differently.
 *
 * Searching here rather than through the installed metadata addon keeps matching
 * independent of how that addon's own search source is configured.
 */
internal object AnimeSearchClient {
    private val log = Logger.withTag("AnimeSearch")
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, List<AnimeSearchHit>>()

    suspend fun search(title: String): List<AnimeSearchHit> {
        val key = AnimeReleaseParser.normalizeForCompare(title)
        if (key.isBlank()) return emptyList()

        cacheMutex.withLock { cache[key] }?.let { return it }

        val hits = searchKitsu(title).ifEmpty { searchJikan(title) }

        cacheMutex.withLock {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.keys.firstOrNull()?.let(cache::remove)
            }
            cache[key] = hits
        }
        return hits
    }

    private suspend fun searchKitsu(title: String): List<AnimeSearchHit> {
        val url = "https://kitsu.io/api/edge/anime" +
            "?filter%5Btext%5D=${WebDavUrl.encodeSegment(title)}" +
            "&page%5Blimit%5D=$RESULT_LIMIT"

        val payload = get(url, accept = "application/vnd.api+json") ?: return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val data = root["data"] as? JsonArray ?: return emptyList()
            data.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
                val attributes = item["attributes"] as? JsonObject ?: return@mapNotNull null
                val titles = attributes["titles"] as? JsonObject
                val canonical = attributes.string("canonicalTitle")
                    ?: titles?.string("en_jp")
                    ?: return@mapNotNull null

                AnimeSearchHit(
                    source = AnimeSearchHit.SOURCE_KITSU,
                    id = id,
                    title = canonical,
                    alternativeTitles = listOfNotNull(
                        titles?.string("en"),
                        titles?.string("en_jp"),
                        titles?.string("ja_jp"),
                    ).filter { it != canonical },
                    episodeCount = attributes["episodeCount"]?.jsonPrimitive?.intOrNull,
                    startDateEpochSeconds = parseIsoDateToEpochSeconds(attributes.string("startDate")),
                    subtype = attributes.string("subtype"),
                    poster = (attributes["posterImage"] as? JsonObject)?.string("original"),
                )
            }
        }.getOrElse { error ->
            log.w(error) { "Could not parse Kitsu response" }
            emptyList()
        }
    }

    private suspend fun searchJikan(title: String): List<AnimeSearchHit> {
        val url = "https://api.jikan.moe/v4/anime" +
            "?q=${WebDavUrl.encodeSegment(title)}&limit=$RESULT_LIMIT"

        val payload = get(url, accept = "application/json") ?: return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val data = root["data"] as? JsonArray ?: return emptyList()
            data.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item["mal_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val mainTitle = item.string("title") ?: return@mapNotNull null
                val alternatives = (item["titles"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.string("title") }
                    .orEmpty()

                AnimeSearchHit(
                    source = AnimeSearchHit.SOURCE_MAL,
                    id = id,
                    title = mainTitle,
                    alternativeTitles = (alternatives + listOfNotNull(item.string("title_english")))
                        .filter { it != mainTitle }
                        .distinct(),
                    episodeCount = item["episodes"]?.jsonPrimitive?.intOrNull,
                    startDateEpochSeconds = parseIsoDateToEpochSeconds(
                        (item["aired"] as? JsonObject)?.string("from"),
                    ),
                    subtype = item.string("type"),
                    poster = ((item["images"] as? JsonObject)?.get("jpg") as? JsonObject)
                        ?.string("image_url"),
                )
            }
        }.getOrElse { error ->
            log.w(error) { "Could not parse Jikan response" }
            emptyList()
        }
    }

    private suspend fun get(url: String, accept: String): String? {
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to accept),
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = MAX_RESPONSE_BYTES,
            )
        }.getOrElse { error ->
            log.w(error) { "Request failed: $url" }
            return null
        }

        if (response.status !in 200..299) {
            log.w { "HTTP ${response.status} from $url" }
            return null
        }
        return response.body.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private const val RESULT_LIMIT = 5
    private const val MAX_CACHE_ENTRIES = 400
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
}

/** Ids for one anime entry, as returned by the Anime Relations Mapper. */
internal data class ArmIds(
    val anidb: Int? = null,
    val anilist: Int? = null,
    val myanimelist: Int? = null,
    val kitsu: Int? = null,
    val imdb: String? = null,
    val themoviedb: Int? = null,
    val themoviedbSeason: Int? = null,
    val thetvdb: Int? = null,
    val thetvdbSeason: Int? = null,
    val media: String? = null,
) {
    /** TVDB numbering first: it is what a TVDB-sourced metadata addon serves. */
    val season: Int? get() = thetvdbSeason ?: themoviedbSeason
}

/**
 * arm.haglund.dev, already used by the TV app for skip-segment id resolution.
 *
 * The forward lookup is the one that carries season numbers; the reverse
 * `/imdb?id=` endpoint returns a bare list without them.
 */
internal object ArmMappingClient {
    private val log = Logger.withTag("ArmMapping")
    private val json = Json { ignoreUnknownKeys = true }

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, ArmIds?>()

    suspend fun lookup(source: String, id: Int): ArmIds? {
        val key = "$source:$id"
        cacheMutex.withLock { if (cache.containsKey(key)) return cache[key] }

        val url = "https://arm.haglund.dev/api/v2/ids?source=$source&id=$id"
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json"),
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = 64 * 1024,
            )
        }.getOrElse { error ->
            log.w(error) { "ARM lookup failed for $key" }
            return null
        }

        val ids = if (response.status !in 200..299) {
            log.d { "ARM has no mapping for $key (HTTP ${response.status})" }
            null
        } else {
            runCatching { parse(response.body) }.getOrNull()
        }

        cacheMutex.withLock {
            if (cache.size >= MAX_CACHE_ENTRIES) {
                cache.keys.firstOrNull()?.let(cache::remove)
            }
            cache[key] = ids
        }
        return ids
    }

    private fun parse(payload: String): ArmIds? {
        val element = json.parseToJsonElement(payload)
        val obj = when {
            element is JsonObject -> element
            element is JsonArray -> element.jsonArray.firstOrNull() as? JsonObject
            else -> null
        } ?: return null

        return ArmIds(
            anidb = obj.int("anidb"),
            anilist = obj.int("anilist"),
            myanimelist = obj.int("myanimelist"),
            kitsu = obj.int("kitsu"),
            imdb = obj["imdb"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("tt") },
            themoviedb = obj.int("themoviedb"),
            themoviedbSeason = obj.int("themoviedb-season"),
            thetvdb = obj.int("thetvdb"),
            thetvdbSeason = obj.int("thetvdb-season"),
            media = obj["media"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private const val MAX_CACHE_ENTRIES = 400
}
