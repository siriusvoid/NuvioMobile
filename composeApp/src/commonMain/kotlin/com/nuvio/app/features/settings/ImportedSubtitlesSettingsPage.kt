package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.subtitles.ImportedSubtitleFile
import com.nuvio.app.features.subtitles.ImportedSubtitlePack
import com.nuvio.app.features.subtitles.ImportedSubtitleRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Highest season a pack can be forced onto by hand. */
private const val MAX_SEASON = 40

/** Episode order: numbered seasons, then specials, then files that matched nothing. */
private val FILE_ORDER = compareBy<ImportedSubtitleFile>(
    { !it.isMatched },
    { it.season == 0 },
    { it.season ?: 0 },
    { it.episode ?: 0 },
    { it.fileName.lowercase() },
)

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

internal fun LazyListScope.importedSubtitlesContent(isTablet: Boolean) {
    item {
        val state by remember {
            ImportedSubtitleRepository.ensureLoaded()
            ImportedSubtitleRepository.uiState
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_imported_subtitles),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (state.packs.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_imported_subtitles_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    state.packs.forEachIndexed { index, pack ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        ImportedSubtitlePackRow(isTablet = isTablet, pack = pack)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedSubtitlePackRow(
    isTablet: Boolean,
    pack: ImportedSubtitlePack,
) {
    val scope = rememberCoroutineScope()
    var showFiles by remember(pack.id) { mutableStateOf(false) }

    /** Placement runs against the show's episode list, so it is fetched on demand. */
    fun replace(season: Int) {
        scope.launch {
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

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = pack.showName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = listOfNotNull(
                stringResource(
                    Res.string.settings_imported_subtitles_summary,
                    pluralStringResource(
                        Res.plurals.settings_imported_subtitles_file_count,
                        pack.files.size,
                        pack.files.size,
                    ),
                    stringResource(Res.string.settings_imported_subtitles_matched_count, pack.matchedCount),
                ),
                pack.sourceName?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val season = pack.currentSeason()
        StepperControl(
            label = stringResource(Res.string.settings_imported_subtitles_season),
            value = season.toString(),
            onDecrement = { replace((season - 1).coerceAtLeast(0)) },
            onIncrement = { replace((season + 1).coerceAtMost(MAX_SEASON)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.settings_imported_subtitles_keep),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.settings_imported_subtitles_keep_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = pack.keepAfterWatching,
                onCheckedChange = { ImportedSubtitleRepository.setKeepAfterWatching(pack.id, it) },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showFiles = !showFiles }) {
                Text(
                    text = if (showFiles) {
                        stringResource(Res.string.settings_imported_subtitles_hide_files)
                    } else {
                        stringResource(Res.string.settings_imported_subtitles_show_files)
                    },
                )
            }
            TextButton(onClick = { ImportedSubtitleRepository.deletePack(pack.id) }) {
                Text(
                    text = stringResource(Res.string.settings_imported_subtitles_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (showFiles) {
            pack.files.sortedWith(FILE_ORDER).forEach { file ->
                ImportedSubtitleFileRow(file = file)
            }
        }
    }
}

@Composable
private fun ImportedSubtitleFileRow(file: ImportedSubtitleFile) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                file.season != null && file.episode != null -> stringResource(
                    Res.string.settings_imported_subtitles_episode,
                    file.season,
                    file.episode,
                )

                file.isMatched -> stringResource(Res.string.settings_imported_subtitles_movie)
                else -> stringResource(Res.string.settings_imported_subtitles_unmatched)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (file.isMatched) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun StepperControl(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDecrement) { Text(text = "−") }
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onIncrement) { Text(text = "+") }
        }
    }
}
