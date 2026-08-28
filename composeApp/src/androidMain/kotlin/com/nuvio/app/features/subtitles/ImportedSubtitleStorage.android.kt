package com.nuvio.app.features.subtitles

/**
 * Android has no imported-subtitle library yet: the importer is an iOS document
 * picker. The storage answers empty so shared code compiles and no-ops here.
 */
internal actual object ImportedSubtitleStorage {
    actual fun loadIndex(): String? = null
    actual fun saveIndex(payload: String) = Unit
    actual fun adopt(sourcePath: String, packId: String, fileName: String): String? = null
    actual fun absolutePath(relativePath: String): String = relativePath
    actual fun exists(relativePath: String): Boolean = false
    actual fun deleteFile(relativePath: String) = Unit
    actual fun readText(relativePath: String): String? = null
    actual fun deletePack(packId: String) = Unit
}
