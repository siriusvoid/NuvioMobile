package com.nuvio.app.features.webdav

/** What a release folder name says once the noise is taken out. */
internal data class ReleaseLabel(
    val title: String,
    val group: String?,
    val quality: String?,
)

/**
 * Reads a torrent folder name the way a person does.
 *
 * "[Erai-raws] Shingeki no Kyojin Season 3 - 01 ~ 12 [1080p][Multiple Subtitle]" becomes
 * the title "Shingeki no Kyojin Season 3 - 01 ~ 12", the group "Erai-raws" and "1080p".
 *
 * Episode numbers and version marks stay in the title, because two folders of one show
 * often differ by nothing else. The group and the resolution come back separately: a
 * library holds the same episodes twice from one group at two qualities, and dropping
 * either would make the two read identically.
 */
internal object WebDavReleaseName {
    private val extension = Regex("""\.(mkv|mp4|avi|m4v|ts)$""", RegexOption.IGNORE_CASE)
    private val leadingGroup = Regex("""^\[([^\[\]]{1,30})]\s*""")
    private val trailingGroup = Regex("""-([A-Za-z0-9]+)$""")
    private val resolution = Regex("""(2160p|1080p|720p|480p)""", RegexOption.IGNORE_CASE)
    private val bracketed = Regex("""\[[^\[\]]*]|\([^()]*\)""")
    private val checksum = Regex("""[0-9a-f]{8}""", RegexOption.IGNORE_CASE)
    private val repeatedSpace = Regex("""\s{2,}""")

    /** Words that mark a bracket as encoding chatter rather than part of the title. */
    private val noiseWords = listOf(
        "2160p", "1080p", "720p", "480p", "bluray", "blu-ray", "bdrip", "webrip", "web-dl", "hdtv",
        "hevc", "avc", "av1", "x264", "x265", "h264", "h265", "10-bit", "10bit", "hi10p", "yuv444",
        "aac", "flac", "opus", "ac3", "eac3", "dts", "dual audio", "dual-audio", "multi-subs",
        "multiple subtitle", "subtitle", "subs", "remux", "repack", "uncensored", "complete",
    )

    /** A word that starts the technical tail of a scene name, e.g. Show.S03.1080p.BluRay.x265. */
    private val tailWords = setOf(
        "2160p", "1080p", "720p", "480p", "bd", "bdrip", "bluray", "blu-ray", "web", "web-dl",
        "webrip", "webdl", "hdtv", "hevc", "avc", "av1", "x264", "x265", "h264", "h265",
        "10-bit", "10bit", "hi10p", "aac", "flac", "opus", "ac3", "eac3", "dts", "remux", "repack",
    )

    fun describe(folderName: String): ReleaseLabel {
        var text = extension.replace(folderName, "")
        var group: String? = null

        leadingGroup.find(text)?.let { match ->
            group = match.groupValues[1].trim()
            text = text.removeRange(match.range)
        }

        val quality = resolution.find(text)?.value?.lowercase()

        text = bracketed.replace(text) { match -> if (isNoise(match.value)) " " else match.value }

        // A scene name is dotted and ends in -GROUP; everything else keeps its spaces.
        if (text.count { it == '.' } > 2) {
            if (group == null) {
                trailingGroup.find(text)?.let { match ->
                    group = match.groupValues[1]
                    text = text.removeRange(match.range)
                }
            }
            text = text.replace('.', ' ')
        }

        text = dropTechnicalTail(text)
        text = repeatedSpace.replace(text, " ").trim().trim('-', '·', '_', ' ')

        return ReleaseLabel(
            title = text.ifBlank { folderName },
            group = group?.takeIf { it.isNotBlank() },
            quality = quality,
        )
    }

    private fun isNoise(segment: String): Boolean {
        val inner = segment.trim('[', ']', '(', ')').trim().lowercase()
        if (inner.isEmpty()) return true
        if (checksum.matches(inner)) return true
        return noiseWords.any { inner.contains(it) }
    }

    /** Cuts "Laid-Back Camp S03 1080p BluRay x265" down to "Laid-Back Camp S03". */
    private fun dropTechnicalTail(text: String): String {
        val words = text.split(' ')
        val cut = words.indexOfFirst { word ->
            word.trim('(', ')', '[', ']', '-', '.').lowercase() in tailWords
        }
        return if (cut > 0) words.take(cut).joinToString(" ") else text
    }
}
