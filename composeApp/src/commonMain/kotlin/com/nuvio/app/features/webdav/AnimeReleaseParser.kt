package com.nuvio.app.features.webdav

/**
 * Reads anime release names.
 *
 * Debrid folders are named in fansub grammar rather than scene grammar, so this
 * handles bracketed groups glued to the title, underscore separators, trailing
 * CRC32 hashes, version suffixes and season words that are words rather than
 * numbers. Scene-style `S02E11` names still parse, they are just the easy case.
 *
 * Titles keep their season words — "Kusuriya no Hitorigoto 2nd Season" is a
 * distinct entry in the anime databases, so stripping the words would search for
 * the wrong thing.
 */
object AnimeReleaseParser {

    private val CRC32 = Regex("^[0-9a-fA-F]{8}$")
    private val SEASON_EPISODE = Regex("""(?:^|[^a-zA-Z0-9])[Ss](\d{1,2})\s*[EeXx](\d{1,4})(?:[^0-9]|$)""")
    private val SEASON_X_EPISODE = Regex("""(?:^|\s)(\d{1,2})[Xx](\d{1,4})(?:\s|$)""")
    private val DASH_EPISODE = Regex("""\s-\s*(\d{1,4})(?:v\d+)?\s*$""")
    private val EP_PREFIX_EPISODE = Regex("""(?:^|\s)(?:EP?|Episode)\s*[.\-]?\s*(\d{1,4})(?:v\d+)?(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val TRAILING_EPISODE = Regex("""\s(\d{1,4})(?:v\d+)?\s*$""")
    private val EPISODE_RANGE = Regex("""(?:^|[\s(\[])(\d{1,4})\s*[-~]\s*(\d{1,4})(?:[\s)\]]|$)""")
    private val ORDINAL_SEASON = Regex("""(\d{1,2})(?:st|nd|rd|th)\s+Season""", RegexOption.IGNORE_CASE)
    private val WORD_SEASON = Regex("""(?:^|\s)(?:Season|Saison)\s*(\d{1,2})(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val SHORT_SEASON = Regex("""(?:^|\s)S(\d{1,2})(?:\s|$)""")
    private val YEAR = Regex("""(?:^|[\s(\[])(19[5-9]\d|20[0-4]\d)(?:[\s)\]]|$)""")
    private val VERSION_SUFFIX = Regex("""v\d+$""")
    private val LEFTOVER_DASH_NUMBER = Regex("""\s-\s*\d{1,4}\s*$""")

    private val ROMAN_SEASONS = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6,
    )

    private val SPECIAL_MARKERS = setOf(
        "ncop", "nced", "ova", "oad", "ona", "special", "specials",
        "sp", "pv", "cm", "menu", "preview", "extra", "extras", "bonus",
    )

    /** Bracket contents that never contribute to a title. */
    private val TECHNICAL_TOKENS = setOf(
        "1080p", "720p", "480p", "360p", "2160p", "4k", "8bit", "10bit", "hi10", "hi10p",
        "bd", "bdrip", "bluray", "blu-ray", "web", "webrip", "web-dl", "webdl", "dvd", "dvdrip",
        "hdtv", "tv", "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "flac", "opus",
        "ac3", "eac3", "dts", "dual audio", "dual-audio", "multiple subtitle", "multi-subs",
        "multi subs", "subbed", "dubbed", "uncensored", "censored", "raw", "batch", "complete",
        "repack", "remux", "hdr", "sdr", "eng sub", "eng subs", "english sub", "english subs",
    )

    fun parseFolder(name: String): ParsedRelease =
        // Debrid exposes single-file torrents under a file name, so treat those as files.
        if (name.isVideoFile()) {
            parseFile(name)
        } else {
            parse(name, stripExtension = false, trailingNumberIsEpisode = false)
        }

    fun parseFile(name: String): ParsedRelease =
        parse(name, stripExtension = true, trailingNumberIsEpisode = true)

    fun parse(
        rawName: String,
        stripExtension: Boolean,
        trailingNumberIsEpisode: Boolean = true,
    ): ParsedRelease {
        val withoutExtension = if (stripExtension) rawName.substringBeforeLast('.') else rawName
        // Scene releases separate words with dots ("Laid-Back.Camp.S02.1080p"), so the
        // season and episode patterns never fire unless dots become spaces first.
        val dotSeparated = withoutExtension.count { it == '.' } >= 2 &&
            !withoutExtension.contains(' ')
        val normalized = withoutExtension
            .replace('_', ' ')
            .replace('×', 'x')
            .let { if (dotSeparated) it.replace('.', ' ') else it }
            .trim()

        val brackets = extractBracketed(normalized)
        var core = brackets.remainder
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '.', '_')

        val group = brackets.tokens.firstOrNull { token ->
            !isTechnical(token) && !CRC32.matches(token) && normalized.trimStart().startsWith("[")
        }

        val specialToken = (brackets.tokens + core.split(' ', '.', '-', '_'))
            .map { it.trim() }
            .firstOrNull { token ->
                token.lowercase().trimEnd { it.isDigit() } in SPECIAL_MARKERS
            }
        val isSpecial = specialToken != null

        core = trimTechnicalTail(core)

        var season: Int? = null
        var episode: Int? = null
        var episodeRange: IntRange? = null

        SEASON_EPISODE.find(core)?.let { match ->
            season = match.groupValues[1].toIntOrNull()
            episode = match.groupValues[2].toIntOrNull()
            core = core.replaceRange(match.range, " ").trim()
        }

        if (episode == null) {
            SEASON_X_EPISODE.find(core)?.let { match ->
                season = match.groupValues[1].toIntOrNull()
                episode = match.groupValues[2].toIntOrNull()
                core = core.replaceRange(match.range, " ").trim()
            }
        }

        // Ranges come from pack folders: "01-12", "(Season 1-2)", "(0001-1000)".
        val rangeSource = brackets.tokens.firstOrNull { EPISODE_RANGE.containsMatchIn(it) && !it.contains("season", ignoreCase = true) }
            ?: core
        EPISODE_RANGE.find(rangeSource)?.let { match ->
            val from = match.groupValues[1].toIntOrNull()
            val to = match.groupValues[2].toIntOrNull()
            if (from != null && to != null && to >= from && to - from < 2000) {
                episodeRange = from..to
                if (rangeSource === core) {
                    core = core.replaceRange(match.range, " ").trim()
                }
            }
        }

        if (episode == null && episodeRange == null) {
            DASH_EPISODE.find(core)?.let { match ->
                episode = match.groupValues[1].toIntOrNull()
                core = core.replaceRange(match.range, " ").trim()
            }
        }

        if (episode == null && episodeRange == null) {
            EP_PREFIX_EPISODE.find(core)?.let { match ->
                episode = match.groupValues[1].toIntOrNull()
                core = core.replaceRange(match.range, " ").trim()
            }
        }

        // Some groups put the episode in its own bracket: "Flying_Witch_[08]_[Group]".
        // Those tokens were stripped as tags, so the episode was never found.
        if (episode == null && episodeRange == null && trailingNumberIsEpisode) {
            episode = brackets.tokens
                .map { it.trim() }
                .firstOrNull { token ->
                    token.length <= 4 && token.all(Char::isDigit) &&
                        (token.toIntOrNull() ?: 0) !in 1900..2100
                }
                ?.toIntOrNull()
        }

        val year = YEAR.find(core)?.groupValues?.get(1)?.toIntOrNull()
            ?: brackets.tokens.firstNotNullOfOrNull { token ->
                YEAR.find(token)?.groupValues?.get(1)?.toIntOrNull()
            }

        if (episode == null && episodeRange == null && trailingNumberIsEpisode) {
            TRAILING_EPISODE.find(core)?.let { match ->
                val candidate = match.groupValues[1].toIntOrNull()
                // A trailing four-digit year is not an episode number.
                if (candidate != null && candidate != year) {
                    episode = candidate
                    core = core.replaceRange(match.range, " ").trim()
                }
            }
        }

        if (season == null) {
            season = ORDINAL_SEASON.find(core)?.groupValues?.get(1)?.toIntOrNull()
                ?: WORD_SEASON.find(core)?.groupValues?.get(1)?.toIntOrNull()
                ?: SHORT_SEASON.find(core)?.groupValues?.get(1)?.toIntOrNull()
                ?: ROMAN_SEASONS[core.substringAfterLast(' ').lowercase()]
        }

        // "Air in Summer - 01 - S00E02" leaves "- 01" behind once the S/E is consumed.
        if (episode != null) {
            core = core.trim(' ', '-', '~')
            LEFTOVER_DASH_NUMBER.find(core)?.let { core = core.removeRange(it.range).trim() }
        }

        // A special belongs to season 0, numbered by the marker itself where it says so.
        if (isSpecial && season == null) {
            season = 0
            if (episode == null) {
                episode = specialToken
                    ?.takeLastWhile(Char::isDigit)
                    ?.toIntOrNull()
                    ?: 1
            }
        }

        val title = cleanTitle(core)

        return ParsedRelease(
            title = title,
            season = season,
            episode = episode,
            episodeRange = episodeRange,
            year = year,
            group = group,
            isSpecial = isSpecial,
        )
    }

