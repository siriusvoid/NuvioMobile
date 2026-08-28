package com.nuvio.app.features.subtitles

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

@OptIn(ExperimentalForeignApi::class)
internal actual object ImportedSubtitleStorage {
    private val root = "${NSHomeDirectory()}/Library/Application Support/NuvioSubtitles"

    actual fun loadIndex(): String? = readFile(indexPath())?.decodeToString()

    actual fun saveIndex(payload: String) {
        createDirectory(root)
        payload.encodeToByteArray().writeToFile(indexPath())
    }

    actual fun adopt(sourcePath: String, packId: String, fileName: String): String? {
        val manager = NSFileManager.defaultManager
        val packDirectory = "$root/$packId"
        createDirectory(packDirectory)

        val safeName = safeFileName(fileName)
        val destination = "$packDirectory/$safeName"
        if (manager.fileExistsAtPath(destination)) {
            manager.removeItemAtPath(destination, null)
        }

        // The picker already copied the file out of its security-scoped location, so a
        // move is enough and keeps the temporary directory from filling up.
        val moved = manager.moveItemAtPath(sourcePath, destination, null) ||
            manager.copyItemAtPath(sourcePath, destination, null)
        return if (moved) "$packId/$safeName" else null
    }

    actual fun absolutePath(relativePath: String): String = "$root/$relativePath"

    actual fun exists(relativePath: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(absolutePath(relativePath))

    actual fun deleteFile(relativePath: String) {
        NSFileManager.defaultManager.removeItemAtPath(absolutePath(relativePath), null)
    }

    actual fun readText(relativePath: String): String? =
        readFile(absolutePath(relativePath))?.decodeToString()

    actual fun deletePack(packId: String) {
        NSFileManager.defaultManager.removeItemAtPath("$root/$packId", null)
    }

    private fun indexPath(): String = "$root/${safeFileName(ProfileScopedKey.of("imported_subtitles"))}.json"

    private fun createDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private fun safeFileName(value: String): String = value.map { character ->
        if (character == '/' || character == '\\' || character == ':') '_' else character
    }.joinToString("")

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
}
