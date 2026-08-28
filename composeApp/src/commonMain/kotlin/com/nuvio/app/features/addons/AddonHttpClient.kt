package com.nuvio.app.features.addons

import com.nuvio.app.features.webdav.WebDavAddonService

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String =
    when {
        // The WebDAV library is a virtual addon: answer its scheme from the local
        // index instead of going over the network.
        WebDavAddonService.handles(url) -> WebDavAddonService.respond(url)

        forceRefresh -> httpGetTextWithHeaders(
            url = url,
            headers = mapOf("Cache-Control" to "no-cache"),
        )

        else -> httpGetText(url)
    }