    private data class Bracketed(val tokens: List<String>, val remainder: String)

    /** Pulls out `[...]` and `(...)` groups, leaving the title text behind. */
    private fun extractBracketed(value: String): Bracketed {
        val tokens = ArrayList<String>()
        val remainder = StringBuilder()
        var depthSquare = 0
        var depthRound = 0
        val buffer = StringBuilder()

        value.forEach { char ->
            when {
                char == '[' -> {
                    if (depthSquare == 0 && depthRound == 0) buffer.clear()
                    depthSquare++
                }

                char == ']' && depthSquare > 0 -> {
                    depthSquare--
                    if (depthSquare == 0 && depthRound == 0) {
                        tokens.add(buffer.toString().trim())
                        buffer.clear()
                        remainder.append(' ')
                    }
                }

                char == '(' || char == '{' -> {
                    if (depthSquare == 0 && depthRound == 0) buffer.clear()
                    depthRound++
                }

                (char == ')' || char == '}') && depthRound > 0 -> {
                    depthRound--
                    if (depthSquare == 0 && depthRound == 0) {
                        tokens.add(buffer.toString().trim())
                        buffer.clear()
                        remainder.append(' ')
                    }
                }

                depthSquare > 0 || depthRound > 0 -> buffer.append(char)
                else -> remainder.append(char)
            }
        }

        return Bracketed(
            tokens = tokens.filter { it.isNotBlank() },
            remainder = remainder.toString(),
        )
    }

