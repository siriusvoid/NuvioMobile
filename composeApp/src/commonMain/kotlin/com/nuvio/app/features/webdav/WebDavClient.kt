package com.nuvio.app.features.webdav

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw

/**
 * The WebDAV verbs this feature needs, over the app's existing raw HTTP primitive.
 * No new networking layer: [httpRequestRaw] already takes an arbitrary method,
 * headers and a body on both platforms.
 */
internal class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val log = Logger.withTag("WebDavClient")

    private val authHeader: String? =
        if (username.isBlank() && password.isBlank()) {
            null
        } else {
            WebDavUrl.basicAuthHeader(username, password)
        }

    /** Headers a player needs to fetch a file from this server. */
    fun playbackHeaders(): Map<String, String> =
        authHeader?.let { mapOf("Authorization" to it) }.orEmpty()

    /**
     * Lists one directory. [path] is decoded and relative to the source base URL.
     * The directory's own entry is dropped so callers only see children.
     */
    suspend fun listDirectory(path: String): Result<List<DavEntry>> {
        val url = WebDavUrl.buildUrl(baseUrl, path)
        val response = runCatching {
            httpRequestRaw(
                method = "PROPFIND",
                url = url,
                headers = buildMap {
                    authHeader?.let { put("Authorization", it) }
                    put("Depth", "1")
                    put("Content-Type", "application/xml; charset=utf-8")
                    put("Accept", "application/xml, text/xml")
                },
                body = PROPFIND_BODY,
                followRedirects = true,
                maxResponseBodyBytes = MAX_LISTING_BYTES,
            )
        }.getOrElse { error ->
            log.w(error) { "PROPFIND failed for $url" }
            return Result.failure(error)
        }

        // Logged so a failure can be diagnosed from the device log. The credential
        // value is never logged, only whether one was attached.
        log.i {
            "PROPFIND $url auth=${if (authHeader != null) "basic" else "none"} " +
                "-> ${response.status} (${response.body.length} bytes)"
        }

        if (response.status !in 200..299) {
            log.w { "PROPFIND $url failed: ${response.body.take(300)}" }
            return Result.failure(
                IllegalStateException(describeStatus(response.status, response.body)),
            )
        }

        val entries = runCatching { WebDavXml.parseMultistatus(response.body) }
            .getOrElse { error ->
                log.w(error) { "Could not parse multistatus for $url" }
                return Result.failure(error)
            }

        val requestPath = WebDavUrl.pathOf(url)
        val children = entries.filter { entry ->
            val entryPath = WebDavUrl.decode(WebDavUrl.pathOf(entry.href))
            entryPath.isNotEmpty() && entryPath.trim('/') != requestPathDecoded(requestPath)
        }
        return Result.success(children)
    }

    suspend fun testConnection(path: String): WebDavConnectionResult {
        val result = listDirectory(path)
        return result.fold(
            onSuccess = { WebDavConnectionResult.Success(it.size) },
            onFailure = { error ->
                WebDavConnectionResult.Failure(error.message ?: "Could not reach the server")
            },
        )
    }

    /** True when the server answers a byte range, which is what makes seeking work. */
    suspend fun supportsRangeRequests(url: String): Boolean {
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = buildMap {
                    authHeader?.let { put("Authorization", it) }
                    put("Range", "bytes=0-1")
                },
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = 1024,
            )
        }.getOrNull() ?: return false
        return response.status == 206
    }

    private fun requestPathDecoded(path: String): String =
        WebDavUrl.decode(path).trim('/')

    /**
     * Failure text carries the status code and whatever the server said, so a
     * failed connection is diagnosable from the screen instead of by guesswork.
     */
    private fun describeStatus(status: Int, body: String): String {
        val explanation = when (status) {
            401 -> "no credentials reached the server"
            403 -> "the credentials were not accepted"
            404 -> "that path does not exist"
            405 -> "the server does not allow PROPFIND here"
            429 -> "the server is rate limiting, try again shortly"
            in 500..599 -> "the server had an internal error"
            else -> "the request was rejected"
        }
        val serverMessage = serverMessageFrom(body)
        return buildString {
            append("HTTP ")
            append(status)
            append(" — ")
            append(explanation)
            if (serverMessage != null) {
                append(". Server said: ")
                append(serverMessage)
            }
            append(".")
        }
    }

    /** Pulls the human-readable part out of a DAV error body, when there is one. */
    private fun serverMessageFrom(body: String): String? {
        if (body.isBlank()) return null
        // [\s\S] rather than DOT_MATCHES_ALL: that option is not in the common stdlib.
        val message = Regex("<[^>]*message[^>]*>([\\s\\S]*?)</[^>]*message[^>]*>")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: Regex("<[^>]*exception[^>]*>([\\s\\S]*?)</[^>]*exception[^>]*>")
                .find(body)
                ?.groupValues
                ?.get(1)
        return message?.trim()?.takeIf { it.isNotBlank() }?.take(120)
    }

    private companion object {
        const val MAX_LISTING_BYTES = 16 * 1024 * 1024

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <propfind xmlns="DAV:">
              <prop>
                <resourcetype/>
                <getcontentlength/>
                <getcontenttype/>
                <getlastmodified/>
                <displayname/>
              </prop>
            </propfind>
        """.trimIndent()
    }
}

/** Extensions that are worth indexing as playable video. */
internal val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "m4v", "mov", "wmv", "flv", "webm",
    "ts", "m2ts", "mpg", "mpeg", "ogm", "rmvb", "divx",
)

internal val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

internal fun String.fileExtension(): String =
    substringAfterLast('.', missingDelimiterValue = "").lowercase()

internal fun String.isVideoFile(): Boolean = fileExtension() in VIDEO_EXTENSIONS

internal fun String.isSubtitleFile(): Boolean = fileExtension() in SUBTITLE_EXTENSIONS

/** Release extras that are not the content itself. */
internal fun String.looksLikeSample(): Boolean {
    val lower = lowercase()
    return lower.contains("sample") || lower.contains("trailer") || lower.contains("preview")
}

/** Decoded final path segment of a href, which is the entry's name. */
internal fun DavEntry.decodedName(): String {
    displayName?.takeIf { it.isNotBlank() }?.let { return it }
    val path = WebDavUrl.pathOf(href).trim('/')
    return WebDavUrl.decode(path.substringAfterLast('/'))
}

/** Decoded path of a href relative to a source root, without leading or trailing slash. */
internal fun DavEntry.decodedPathRelativeTo(rootPath: String): String {
    val full = WebDavUrl.decode(WebDavUrl.pathOf(href)).trim('/')
    val root = rootPath.trim('/')
    return when {
        root.isEmpty() -> full
        full.startsWith("$root/") -> full.removePrefix("$root/")
        full == root -> ""
        else -> full
    }
}
