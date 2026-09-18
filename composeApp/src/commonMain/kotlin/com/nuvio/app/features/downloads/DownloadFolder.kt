package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** A folder of the user's choosing that new downloads are saved to, instead of the app's own storage. */
internal expect object DownloadFolder {
    /** Name of the chosen folder, or null while downloads go to the app's own storage. */
    val folderName: StateFlow<String?>

    fun useAppStorage()
}

internal interface DownloadFolderPickerHandle {
    val isSupported: Boolean
    fun launch()
}

/** Opens the platform folder picker; [onRejected] runs when the picked folder can't take downloads. */
@Composable
internal expect fun rememberDownloadFolderPicker(
    onRejected: () -> Unit,
): DownloadFolderPickerHandle
