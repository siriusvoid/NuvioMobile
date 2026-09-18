package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.subtitles.ImportedSubtitleFile
import com.nuvio.app.features.subtitles.ImportedSubtitlePack
import com.nuvio.app.features.subtitles.ImportedSubtitleRepository
import com.nuvio.app.features.webdav.WebDavReleaseName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Highest season a pack can be forced onto by hand. */
private const val MAX_SEASON = 40

private val FILE_CODE_GAP = 12.dp
private val UNMATCHED_DOT_SIZE = 8.dp

/** Episode order: numbered seasons, then specials, then files that matched nothing. */
private val FILE_ORDER = compareBy<ImportedSubtitleFile>(
    { !it.isMatched },
    { it.season == 0 },
    { it.season ?: 0 },
    { it.episode ?: 0 },
    { it.fileName.lowercase() },
)

private val ImportedSubtitlePack.isMovie: Boolean
    get() = metaType.equals("movie", ignoreCase = true)

/**
 * The season the pack sits on: the one picked by hand, otherwise the one most of
 * its numbered episodes were placed in. Specials alone count only when nothing
 * else placed.
 */
private fun ImportedSubtitlePack.currentSeason(): Int {
    seasonOverride?.let { return it }
    val placed = files.mapNotNull { it.season }
    return placed.filter { it != 0 }.mostCommon()
        ?: placed.mostCommon()
        ?: mapperSeason
        ?: 1
}

private fun List<Int>.mostCommon(): Int? =
    groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/**
 * The pack the pack page shows. Held outside composition because on iPhone every
 * settings page is pushed as a screen of its own.
 */
internal object ImportedSubtitlesSelection {
    var packId: String? by mutableStateOf(null)
}

/** Title for the pack page: the show the subtitles belong to. */
internal fun importedSubtitleSelectedPackName(): String? {
    val id = ImportedSubtitlesSelection.packId ?: return null
    return ImportedSubtitleRepository.uiState.value.packs.firstOrNull { it.id == id }?.showName
}

@Composable
private fun rememberImportedPacks(): List<ImportedSubtitlePack> {
    val state by remember {
        ImportedSubtitleRepository.ensureLoaded()
        ImportedSubtitleRepository.uiState
    }.collectAsStateWithLifecycle()
    return state.packs
}

// --------------------------------------------------------------------- list

