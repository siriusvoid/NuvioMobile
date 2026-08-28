package com.nuvio.app.features.webdav

/** One `<response>` element from a PROPFIND multistatus body. */
internal data class DavEntry(
    val href: String,
    val displayName: String? = null,
    val isCollection: Boolean = false,
    val contentLength: Long? = null,
    val contentType: String? = null,
    val lastModified: String? = null,
) {
    val lastModifiedEpochSeconds: Long? get() = parseHttpDateToEpochSeconds(lastModified)
}

/**
 * A deliberately small XML reader for WebDAV multistatus bodies.
 *
 * Servers disagree about namespace prefixes — `d:`, `D:`, `lp1:`, `ns0:` all turn
 * up — so every element is matched on its local name and prefixes are discarded.
 * Nothing here tries to be a general XML parser; it handles the fixed shape of a
 * PROPFIND response and ignores everything else it meets.
 */
internal object WebDavXml {

    fun parseMultistatus(xml: String): List<DavEntry> {
        val entries = ArrayList<DavEntry>()
        val stack = ArrayList<String>()
        var current: EntryBuilder? = null
        var inResourceType = false

        val reader = XmlReader(xml)
        while (true) {
            val token = reader.next() ?: break
            when (token) {
                is XmlToken.Open -> {
                    when (token.name) {
                        "response" -> current = EntryBuilder()
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResourceType) current?.isCollection = true
                    }
                    if (token.selfClosing) {
                        if (token.name == "resourcetype") inResourceType = false
                    } else {
                        stack.add(token.name)
                    }
                }

                is XmlToken.Close -> {
                    when (token.name) {
                        "response" -> {
                            current?.build()?.let(entries::add)
                            current = null
                        }

                        "resourcetype" -> inResourceType = false
                    }
                    val index = stack.lastIndexOf(token.name)
                    if (index >= 0) {
                        while (stack.size > index) stack.removeAt(stack.size - 1)
                    }
                }

                is XmlToken.Text -> {
                    val parent = stack.lastOrNull()
                    val builder = current
                    if (parent != null && builder != null) {
                        val value = unescape(token.value)
                        when (parent) {
                            "href" -> builder.href = (builder.href ?: "") + value
                            "displayname" -> builder.displayName = value.trim().ifEmpty { null }
                            "getcontentlength" -> builder.contentLength = value.trim().toLongOrNull()
                            "getcontenttype" -> builder.contentType = value.trim().ifEmpty { null }
                            "getlastmodified" -> builder.lastModified = value.trim().ifEmpty { null }
                        }
                    }
                }
            }
        }
        return entries
    }

    private class EntryBuilder {
        var href: String? = null
        var displayName: String? = null
        var isCollection: Boolean = false
        var contentLength: Long? = null
        var contentType: String? = null
        var lastModified: String? = null

        fun build(): DavEntry? {
            val resolvedHref = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return DavEntry(
                href = resolvedHref,
                displayName = displayName,
                isCollection = isCollection,
                contentLength = contentLength,
                contentType = contentType,
                lastModified = lastModified,
            )
        }
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        return buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                if (char != '&') {
                    append(char)
                    index++
                    continue
                }
                val end = raw.indexOf(';', index + 1)
                if (end < 0 || end - index > 12) {
                    append(char)
                    index++
                    continue
                }
                when (val entity = raw.substring(index + 1, end)) {
                    "amp" -> append('&')
                    "lt" -> append('<')
                    "gt" -> append('>')
                    "quot" -> append('"')
                    "apos" -> append('\'')
                    else -> {
                        val code = when {
                            entity.startsWith("#x") || entity.startsWith("#X") ->
                                entity.drop(2).toIntOrNull(16)

                            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                            else -> null
                        }
                        if (code != null) append(code.toChar()) else append(raw, index, end + 1)
                    }
                }
                index = end + 1
            }
        }
    }
}

private sealed interface XmlToken {
    data class Open(val name: String, val selfClosing: Boolean) : XmlToken
    data class Close(val name: String) : XmlToken
    data class Text(val value: String) : XmlToken
}

/** Minimal pull reader: tags with their local names, and the text between them. */
private class XmlReader(private val source: String) {
    private var index = 0

    fun next(): XmlToken? {
        if (index >= source.length) return null

        if (source[index] != '<') {
            val nextTag = source.indexOf('<', index)
            val end = if (nextTag < 0) source.length else nextTag
            val text = source.substring(index, end)
            index = end
            return if (text.isBlank()) next() else XmlToken.Text(text)
        }

        // Comments, declarations, doctypes and CDATA are skipped wholesale.
        if (source.startsWith("<!--", index)) {
            val end = source.indexOf("-->", index)
            index = if (end < 0) source.length else end + 3
            return next()
        }
        if (source.startsWith("<![CDATA[", index)) {
            val end = source.indexOf("]]>", index)
            val stop = if (end < 0) source.length else end
            val text = source.substring(index + 9, stop)
            index = if (end < 0) source.length else end + 3
            return if (text.isBlank()) next() else XmlToken.Text(text)
        }
        if (source.startsWith("<?", index) || source.startsWith("<!", index)) {
            val end = source.indexOf('>', index)
            index = if (end < 0) source.length else end + 1
            return next()
        }

        val tagEnd = source.indexOf('>', index)
        if (tagEnd < 0) {
            index = source.length
            return null
        }
        val raw = source.substring(index + 1, tagEnd).trim()
        index = tagEnd + 1

        if (raw.startsWith("/")) {
            return XmlToken.Close(localName(raw.drop(1)))
        }
        val selfClosing = raw.endsWith("/")
        val withoutSlash = if (selfClosing) raw.dropLast(1).trim() else raw
        val name = withoutSlash.takeWhile { !it.isWhitespace() }
        return XmlToken.Open(localName(name), selfClosing)
    }

    private fun localName(raw: String): String =
        raw.substringAfterLast(':').trim().lowercase()
}
