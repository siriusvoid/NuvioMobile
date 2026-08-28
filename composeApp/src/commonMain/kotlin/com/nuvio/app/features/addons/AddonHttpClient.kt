package com.nuvio.app.features.addons

import com.nuvio.app.features.subtitles.ImportedSubtitleAddonService
import com.nuvio.app.features.webdav.WebDavAddonService

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String =
    when {
        // The WebDAV library is a virtual addon: answer its scheme from the local
        // index instead of going over the network.
        WebDavAddonService.handles(url) -> WebDavAddonService.respond(url)

        // Same idea for subtitles the user imported from the Files app: the player
        // asks the addon layer, and this answers out of the local index.
        ImportedSubtitleAddonService.handles(url) -> ImportedSubtitleAddonService.respond(url)

        forceRefresh -> httpGetTextWithHeaders(
            url = url,
            headers = mapOf("Cache-Control" to "no-cache"),
        )

        else -> httpGetText(url)
    }
