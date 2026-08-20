package com.nuvio.app.features.details

import com.nuvio.app.core.format.extractReleaseYearForDisplay

private fun isTvSeriesType(type: String): Boolean =
    when (type.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> true
        else -> false
    }

private fun isEndedSeriesStatus(status: String?): Boolean {
    if (status.isNullOrBlank()) return false
    val s = status.trim().lowercase()
    if (isOngoingSeriesStatus(status)) return false
    return "ended" in s || "canceled" in s || "cancelled" in s
}

private fun isOngoingSeriesStatus(status: String?): Boolean {
    if (status.isNullOrBlank()) return false
    val s = status.trim().lowercase()
    return "returning" in s || "continuing" in s || "in production" in s || "ongoing" in s
}

/**
 * Compact release line under the details hero: movies → year only; TV → "2025 -" or "2021 - 2028".
 *
 * The open-ended "2025 -" form is only used when the status says the show is still running; an
 * unknown status falls back to the start year alone rather than implying it never ended.
 */
fun formatMetaReleaseLineForDetails(meta: MetaDetails): String? {
    val raw = meta.releaseInfo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!isTvSeriesType(meta.type)) {
        return extractReleaseYearForDisplay(raw)?.toString()
    }
    val startYear = extractReleaseYearForDisplay(raw) ?: return raw
    val endYear = meta.lastAirDate?.let { extractReleaseYearForDisplay(it) }
    return when {
        isEndedSeriesStatus(meta.status) && endYear != null ->
            if (endYear == startYear) startYear.toString()
            else "$startYear - $endYear"
        isEndedSeriesStatus(meta.status) -> startYear.toString()
        isOngoingSeriesStatus(meta.status) -> "$startYear -"
        else -> startYear.toString()
    }
}
