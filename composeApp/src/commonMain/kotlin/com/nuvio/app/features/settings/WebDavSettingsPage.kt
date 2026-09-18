package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.webdav.ScanPhase
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import com.nuvio.app.features.webdav.WebDavProvider
import com.nuvio.app.features.webdav.WebDavScanProgress
import com.nuvio.app.features.webdav.WebDavSource
import com.nuvio.app.features.webdav.WebDavUiState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val WINDOW_STEP = 10
private const val WINDOW_MIN = 10
private const val WINDOW_MAX = 500

/**
 * The source the WebDAV sub-pages show. Held outside composition because on iPhone
 * every settings page is pushed as a screen of its own, so nothing remembered on
 * the library page would reach them.
 */
internal object WebDavSettingsSelection {
    var sourceId: String? by mutableStateOf(null)
}

/** Title for the source page: the source's own name. */
internal fun webDavSelectedSourceName(): String? {
    val id = WebDavSettingsSelection.sourceId ?: return null
    return WebDavLibraryRepository.uiState.value.sources.firstOrNull { it.id == id }?.displayName
}

// ------------------------------------------------------------------ library

internal fun LazyListScope.webDavSettingsContent(
    isTablet: Boolean,
    state: WebDavUiState,
    onSourceClick: (String) -> Unit,
) {
    item {
        var showAddSheet by rememberSaveable { mutableStateOf(false) }

        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_sources),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                if (state.sources.isEmpty()) {
                    SettingsMessageRow(
                        text = stringResource(Res.string.settings_webdav_empty),
                        isTablet = isTablet,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                state.sources.forEach { source ->
                    WebDavSourceRow(
                        isTablet = isTablet,
                        source = source,
                        state = state,
                        onClick = {
                            WebDavSettingsSelection.sourceId = source.id
                            onSourceClick(source.id)
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_webdav_add),
                    description = stringResource(Res.string.settings_webdav_add_description),
                    icon = Icons.Rounded.Add,
                    isTablet = isTablet,
                    onClick = { showAddSheet = true },
                )
            }
        }

        if (showAddSheet) {
            WebDavAddSourceSheet(onDismiss = { showAddSheet = false })
        }
    }
}

@Composable
private fun WebDavSourceRow(
    isTablet: Boolean,
    source: WebDavSource,
    state: WebDavUiState,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val progress = state.progress[source.id]
    val failed = progress?.phase == ScanPhase.Failed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = settingsRowHorizontalPadding(isTablet),
                vertical = settingsRowVerticalPadding(isTablet),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceIcon(isTablet = isTablet, enabled = source.enabled)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (isTablet) 16.dp else 14.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceStatus(source, state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) tokens.colors.danger else tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            progress?.takeIf { it.isRunning }?.let { running ->
                Spacer(modifier = Modifier.height(6.dp))
                ScanProgressBar(running)
            }
        }
    }
}

@Composable
private fun SourceIcon(isTablet: Boolean, enabled: Boolean) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .size(if (isTablet) 42.dp else 36.dp)
            .alpha(if (enabled) 1f else tokens.opacity.medium),
        color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
        shape = tokens.shapes.compactCard,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Cloud,
                contentDescription = null,
                tint = tokens.colors.accent,
            )
        }
    }
}

/** One line on what the source is doing, or what it holds when it is idle. */
@Composable
private fun sourceStatus(source: WebDavSource, state: WebDavUiState): String {
    scanStatus(state.progress[source.id])?.let { return it }

    val folders = state.folderCounts[source.id] ?: 0
    val matched = state.matchedCounts[source.id] ?: 0
    val summary = if (source.lastScanAt == null && folders == 0) {
        stringResource(Res.string.settings_webdav_never_scanned)
    } else {
        listOf(
            pluralStringResource(Res.plurals.settings_webdav_folder_count, folders, folders),
            stringResource(Res.string.settings_webdav_matched_count, matched),
        ).joinToString(" · ")
    }
    return if (source.enabled) {
        summary
    } else {
        "${stringResource(Res.string.settings_webdav_source_off)} · $summary"
    }
}

/** What a running or failed scan is doing; null when there is nothing to report. */
@Composable
private fun scanStatus(progress: WebDavScanProgress?): String? = when (progress?.phase) {
    ScanPhase.Listing -> stringResource(Res.string.settings_webdav_status_listing)
    ScanPhase.Folders -> stringResource(
        Res.string.settings_webdav_status_folders,
        progress.foldersDone,
        progress.foldersPlanned,
        pluralStringResource(Res.plurals.settings_webdav_file_count, progress.filesFound, progress.filesFound),
    )

    ScanPhase.Matching -> pluralStringResource(
        Res.plurals.settings_webdav_status_matching,
        progress.foldersPlanned,
        progress.foldersPlanned,
    )

    ScanPhase.Failed -> stringResource(
        Res.string.settings_webdav_status_failed,
        progress.errorMessage.orEmpty(),
    )

    else -> null
}

