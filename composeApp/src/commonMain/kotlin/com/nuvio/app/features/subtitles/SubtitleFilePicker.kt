package com.nuvio.app.features.subtitles

import androidx.compose.runtime.Composable

/** One file the user picked, already copied somewhere this app can read. */
internal data class PickedSubtitleFile(
    val fileName: String,
    val sourcePath: String,
    /** Folder the file was picked from, when the user picked a folder. */
    val sourceName: String? = null,
)

internal interface SubtitleFilePickerHandle {
    val isSupported: Boolean
    fun launch()
}

/**
 * Opens the platform file picker for subtitle files. Folders are pickable too:
 * subtitle packs arrive as a directory per show.
 */
@Composable
internal expect fun rememberSubtitleFilePicker(
    onPicked: (List<PickedSubtitleFile>) -> Unit,
): SubtitleFilePickerHandle