internal fun LazyListScope.importedSubtitlesContent(
    isTablet: Boolean,
    onPackClick: () -> Unit,
) {
    item {
        val packs = rememberImportedPacks()

        SettingsSection(
            title = stringResource(Res.string.settings_imported_subtitles_section_packs),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                if (packs.isEmpty()) {
                    SettingsMessageRow(
                        text = stringResource(Res.string.settings_imported_subtitles_empty),
                        isTablet = isTablet,
                    )
                } else {
                    packs.forEachIndexed { index, pack ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        PackRow(
                            isTablet = isTablet,
                            pack = pack,
                            onClick = {
                                ImportedSubtitlesSelection.packId = pack.id
                                onPackClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackRow(
    isTablet: Boolean,
    pack: ImportedSubtitlePack,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val unmatched = pack.files.size - pack.matchedCount

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
        Surface(
            modifier = Modifier.size(if (isTablet) 42.dp else 36.dp),
            color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
            shape = tokens.shapes.compactCard,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Subtitles,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (isTablet) 16.dp else 14.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pack.showName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unmatched > 0) {
                    UnmatchedDot(
                        description = pluralStringResource(
                            Res.plurals.settings_imported_subtitles_unmatched_count,
                            unmatched,
                            unmatched,
                        ),
                    )
                }
            }
            // What tells two sets of one show apart: the release group when the folder
            // names one, otherwise the folder name as the user left it, e.g. "mudabone".
            val group = remember(pack.sourceName) {
                pack.sourceName?.trim()?.takeIf { it.isNotEmpty() }?.let { folder ->
                    WebDavReleaseName.describe(folder).group ?: folder
                }
            }
            Text(
                text = listOfNotNull(
                    stringResource(Res.string.settings_imported_subtitles_season_value, pack.currentSeason())
                        .takeUnless { pack.isMovie },
                    pluralStringResource(
                        Res.plurals.settings_imported_subtitles_file_count,
                        pack.files.size,
                        pack.files.size,
                    ),
                    group,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --------------------------------------------------------------------- pack

internal fun LazyListScope.importedSubtitlePackContent(
    isTablet: Boolean,
    onDeleted: () -> Unit,
) {
    item(key = "subtitle-pack-placement") {
        val pack = rememberImportedPacks().firstOrNull { it.id == ImportedSubtitlesSelection.packId }
        if (pack == null) {
            SettingsRowGroup {
                SettingsMessageRow(
                    text = stringResource(Res.string.settings_imported_subtitles_missing),
                    isTablet = isTablet,
                )
            }
        } else {
            PackPlacementSection(isTablet = isTablet, pack = pack)
        }
    }

    item(key = "subtitle-pack-files") {
        val pack = rememberImportedPacks().firstOrNull { it.id == ImportedSubtitlesSelection.packId }
            ?: return@item
        val files = remember(pack.files) { pack.files.sortedWith(FILE_ORDER) }
        val codes = files.map { file ->
            when {
                file.season != null && file.episode != null ->
                    stringResource(Res.string.settings_imported_subtitles_episode, file.season, file.episode)
                // Matched with no episode is a film; Review matches tags films with the same word.
                file.isMatched -> stringResource(Res.string.media_movie)
                else -> null
            }
        }
        val codeWidth = rememberCodeColumnWidth(codes)
        val rowPadding = settingsRowHorizontalPadding(isTablet)

        SettingsSection(
            title = pluralStringResource(Res.plurals.settings_imported_subtitles_file_count, files.size, files.size)
                .uppercase(),
            isTablet = isTablet,
        ) {
            SettingsRowGroup {
                files.forEachIndexed { index, file ->
                    if (index > 0) SettingsRowSeparator(inset = rowPadding + codeWidth + FILE_CODE_GAP)
                    FileRow(isTablet = isTablet, file = file, code = codes[index], codeWidth = codeWidth)
                }
            }
        }
    }

    item(key = "subtitle-pack-delete") {
        val pack = rememberImportedPacks().firstOrNull { it.id == ImportedSubtitlesSelection.packId }
            ?: return@item
        var confirmDelete by rememberSaveable { mutableStateOf(false) }

        SettingsRowGroup {
            SettingsDestructiveRow(
                title = stringResource(Res.string.settings_imported_subtitles_delete_pack),
                icon = Icons.Rounded.DeleteOutline,
                isTablet = isTablet,
                onClick = { confirmDelete = true },
            )
        }

        NuvioStatusModal(
            title = stringResource(Res.string.settings_imported_subtitles_delete_confirm_title),
            message = stringResource(Res.string.settings_imported_subtitles_delete_confirm_message),
            isVisible = confirmDelete,
            confirmText = stringResource(Res.string.action_delete),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmDelete = false
                ImportedSubtitleRepository.deletePack(pack.id)
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PackPlacementSection(isTablet: Boolean, pack: ImportedSubtitlePack) {
    val scope = rememberCoroutineScope()
    val applied = pack.currentSeason()
    // Shows the tapped number at once; placement needs the show's episode list,
    // which can take a moment to arrive.
    var shown by remember(pack.id, applied) { mutableStateOf(applied) }
    var placement by remember(pack.id) { mutableStateOf<Job?>(null) }

    fun place(season: Int) {
        shown = season
        placement?.cancel()
        placement = scope.launch {
            val meta = runCatching {
                MetaDetailsRepository.fetch(type = pack.metaType, id = pack.metaId, cacheResult = true)
            }.getOrNull()
            ImportedSubtitleRepository.updatePlacement(
                packId = pack.id,
                meta = meta,
                seasonOverride = season,
            )
        }
    }

    SettingsSection(
        title = stringResource(Res.string.settings_imported_subtitles_section_placement),
        isTablet = isTablet,
    ) {
        SettingsRowGroup {
            if (!pack.isMovie) {
                SettingsStepperRow(
                    title = stringResource(Res.string.settings_imported_subtitles_season),
                    description = stringResource(Res.string.settings_imported_subtitles_season_description),
                    value = shown.toString(),
                    isTablet = isTablet,
                    canDecrement = shown > 0,
                    canIncrement = shown < MAX_SEASON,
                    onDecrement = { place((shown - 1).coerceAtLeast(0)) },
                    onIncrement = { place((shown + 1).coerceAtMost(MAX_SEASON)) },
                )
                SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_imported_subtitles_keep),
                description = stringResource(Res.string.settings_imported_subtitles_keep_description),
                checked = pack.keepAfterWatching,
                isTablet = isTablet,
                onCheckedChange = { ImportedSubtitleRepository.setKeepAfterWatching(pack.id, it) },
            )
            pack.sourceName?.takeIf { it.isNotBlank() }?.let { folder ->
                SettingsRowSeparator(inset = settingsRowHorizontalPadding(isTablet))
                SettingsValueRow(
                    title = stringResource(Res.string.settings_imported_subtitles_folder),
                    value = folder,
                    isTablet = isTablet,
                )
            }
        }
    }
}

/** Width of the episode column: the widest code in this set, so every file name starts level. */
@Composable
private fun rememberCodeColumnWidth(codes: List<String?>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    return remember(codes, style, density) {
        val widest = codes.filterNotNull().maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
        with(density) { widest.toDp() }.coerceAtLeast(UNMATCHED_DOT_SIZE)
    }
}

/**
 * The episode leads the row as quiet text, the way a track list numbers its songs.
 * A file placed nowhere has no code; the red dot Review matches uses for "unmatched"
 * takes its place, centred in the column.
 */
@Composable
private fun FileRow(isTablet: Boolean, file: ImportedSubtitleFile, code: String?, codeWidth: Dp) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = settingsRowHorizontalPadding(isTablet), vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(FILE_CODE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(codeWidth),
            contentAlignment = if (code != null) Alignment.CenterStart else Alignment.Center,
        ) {
            if (code != null) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            } else if (!file.isMatched) {
                UnmatchedDot(description = stringResource(Res.string.settings_imported_subtitles_unmatched))
            }
        }
        Text(
            text = file.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The red dot Review matches uses for "unmatched", with words for VoiceOver. */
@Composable
private fun UnmatchedDot(description: String) {
    Box(
        modifier = Modifier
            .size(UNMATCHED_DOT_SIZE)
            .clip(MaterialTheme.nuvio.shapes.chip)
            .background(MaterialTheme.nuvio.colors.danger)
            .semantics { contentDescription = description },
    )
}
