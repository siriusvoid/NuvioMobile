package com.nuvio.app.features.webdav

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal actual object WebDavStorage {
    private const val preferencesName = "nuvio_webdav"
    private const val sourcesKey = "webdav_sources"
    private const val matchesKey = "webdav_matches"
    private const val passwordKeyPrefix = "webdav_password_"

    private var preferences: SharedPreferences? = null
    private var indexDirectory: File? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        indexDirectory = File(context.filesDir, "webdav")
    }

    actual fun loadSources(): String? = preferences?.getString(sourcesKey, null)

    actual fun saveSources(payload: String) {
        preferences?.edit()?.putString(sourcesKey, payload)?.apply()
    }

    actual fun loadIndex(sourceId: String): String? =
        indexFile(sourceId)?.takeIf { it.exists() }?.readText()

    actual fun saveIndex(sourceId: String, payload: String) {
        val file = indexFile(sourceId) ?: return
        file.parentFile?.mkdirs()
        file.writeText(payload)
    }

    actual fun deleteIndex(sourceId: String) {
        indexFile(sourceId)?.delete()
    }

    actual fun loadMatches(): String? = preferences?.getString(matchesKey, null)

    actual fun saveMatches(payload: String) {
        preferences?.edit()?.putString(matchesKey, payload)?.apply()
    }

    actual fun loadPassword(sourceId: String): String? =
        preferences?.getString(passwordKey(sourceId), null)

    actual fun savePassword(sourceId: String, password: String) {
        preferences?.edit()?.putString(passwordKey(sourceId), password)?.apply()
    }

    actual fun deletePassword(sourceId: String) {
        preferences?.edit()?.remove(passwordKey(sourceId))?.apply()
    }

    private fun passwordKey(sourceId: String): String = "$passwordKeyPrefix${safeKey(sourceId)}"

    private fun indexFile(sourceId: String): File? =
        indexDirectory?.let { File(it, "${safeKey(sourceId)}.json") }

    private fun safeKey(value: String): String = value.map { character ->
        if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
    }.joinToString("")
}
