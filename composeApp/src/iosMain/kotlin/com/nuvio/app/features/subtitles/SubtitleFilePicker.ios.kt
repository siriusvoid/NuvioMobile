package com.nuvio.app.features.subtitles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UniformTypeIdentifiers.UTType
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The system document picker, taking files or a whole folder.
 *
 * Picked items arrive as security-scoped urls that stay readable only while the
 * picker's grant is held, so everything is copied into a temporary directory
 * before the callback runs; the repository then moves those copies into place.
 */
@Composable
internal actual fun rememberSubtitleFilePicker(
    onPicked: (List<PickedSubtitleFile>) -> Unit,
): SubtitleFilePickerHandle {
    val viewController = LocalUIViewController.current
    val latestOnPicked = rememberUpdatedState(onPicked)
    val delegate = remember {
        SubtitlePickerDelegate { urls -> latestOnPicked.value(copyPickedItems(urls)) }
    }

    DisposableEffect(delegate) {
        onDispose { delegate.detach() }
    }

    return remember(viewController, delegate) {
        object : SubtitleFilePickerHandle {
            override val isSupported: Boolean = true

            override fun launch() {
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = pickerContentTypes(),
                    asCopy = false,
                )
                picker.allowsMultipleSelection = true
                picker.delegate = delegate
                viewController.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

/**
 * `.ass` and `.srt` have no registered system type, and a dynamic one would hide
 * every file the picker could not classify. Showing all items and filtering by
 * extension afterwards is what keeps a subtitle folder browsable.
 */
private fun pickerContentTypes(): List<UTType> = listOfNotNull(
    UTType.typeWithIdentifier("public.item"),
    UTType.typeWithIdentifier("public.folder"),
)

private class SubtitlePickerDelegate(
    onPicked: (List<NSURL>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var callback: ((List<NSURL>) -> Unit)? = onPicked

    fun detach() {
        callback = null
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        callback?.invoke(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        callback?.invoke(emptyList())
    }
}

@OptIn(ExperimentalUuidApi::class, ExperimentalForeignApi::class)
private fun copyPickedItems(urls: List<NSURL>): List<PickedSubtitleFile> {
    if (urls.isEmpty()) return emptyList()

    val manager = NSFileManager.defaultManager
    val stagingDirectory = "${NSTemporaryDirectory()}nuvio-subtitle-import/${Uuid.random()}"
    manager.createDirectoryAtPath(
        path = stagingDirectory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )

    val picked = mutableListOf<PickedSubtitleFile>()
    urls.forEach { url ->
        val accessed = url.startAccessingSecurityScopedResource()
        try {
            val path = url.path ?: return@forEach
            if (url.hasDirectoryPath) {
                val folderName = url.lastPathComponent
                manager.subpathsOfDirectoryAtPath(path, null)
                    ?.filterIsInstance<String>()
                    ?.filter { it.isSubtitleFileName() }
                    ?.forEach { subPath ->
                        val fileName = subPath.substringAfterLast('/')
                        stage(manager, "$path/$subPath", fileName, stagingDirectory)?.let { staged ->
                            picked += PickedSubtitleFile(
                                fileName = fileName,
                                sourcePath = staged,
                                sourceName = folderName,
                            )
                        }
                    }
            } else {
                val fileName = url.lastPathComponent ?: return@forEach
                if (!fileName.isSubtitleFileName()) return@forEach
                stage(manager, path, fileName, stagingDirectory)?.let { staged ->
                    picked += PickedSubtitleFile(
                        fileName = fileName,
                        sourcePath = staged,
                        sourceName = url.URLByDeletingLastPathComponent?.lastPathComponent,
                    )
                }
            }
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }
    return picked
}

/** Copies one file into the staging directory, keeping picked names unique. */
@OptIn(ExperimentalForeignApi::class)
private fun stage(
    manager: NSFileManager,
    sourcePath: String,
    fileName: String,
    stagingDirectory: String,
): String? {
    var destination = "$stagingDirectory/$fileName"
    var attempt = 1
    while (manager.fileExistsAtPath(destination)) {
        destination = "$stagingDirectory/${attempt}_$fileName"
        attempt++
    }
    return destination.takeIf { manager.copyItemAtPath(sourcePath, it, null) }
}
