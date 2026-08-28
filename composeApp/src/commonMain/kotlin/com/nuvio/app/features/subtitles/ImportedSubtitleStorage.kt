package com.nuvio.app.features.subtitles

/**
 * Device-local storage for imported subtitles: an index plus the files themselves.
 *
 * Paths are handed around relative to the storage root, never absolute. iOS renames
 * the app container on update, so an absolute path recorded today points nowhere
 * after the next install.
 */
internal expect object ImportedSubtitleStorage {
    fun loadIndex(): String?
    fun saveIndex(payload: String)

    /** Moves a picked file into [packId]'s directory, returning its relative path. */
    fun adopt(sourcePath: String, packId: String, fileName: String): String?

    /** Absolute path for the player, resolved against the container as it is now. */
    fun absolutePath(relativePath: String): String

    fun exists(relativePath: String): Boolean
    fun deleteFile(relativePath: String)
    fun readText(relativePath: String): String?
    fun deletePack(packId: String)
}
