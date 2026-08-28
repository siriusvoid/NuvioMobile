package com.nuvio.app.features.webdav

/**
 * URL handling for WebDAV. `href` values in a multistatus body are percent-encoded
 * and may be absolute or origin-relative, so every path that comes back from the
 * server has to be decoded once and re-encoded when it is used again.
 */
internal object WebDavUrl {

    private const val HEX = "0123456789ABCDEF"

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    fun normalizeRootPath(raw: String): String =
        raw.trim().trim('/')

    fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.trimEnd('/')
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /** Decoded path component of an absolute or relative URL, without leading slash. */
    fun pathOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val raw = if (schemeEnd < 0) {
            url
        } else {
            val pathStart = url.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) "" else url.substring(pathStart)
        }
        return raw.substringBefore('?').trim('/')
    }

    /** Absolute URL for a href taken from a multistatus response. */
    fun resolveHref(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed

            trimmed.startsWith("/") -> originOf(baseUrl) + trimmed
            else -> baseUrl.trimEnd('/') + "/" + trimmed
        }
    }

    /** Joins already-decoded path segments onto a base and encodes them once. */
    fun buildUrl(baseUrl: String, vararg segments: String): String {
        val encoded = segments
            .flatMap { it.split('/') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }
        val base = baseUrl.trimEnd('/')
        // Always end a collection URL with a slash. Without it servers answer a
        // redirect, and a redirected PROPFIND can arrive without its body.
        return if (encoded.isEmpty()) "$base/" else "$base/$encoded/"
    }

    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    fun encodeSegment(segment: String): String = buildString {
        segment.encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    fun decode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && index + 2 < value.length -> {
                    val hex = value.substring(index + 1, index + 3)
                    val parsed = hex.toIntOrNull(16)
                    if (parsed == null) {
                        bytes.add(char.code.toByte())
                        index++
                    } else {
                        bytes.add(parsed.toByte())
                        index += 3
                    }
                }

                else -> {
                    char.toString().encodeToByteArray().forEach { bytes.add(it) }
                    index++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /** Basic auth header value. Hand-rolled so no experimental stdlib opt-in is needed. */
    fun basicAuthHeader(username: String, password: String): String =
        "Basic " + base64("$username:$password".encodeToByteArray())

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(bytes: ByteArray): String = buildString {
        var index = 0
        while (index + 2 < bytes.size) {
            val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                (bytes[index + 2].toInt() and 0xFF)
            append(B64[(chunk shr 18) and 0x3F])
            append(B64[(chunk shr 12) and 0x3F])
            append(B64[(chunk shr 6) and 0x3F])
            append(B64[chunk and 0x3F])
            index += 3
        }
        when (bytes.size - index) {
            1 -> {
                val chunk = (bytes[index].toInt() and 0xFF) shl 16
                append(B64[(chunk shr 18) and 0x3F])
                append(B64[(chunk shr 12) and 0x3F])
                append("==")
            }

            2 -> {
                val chunk = ((bytes[index].toInt() and 0xFF) shl 16) or
                    ((bytes[index + 1].toInt() and 0xFF) shl 8)
                append(B64[(chunk shr 18) and 0x3F])
                append(B64[(chunk shr 12) and 0x3F])
                append(B64[(chunk shr 6) and 0x3F])
                append('=')
            }
        }
    }
}

private val MONTHS = listOf(
    "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
)

/**
 * Parses an RFC 1123 HTTP-date ("Tue, 05 Oct 2012 12:00:00 GMT") to epoch seconds.
 * Only used for ordering folders newest-first, so a null on an odd format simply
 * sends that folder to the back of the queue rather than failing a scan.
 */
internal fun parseHttpDateToEpochSeconds(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val cleaned = value.trim().substringAfter(',', missingDelimiterValue = value.trim()).trim()
    val parts = cleaned.split(' ', '\t').filter { it.isNotBlank() }
    if (parts.size < 4) return null

    val day = parts[0].toIntOrNull() ?: return null
    val month = MONTHS.indexOf(parts[1].lowercase().take(3)).takeIf { it >= 0 } ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    val time = parts[3].split(':')
    val hour = time.getOrNull(0)?.toIntOrNull() ?: 0
    val minute = time.getOrNull(1)?.toIntOrNull() ?: 0
    val second = time.getOrNull(2)?.toIntOrNull() ?: 0

    val days = daysFromCivil(year, month + 1, day)
    return days * 86_400L + hour * 3_600L + minute * 60L + second
}

/** Howard Hinnant's days_from_civil, which needs no date library. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146_097L + doe.toLong() - 719_468L
}

/** ISO-8601 date ("2012-10-05" or a full timestamp) to epoch seconds. */
internal fun parseIsoDateToEpochSeconds(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val datePart = value.trim().take(10)
    val pieces = datePart.split('-')
    if (pieces.size != 3) return null
    val year = pieces[0].toIntOrNull() ?: return null
    val month = pieces[1].toIntOrNull() ?: return null
    val day = pieces[2].toIntOrNull() ?: return null
    return daysFromCivil(year, month, day) * 86_400L
}
