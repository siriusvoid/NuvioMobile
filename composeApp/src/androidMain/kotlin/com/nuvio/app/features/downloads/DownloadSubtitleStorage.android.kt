package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val video = File(URI(localVideoUri))
    private val directory = video.parentFile ?: File(".")
    actual val videoFileName: String = video.name

    actual fun read(fileName: String): String? =
        runCatching { AtomicFile(file(fileName)).readFully().decodeToString() }.getOrNull()

    actual fun write(fileName: String, text: String) {
        val target = file(fileName)
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create subtitle directory" } }
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    actual fun copy(sourcePath: String, fileName: String): Boolean = runCatching {
        val target = file(fileName)
        target.parentFile?.mkdirs()
        File(sourcePath).copyTo(target, overwrite = true)
        true
    }.getOrDefault(false)

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf { it.isFile }?.toURI()?.toString()

    actual fun delete(fileName: String) {
        file(fileName).deleteRecursively()
    }

    private fun file(fileName: String): File {
        val parts = fileName.split('/')
        require(parts.size <= 2 && parts.none { it.isBlank() || it == "." || it == ".." })
        return File(directory, fileName)
    }
}