@Composable
private fun ScanProgressBar(progress: WebDavScanProgress) {
    val tokens = MaterialTheme.nuvio
    val fraction = when (progress.phase) {
        ScanPhase.Folders -> progress.foldersDone.toFloat() / progress.foldersPlanned.coerceAtLeast(1)
        ScanPhase.Matching -> progress.matchesResolved.toFloat() / progress.foldersPlanned.coerceAtLeast(1)
        else -> null
    }
    val modifier = Modifier.fillMaxWidth().height(4.dp)
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = modifier,
            color = tokens.colors.accent,
            trackColor = tokens.colors.borderSubtle,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = modifier,
            color = tokens.colors.accent,
            trackColor = tokens.colors.borderSubtle,
            drawStopIndicator = {},
        )
    }
}

/** Brand names stay as they are; only the custom option is words that translate. */
@Composable
internal fun WebDavProvider.label(): String =
    if (this == WebDavProvider.Custom) {
        stringResource(Res.string.settings_webdav_provider_custom)
    } else {
        displayName
    }

// ------------------------------------------------------------------- source

internal fun LazyListScope.webDavSourceContent(
    isTablet: Boolean,
    state: WebDavUiState,
    onReviewClick: () -> Unit,
    onRemoved: () -> Unit,
) {
    val source = state.sources.firstOrNull { it.id == WebDavSettingsSelection.sourceId }
    if (source == null) {
        item {
            SettingsRowGroup {
                SettingsMessageRow(
                    text = stringResource(Res.string.settings_webdav_source_missing),
                    isTablet = isTablet,
                )
            }
        }
        return
    }

    val progress = state.progress[source.id]
    val running = progress?.isRunning == true

    item(key = "webdav-source-status") {
        WebDavSourceOverview(isTablet = isTablet, source = source, state = state)
    }

    item(key = "webdav-source-library") {
        // Counted from the index rather than kept in the repository state: it only
        // matters here, and it changes whenever a scan or a fix lands.
        var worthChecking by remember(source.id) { mutableStateOf<Int?>(null) }
        LaunchedEffect(source.id, progress?.phase, state.matchedCounts[source.id], state.folderCounts[source.id]) {
            worthChecking = WebDavLibraryRepository.reviewRows(source.id)
                .count { it.reviewFilter() == WebDavReviewFilter.WorthChecking }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_library),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_webdav_enabled),
                    description = stringResource(Res.string.settings_webdav_enabled_description),
                    checked = source.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { WebDavLibraryRepository.setEnabled(source.id, it) },
                )
                SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_webdav_review),
                    description = worthChecking?.let { count ->
                        if (count == 0) {
                            stringResource(Res.string.settings_webdav_review_all_good)
                        } else {
                            pluralStringResource(Res.plurals.settings_webdav_review_worth_checking_count, count, count)
                        }
                    },
                    isTablet = isTablet,
                    onClick = {
                        WebDavReviewState.reset()
                        onReviewClick()
                    },
                )
            }
        }
    }

    item(key = "webdav-source-scanning") {
        var confirmRebuild by rememberSaveable { mutableStateOf(false) }

        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_scanning),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                SettingsStepperRow(
                    title = stringResource(Res.string.settings_webdav_window_size),
                    description = stringResource(Res.string.settings_webdav_window_size_description),
                    value = source.windowSize.toString(),
                    isTablet = isTablet,
                    canDecrement = source.windowSize > WINDOW_MIN,
                    canIncrement = source.windowSize < WINDOW_MAX,
                    onDecrement = {
                        WebDavLibraryRepository.setWindowSize(source.id, source.windowSize - WINDOW_STEP)
                    },
                    onIncrement = {
                        WebDavLibraryRepository.setWindowSize(source.id, source.windowSize + WINDOW_STEP)
                    },
                )
                SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_webdav_rebuild),
                    description = stringResource(Res.string.settings_webdav_rebuild_description),
                    enabled = !running,
                    isTablet = isTablet,
                    onClick = { confirmRebuild = true },
                )
            }
        }

        NuvioStatusModal(
            title = stringResource(Res.string.settings_webdav_rebuild_confirm_title),
            message = stringResource(Res.string.settings_webdav_rebuild_confirm_message),
            isVisible = confirmRebuild,
            confirmText = stringResource(Res.string.settings_webdav_rebuild),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmRebuild = false
                WebDavLibraryRepository.rebuild(source.id)
            },
            onDismiss = { confirmRebuild = false },
        )
    }

    item(key = "webdav-source-connection") {
        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_connection),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                SettingsValueRow(
                    title = stringResource(Res.string.settings_webdav_server_url),
                    value = source.baseUrl,
                    isTablet = isTablet,
                )
                SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
                SettingsValueRow(
                    title = stringResource(Res.string.settings_webdav_root_path),
                    value = source.rootPath.ifBlank { stringResource(Res.string.settings_webdav_server_root) },
                    isTablet = isTablet,
                )
                if (source.username.isNotBlank()) {
                    SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
                    SettingsValueRow(
                        title = stringResource(Res.string.settings_webdav_username),
                        value = source.username,
                        isTablet = isTablet,
                    )
                }
            }
        }
    }

    item(key = "webdav-source-remove") {
        var confirmRemove by rememberSaveable { mutableStateOf(false) }

        SettingsRowGroup {
            SettingsDestructiveRow(
                title = stringResource(Res.string.settings_webdav_remove),
                icon = Icons.Rounded.DeleteOutline,
                isTablet = isTablet,
                onClick = { confirmRemove = true },
            )
        }

        NuvioStatusModal(
            title = stringResource(Res.string.settings_webdav_remove_confirm_title, source.displayName),
            message = stringResource(Res.string.settings_webdav_remove_confirm_message),
            isVisible = confirmRemove,
            confirmText = stringResource(Res.string.action_remove),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmRemove = false
                WebDavLibraryRepository.removeSource(source.id)
                onRemoved()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** Past this width the scan tile joins the counts on one line. */
private val OverviewWideWidth = 640.dp

/**
 * What the source holds and the action that refreshes it, as a row of tiles in the
 * page's own card style. The page title already names the source, so the tiles
 * don't repeat it.
 */
@Composable
private fun WebDavSourceOverview(
    isTablet: Boolean,
    source: WebDavSource,
    state: WebDavUiState,
) {
    val tokens = MaterialTheme.nuvio
    val progress = state.progress[source.id]
    val status = scanStatus(progress)
        ?: stringResource(Res.string.settings_webdav_never_scanned).takeIf {
            source.lastScanAt == null && (state.folderCounts[source.id] ?: 0) == 0
        }
    val gap = if (isTablet) 12.dp else 8.dp

    BoxWithConstraints {
        val wide = maxWidth >= OverviewWideWidth
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                StatTile(
                    isTablet = isTablet,
                    value = state.folderCounts[source.id] ?: 0,
                    label = stringResource(Res.string.settings_webdav_stat_folders),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    isTablet = isTablet,
                    value = state.fileCounts[source.id] ?: 0,
                    label = stringResource(Res.string.settings_webdav_stat_files),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    isTablet = isTablet,
                    value = state.matchedCounts[source.id] ?: 0,
                    label = stringResource(Res.string.settings_webdav_stat_matched),
                    modifier = Modifier.weight(1f),
                )
                if (wide) {
                    ScanTile(
                        isTablet = isTablet,
                        source = source,
                        progress = progress,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            if (!wide) {
                ScanTile(
                    isTablet = isTablet,
                    source = source,
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (status != null) {
                Column(
                    modifier = Modifier.padding(horizontal = settingsRowHorizontalPadding(isTablet)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress?.phase == ScanPhase.Failed) {
                            tokens.colors.danger
                        } else {
                            tokens.colors.textMuted
                        },
                    )
                    progress?.takeIf { it.isRunning }?.let { ScanProgressBar(it) }
                }
            }
        }
    }
}

@Composable
private fun OverviewTile(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // The corners and fill of the row groups below, so tiles and groups read as one set.
    val shape = RoundedCornerShape(NuvioTokens.Radius.xxl)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        color = tokens.colors.surface,
        shape = shape,
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = if (isTablet) 20.dp else 14.dp,
                vertical = if (isTablet) 18.dp else 14.dp,
            ),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

@Composable
private fun StatTile(
    isTablet: Boolean,
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    OverviewTile(isTablet = isTablet, modifier = modifier.fillMaxHeight()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Scan now, drawn as a tile so it sits level with the counts instead of shouting over them. */
@Composable
private fun ScanTile(
    isTablet: Boolean,
    source: WebDavSource,
    progress: WebDavScanProgress?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val running = progress?.isRunning == true
    OverviewTile(
        isTablet = isTablet,
        modifier = modifier,
        onClick = { WebDavLibraryRepository.scan(source.id) },
        enabled = !running,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                NuvioLoadingIndicator(size = 22.dp, color = tokens.colors.textMuted)
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(
                    if (running) Res.string.settings_webdav_scanning else Res.string.settings_webdav_scan,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = if (running) tokens.colors.textMuted else tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
