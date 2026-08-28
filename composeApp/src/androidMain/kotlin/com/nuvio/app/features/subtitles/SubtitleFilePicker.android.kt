package com.nuvio.app.features.subtitles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Not offered on Android yet — the entry point stays hidden. */
@Composable
internal actual fun rememberSubtitleFilePicker(
    onPicked: (List<PickedSubtitleFile>) -> Unit,
): SubtitleFilePickerHandle = remember {
    object : SubtitleFilePickerHandle {
        override val isSupported: Boolean = false
        override fun launch() = Unit
    }
}
