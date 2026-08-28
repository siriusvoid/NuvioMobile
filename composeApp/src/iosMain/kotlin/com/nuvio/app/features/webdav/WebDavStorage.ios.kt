package com.nuvio.app.features.webdav

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
internal actual object WebDavStorage {
    private const val sourcesKey = "webdav_sources"
    private const val matchesKey = "webdav_matches"
    private const val passwordKeyPrefix = "webdav_password_"

    private val indexDirectory = "${NSHomeDirectory()}/Library/Application Support/NuvioWebDav"

    actual fun loadSources(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(sourcesKey))

    actual fun saveSources(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(sourcesKey))
    }

    actual fun loadIndex(sourceId: String): String? =
        readFile(indexPath(sourceId))?.decodeToString()

    actual fun saveIndex(sourceId: String, payload: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = indexDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        payload.encodeToByteArray().writeToFile(indexPath(sourceId))
    }

    actual fun deleteIndex(sourceId: String) {
        NSFileManager.defaultManager.removeItemAtPath(indexPath(sourceId), null)
    }

    actual fun loadMatches(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(matchesKey))

    actual fun saveMatches(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(matchesKey))
    }

    actual fun loadPassword(sourceId: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(passwordKey(sourceId))

    actual fun savePassword(sourceId: String, password: String) {
        NSUserDefaults.standardUserDefaults.setObject(password, forKey = passwordKey(sourceId))
    }

    actual fun deletePassword(sourceId: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(passwordKey(sourceId))
    }

    private fun passwordKey(sourceId: String): String =
        ProfileScopedKey.of("$passwordKeyPrefix${safeKey(sourceId)}")

    private fun indexPath(sourceId: String): String =
        "$indexDirectory/${safeKey(sourceId)}.json"

    private fun ByteArray.writeToFile(path: String): Boolean {
        val file = fopen(path, "wb") ?: return false
        return try {
            if (isEmpty()) return true
            usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), size.convert(), file).toLong() == size.toLong()
            }
        } finally {
            fclose(file)
        }
    }

    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        return try {
            if (fseek(file, 0, SEEK_END) != 0) return null
            val size = ftell(file)
            if (size <= 0L || size > Int.MAX_VALUE) return null
            rewind(file)
            ByteArray(size.toInt()).also { result ->
                val read = result.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), result.size.convert(), file)
                }
                if (read.toLong() != result.size.toLong()) return null
            }
        } finally {
            fclose(file)
        }
    }

    private fun safeKey(value: String): String = value.map { character ->
        if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
    }.joinToString("")
}
