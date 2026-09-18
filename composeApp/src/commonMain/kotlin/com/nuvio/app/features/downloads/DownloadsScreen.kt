package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import androidx.compose.foundation.layout.fillMaxSize
import com.nuvio.app.core.ui.NuvioNativeHeaderTitle
import com.nuvio.app.navigation.LocalUseNativeNavigation
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    // Downloads deleted in the Files app meanwhile drop out of the list.
    LaunchedEffect(Unit) { DownloadsRepository.pruneMissingFiles() }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadsPendingBulkDeletion by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val folderNotWritableText = stringResource(Res.string.downloads_location_not_writable)
    val downloadFolderName by DownloadFolder.folderName.collectAsStateWithLifecycle()
    val downloadFolderPicker = rememberDownloadFolderPicker(
        onRejected = { NuvioToastController.show(folderNotWritableText) },
    )

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    val headerTitle = if (selectedShowId == null) {
        stringResource(Res.string.compose_settings_root_downloads_title)
    } else {
        selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = headerTitle,
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                downloadFolderName = downloadFolderName,
                downloadFolderPicker = downloadFolderPicker,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onDeleteDownload = { downloadPendingDeletionId = it },
                onDeleteDownloads = { downloadsPendingBulkDeletion = it },
            )
        } else {
            downloadsShowContent(
                showId = selectedShowId.orEmpty(),
                episodes = completedEpisodes,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
                onDeleteDownloads = { downloadsPendingBulkDeletion = it },
            )
        }
    }

        if (LocalUseNativeNavigation.current) {
            NuvioNativeHeaderTitle(title = headerTitle)
        }
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }

    // A whole show or season goes with one confirmation instead of one per episode.
    val pendingBulkDeletion = downloadsPendingBulkDeletion
    if (pendingBulkDeletion != null) {
        val deleting = uiState.items.filter { it.id in pendingBulkDeletion }
        val freedBytes = deleting.sumOf { item ->
            if (item.status == DownloadStatus.Completed) item.totalBytes ?: item.downloadedBytes else item.downloadedBytes
        }
        NuvioStatusModal(
            title = pluralStringResource(Res.plurals.downloads_delete_episodes_title, deleting.size, deleting.size),
            message = stringResource(Res.string.downloads_delete_frees_space, formatBytes(freedBytes)),
            isVisible = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.deleteDownloads(pendingBulkDeletion)
                downloadsPendingBulkDeletion = null
            },
            onDismiss = { downloadsPendingBulkDeletion = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    downloadFolderName: String?,
    downloadFolderPicker: DownloadFolderPickerHandle,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onDeleteDownloads: (List<String>) -> Unit,
) {
    val activeItems = uiState.activeItems
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) ->
            episodes.firstOrNull()?.let { first ->
                first to episodes
            }
        }
        .sortedBy { (item, _) -> item.title.lowercase() }

    if (downloadFolderPicker.isSupported) {
        item(key = "download-location") {
            DownloadLocationGroup(
                folderName = downloadFolderName,
                onChooseFolder = downloadFolderPicker::launch,
            )
        }
    }

    if (activeItems.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        // A show's unfinished episodes sit under one heading, in episode order; the
        // show added last comes first. A movie stays a single row.
        activeItems
            .groupBy { if (it.isEpisode) "show:${it.parentMetaId}" else "movie:${it.id}" }
            .values
            .forEach { group ->
                val first = group.first()
                val rows = if (first.isEpisode) {
                    val episodes = group.sortedForSeriesDownloads()
                    val showDownloads = uiState.items.filter { it.isEpisode && it.parentMetaId == first.parentMetaId }
                    item(key = "active-show-${first.parentMetaId}") {
                        ActiveShowHeading(
                            title = first.title,
                            finished = showDownloads.count { it.status == DownloadStatus.Completed },
                            total = showDownloads.size,
                            anyDownloading = episodes.any { it.status == DownloadStatus.Downloading },
                            onPauseAll = { DownloadsRepository.pauseDownloads(episodes.map { it.id }) },
                            onResumeAll = { DownloadsRepository.resumeDownloads(episodes.map { it.id }) },
                            onDeleteAll = { onDeleteDownloads(episodes.map { it.id }) },
                        )
                    }
                    episodes
                } else {
                    group
                }
                items(
                    items = rows,
                    key = { it.id },
                ) { item ->
                    DownloadRow(
                        item = item,
                        onOpen = { onOpenDownload(item) },
                        onPause = { DownloadsRepository.pauseDownload(item.id) },
                        onResume = { DownloadsRepository.resumeDownload(item.id) },
                        onRetry = { DownloadsRepository.retryDownload(item.id) },
                        onDelete = { onDeleteDownload(item.id) },
                    )
                }
            }
    }

    if (completedMovies.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_movies))
        }
        items(
            items = completedMovies,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_shows))
        }
        items(
            items = completedShows,
            key = { (item, _) -> item.parentMetaId },
        ) { (item, episodes) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clickable { onOpenShow(item.parentMetaId, item.title) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(Res.string.downloads_episode_count, episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onDeleteDownloads(episodes.map { it.id }) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.downloads_delete_show),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onDeleteDownloads: (List<String>) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            // The delete button lines up with the episode rows' own delete buttons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SectionTitle(
                        if (seasonNumber == 0) {
                            stringResource(Res.string.episodes_specials)
                        } else {
                            stringResource(Res.string.episodes_season, seasonNumber)
                        },
                    )
                }
                IconButton(onClick = { onDeleteDownloads(entries.map { it.id }) }) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.downloads_delete_season),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = item.isPlayable, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

/** Where new downloads are saved; existing ones stay where they were downloaded. */
@Composable
private fun DownloadLocationGroup(
    folderName: String?,
    onChooseFolder: () -> Unit,
) {
    SettingsGroup(
        isTablet = false,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        SettingsNavigationRow(
            title = stringResource(Res.string.downloads_location_title),
            description = folderName ?: stringResource(Res.string.downloads_location_app_storage),
            icon = Icons.Rounded.Folder,
            isTablet = false,
            onClick = onChooseFolder,
        )
        if (folderName != null) {
            SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(
                title = stringResource(Res.string.downloads_location_use_app_storage),
                description = null,
                icon = Icons.Rounded.PhoneIphone,
                isTablet = false,
                onClick = DownloadFolder::useAppStorage,
            )
        }
    }
}

/**
 * One show in the active list. Its buttons act on all of the show's unfinished
 * episodes and line up with the episode rows' own buttons below.
 */
@Composable
private fun ActiveShowHeading(
    title: String,
    finished: Int,
    total: Int,
    anyDownloading: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 26.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "· " + stringResource(Res.string.downloads_live_progress_count, finished, total),
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (anyDownloading) {
            IconButton(onClick = onPauseAll) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = stringResource(Res.string.downloads_pause_all),
                    // Off a card, so the rows' white has to be asked for.
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            IconButton(onClick = onResumeAll) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.downloads_resume_all),
                    // Off a card, so the rows' white has to be asked for.
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        IconButton(onClick = onDeleteAll) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.downloads_delete_show),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
