package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Coil's default iOS disk cache lives in the temp directory, which iOS clears whenever the app isn't running.
internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    val cachesDirectory = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: return this
    return diskCache {
        DiskCache.Builder()
            .fileSystem(FileSystem.SYSTEM)
            .directory("$cachesDirectory/image_cache".toPath())
            .build()
    }
}