    private val TECHNICAL_WORD = Regex(
        """^(x26[45]|h26[45]|hi10p?|\d{3,4}p|\d{1,2}bits?|aac|flac|ac3|eac3|dts|opus|bd|bdrip|bluray|web|webrip|hdtv|dvd|dvdrip|remux|hevc|avc)([-._].*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun isTechnicalWord(word: String): Boolean {
        val lower = word.trim().lowercase()
        if (lower.isEmpty()) return false
        return lower in TECHNICAL_TOKENS || TECHNICAL_WORD.matches(lower) || CRC32.matches(lower)
    }

    private fun isTechnical(token: String): Boolean {
        val lower = token.trim().lowercase()
        if (lower in TECHNICAL_TOKENS) return true
        return lower.split(' ', '-', '.').any { it in TECHNICAL_TOKENS }
    }

    /** Drops trailing technical words so a bare episode number ends up at the end. */
    private fun trimTechnicalTail(value: String): String {
        var text = value.trim()
        while (true) {
            val lastWord = text.substringAfterLast(' ', missingDelimiterValue = "")
            if (lastWord.isEmpty() || !isTechnicalWord(lastWord)) return text
            text = text.substringBeforeLast(' ').trim(' ', '-', '.', '~')
        }
    }

    private fun cleanTitle(value: String): String {
        // A torrent holding a run plus its specials names both: "Air (2005) + Air in
        // Summer". The first part is the show; the rest arrives as season 0 files.
        var title = value.substringBefore(" + ")
            .split(' ')
            .filterNot { word ->
                word.trim().lowercase().trimEnd { it.isDigit() } in SPECIAL_MARKERS &&
                    word.trim().length <= 8
            }
            .joinToString(" ")
            .replace(VERSION_SUFFIX, "")
            .replace(Regex("""[._]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '~', '.', ',')

        // Trailing technical words that escaped the brackets.
        var changed = true
        while (changed) {
            changed = false
            val lastWord = title.substringAfterLast(' ', missingDelimiterValue = "")
            if (lastWord.isNotEmpty() && isTechnicalWord(lastWord)) {
                title = title.substringBeforeLast(' ').trim(' ', '-', '.')
                changed = true
            }
        }
        return title.trim()
    }

    /** Comparison form: lowercase alphanumeric words, used for scoring and cache keys. */
    fun normalizeForCompare(value: String): String =
        value
            .replace('×', 'x')
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /** 0..1 similarity over word overlap, which suits transliterated anime titles. */
    fun similarity(left: String, right: String): Float {
        val leftWords = normalizeForCompare(left).split(' ').filter { it.isNotBlank() }
        val rightWords = normalizeForCompare(right).split(' ').filter { it.isNotBlank() }
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0f
        if (leftWords == rightWords) return 1f

        // Databases hyphenate differently ("Natsu-iro Kiseki" vs "Natsuiro Kiseki"), which
        // word-set comparison scores far too low. Compare the letters as well.
        val leftCompact = leftWords.joinToString("")
        val rightCompact = rightWords.joinToString("")
        if (leftCompact == rightCompact) return 1f

        val leftSet = leftWords.toSet()
        val rightSet = rightWords.toSet()
        val shared = leftSet.count { it in rightSet }
        val union = (leftSet + rightSet).size
        val jaccard = shared.toFloat() / union.toFloat()

        val containment = shared.toFloat() / minOf(leftSet.size, rightSet.size).toFloat()
        val wordScore = (jaccard * 0.4f) + (containment * 0.6f)

        val compactContainment = when {
            leftCompact.length >= rightCompact.length && leftCompact.contains(rightCompact) ->
                rightCompact.length.toFloat() / leftCompact.length.toFloat()

            rightCompact.contains(leftCompact) ->
                leftCompact.length.toFloat() / rightCompact.length.toFloat()

            else -> 0f
        }
        return maxOf(wordScore, compactContainment)
    }
}
