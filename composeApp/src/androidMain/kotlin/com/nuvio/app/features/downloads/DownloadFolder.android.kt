package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Not offered on Android yet — downloads stay in app storage and the entry point stays hidden. */
internal actual object DownloadFolder {
    actual val folderName: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    actual fun useAppStorage() = Unit

    actual fun freeSpaceBytes(): Long? = null
}

@Composable
internal actual fun rememberDownloadFolderPicker(
    onRejected: () -> Unit,
): DownloadFolderPickerHandle = remember {
    object : DownloadFolderPickerHandle {
        override val isSupported: Boolean = false
        override fun launch() = Unit
    }
}
