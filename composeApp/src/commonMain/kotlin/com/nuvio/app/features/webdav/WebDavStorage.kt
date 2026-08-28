package com.nuvio.app.features.webdav

/**
 * Device-local persistence for the WebDAV library. Nothing here syncs to an
 * account: the source list, the scan index and the credentials all stay on
 * this device.
 *
 * Passwords sit alongside every other secret in the app (Trakt tokens, debrid
 * API keys) rather than in the Keychain, because the Security framework is only
 * linked for the full iOS distribution and a credential store that fails to link
 * in the lite build would be worse than a consistent one.
 */
internal expect object WebDavStorage {
    fun loadSources(): String?
    fun saveSources(payload: String)

    /** Folder index for one source. File-backed: it grows with every scan. */
    fun loadIndex(sourceId: String): String?
    fun saveIndex(sourceId: String, payload: String)
    fun deleteIndex(sourceId: String)

    fun loadMatches(): String?
    fun saveMatches(payload: String)

    fun loadPassword(sourceId: String): String?
    fun savePassword(sourceId: String, password: String)
    fun deletePassword(sourceId: String)
}
