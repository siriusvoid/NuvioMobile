package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val videoPath = requireNotNull(NSURL(string = localVideoUri).path)
    private val directory = (videoPath as NSString).stringByDeletingLastPathComponent
    actual val videoFileName: String = videoPath.substringAfterLast('/')

    actual fun read(fileName: String): String? =
        NSString.stringWithContentsOfFile(path(fileName), NSUTF8StringEncoding, null)

    actual fun write(fileName: String, text: String) {
        val target = path(fileName)
        createParentDirectory(target)
        check(NSString.create(string = text).writeToFile(target, true, NSUTF8StringEncoding, null))
    }

    actual fun copy(sourcePath: String, fileName: String): Boolean {
        val target = path(fileName)
        createParentDirectory(target)
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(target)) manager.removeItemAtPath(target, null)
        return manager.copyItemAtPath(sourcePath, target, null)
    }

    actual fun localFileUri(fileName: String): String? =
        path(fileName).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { NSURL.fileURLWithPath(it).absoluteString }

    actual fun delete(fileName: String) {
        val target = path(fileName)
        if (NSFileManager.defaultManager.fileExistsAtPath(target)) {
            NSFileManager.defaultManager.removeItemAtPath(target, null)
        }
    }

    private fun createParentDirectory(target: String) {
        val parent = (target as NSString).stringByDeletingLastPathComponent
        if (parent != directory) {
            NSFileManager.defaultManager.createDirectoryAtPath(parent, true, null, null)
        }
    }

    private fun path(fileName: String): String {
        val parts = fileName.split('/')
        require(parts.size <= 2 && parts.none { it.isBlank() || it == "." || it == ".." })
        return "$directory/$fileName"
    }
}
