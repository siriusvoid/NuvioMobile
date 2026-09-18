package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkResolutionWithoutUI
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.concurrent.AtomicReference

/**
 * The chosen folder is kept as a security-scoped bookmark, since its path alone
 * stops being accessible after a relaunch. Access is opened once and held while
 * the folder stays chosen: downloads are written there from the session's
 * delegate queue and played from there by the player, both at arbitrary times.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadFolder {
    private const val bookmarkKey = "downloads_folder_bookmark"

    private class Chosen(val url: NSURL, val path: String, val accessed: Boolean)

    private val chosen = AtomicReference<Chosen?>(null)
    private val _folderName = MutableStateFlow<String?>(null)
    actual val folderName: StateFlow<String?> = _folderName.asStateFlow()

    init {
        restore()
    }

    /** Where downloads are saved now: the chosen folder while it can be reached, otherwise app storage. */
    fun directoryPath(): String = chosen.value?.path ?: appStorageDirectoryPath()

    /** A folder was chosen but can't be reached right now, so app storage stands in for it. */
    fun isChosenFolderUnavailable(): Boolean =
        chosen.value == null && NSUserDefaults.standardUserDefaults.dataForKey(bookmarkKey) != null

    /** Returns false, keeping the current folder, when [url] can't be written to or remembered. */
    fun choose(url: NSURL): Boolean {
        val accessed = url.startAccessingSecurityScopedResource()
        val path = url.path
        val bookmark = path
            ?.takeIf { NSFileManager.defaultManager.isWritableFileAtPath(it) }
            ?.let {
                url.bookmarkDataWithOptions(
                    options = 0uL,
                    includingResourceValuesForKeys = null,
                    relativeToURL = null,
                    error = null,
                )
            }
        if (path == null || bookmark == null) {
            if (accessed) url.stopAccessingSecurityScopedResource()
            return false
        }
        NSUserDefaults.standardUserDefaults.setObject(bookmark, forKey = bookmarkKey)
        replaceChosen(Chosen(url, path, accessed))
        return true
    }

    // "Important usage" counts space iOS would free on demand, as the Settings app does.
    actual fun freeSpaceBytes(): Long? =
        (NSURL.fileURLWithPath(directoryPath())
            .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
            ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? Number)
            ?.toLong()

    actual fun useAppStorage() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(bookmarkKey)
        replaceChosen(null)
    }

    private fun restore() {
        val bookmark = NSUserDefaults.standardUserDefaults.dataForKey(bookmarkKey) ?: return
        memScoped {
            val isStale = alloc<BooleanVar>()
            val url = NSURL.URLByResolvingBookmarkData(
                bookmark,
                options = NSURLBookmarkResolutionWithoutUI,
                relativeToURL = null,
                bookmarkDataIsStale = isStale.ptr,
                error = null,
            )
            // Unreachable for now (an unplugged drive, say): app storage takes over
            // for this run, and the bookmark is tried again on the next launch.
            val path = url?.path ?: return
            val accessed = url.startAccessingSecurityScopedResource()
            if (isStale.value) {
                // Moved or renamed since it was chosen; a fresh bookmark keeps it resolving.
                url.bookmarkDataWithOptions(
                    options = 0uL,
                    includingResourceValuesForKeys = null,
                    relativeToURL = null,
                    error = null,
                )?.let { NSUserDefaults.standardUserDefaults.setObject(it, forKey = bookmarkKey) }
            }
            replaceChosen(Chosen(url, path, accessed))
        }
    }

    private fun replaceChosen(next: Chosen?) {
        val previous = chosen.getAndSet(next)
        if (previous != null && previous.accessed && previous.url != next?.url) {
            previous.url.stopAccessingSecurityScopedResource()
        }
        _folderName.value = next?.url?.lastPathComponent
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun appStorageDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@Composable
internal actual fun rememberDownloadFolderPicker(
    onRejected: () -> Unit,
): DownloadFolderPickerHandle {
    val viewController = LocalUIViewController.current
    val latestOnRejected = rememberUpdatedState(onRejected)
    val delegate = remember {
        DownloadFolderPickerDelegate { url ->
            if (!DownloadFolder.choose(url)) latestOnRejected.value()
        }
    }

    DisposableEffect(delegate) {
        onDispose { delegate.detach() }
    }

    return remember(viewController, delegate) {
        object : DownloadFolderPickerHandle {
            override val isSupported: Boolean = true

            override fun launch() {
                val picker = UIDocumentPickerViewController(
                    forOpeningContentTypes = listOfNotNull(UTType.typeWithIdentifier("public.folder")),
                    asCopy = false,
                )
                picker.allowsMultipleSelection = false
                picker.delegate = delegate
                viewController.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class DownloadFolderPickerDelegate(
    onPicked: (NSURL) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var callback: ((NSURL) -> Unit)? = onPicked

    fun detach() {
        callback = null
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        callback?.invoke(url)
    }
}
