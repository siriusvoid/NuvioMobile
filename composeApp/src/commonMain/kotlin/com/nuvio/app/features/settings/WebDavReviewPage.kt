package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.webdav.AnimeSearchHit
import com.nuvio.app.features.webdav.MatchReviewRow
import com.nuvio.app.features.webdav.PlacementStep
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val LOW_CONFIDENCE = 0.75f

internal fun LazyListScope.webDavReviewContent(
    isTablet: Boolean,
    sourceId: String?,
    rows: List<MatchReviewRow>,
    onChanged: () -> Unit,
) {
    if (sourceId == null || rows.isEmpty()) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_webdav_review_all),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    Text(
                        text = stringResource(Res.string.settings_webdav_review_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
        return
    }

    val unmatched = rows.filter { it.match == null || it.match.placementStep == PlacementStep.Unresolved }
    val lowConfidence = rows.filter { row ->
        val match = row.match ?: return@filter false
        match.placementStep != PlacementStep.Unresolved &&
            !match.userSet &&
            (match.confidence < LOW_CONFIDENCE || match.placementStep == PlacementStep.FlattenedAbsolute)
    }
    val mapped = rows.filter { it !in unmatched && it !in lowConfidence }

    reviewBucket(isTablet, Res.string.settings_webdav_review_unmatched, unmatched, onChanged)
    reviewBucket(isTablet, Res.string.settings_webdav_review_low, lowConfidence, onChanged)
    reviewBucket(isTablet, Res.string.settings_webdav_review_all, mapped, onChanged)
}

private fun LazyListScope.reviewBucket(
    isTablet: Boolean,
    titleRes: org.jetbrains.compose.resources.StringResource,
    rows: List<MatchReviewRow>,
    onChanged: () -> Unit,
) {
    if (rows.isEmpty()) return
    item {
        SettingsSection(title = stringResource(titleRes), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    ReviewRow(isTablet = isTablet, row = row, onChanged = onChanged)
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    isTablet: Boolean,
    row: MatchReviewRow,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(row.folderKey) { mutableStateOf(false) }
    var query by remember(row.folderKey) { mutableStateOf(row.match?.title ?: row.folderName) }
    var results by remember(row.folderKey) { mutableStateOf<List<AnimeSearchHit>>(emptyList()) }
    var selected by remember(row.folderKey) { mutableStateOf<AnimeSearchHit?>(null) }
    var season by remember(row.folderKey) { mutableStateOf(row.match?.season) }
    var offset by remember(row.folderKey) { mutableStateOf(row.match?.episodeOffset ?: 0) }
    var searching by remember(row.folderKey) { mutableStateOf(false) }

    val summary = row.match?.let { match ->
        buildString {
            append(match.title)
            match.season?.let { append(" · S").append(it) }
            if (match.episodeOffset != 0) append(" · ").append(match.episodeOffset)
        }
    } ?: stringResource(Res.string.settings_webdav_review_unmatched_label)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = row.folderName,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_webdav_review_files, row.fileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.match?.let { match ->
                Text(
                    text = stringResource(
                        Res.string.settings_webdav_review_step,
                        match.placementStep.label,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Excluding is the most common action on this screen, so it sits on the row
        // itself rather than behind the editor.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(Res.string.settings_webdav_review_fix))
            }
            TextButton(
                enabled = row.match != null,
                onClick = {
                    scope.launch {
                        WebDavLibraryRepository.setExcluded(
                            folderKey = row.folderKey,
                            excluded = row.match?.excluded != true,
                        )
                        onChanged()
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (row.match?.excluded == true) {
                            Res.string.settings_webdav_review_include
                        } else {
                            Res.string.settings_webdav_review_exclude
                        },
                    ),
                )
            }
        }

        if (expanded) {
            LaunchedEffect(row.folderKey) {
                searching = true
                results = WebDavLibraryRepository.searchForOverride(query)
                searching = false
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(Res.string.settings_webdav_review_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = !searching,
                onClick = {
                    scope.launch {
                        searching = true
                        results = WebDavLibraryRepository.searchForOverride(query)
                        searching = false
                    }
                },
            ) { Text(stringResource(Res.string.settings_webdav_review_search)) }

            results.take(5).forEach { hit ->
                TextButton(onClick = { selected = hit }) {
                    Text(
                        text = if (selected === hit) "• ${hit.title}" else hit.title,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.settings_webdav_review_season))
                TextButton(onClick = { season = ((season ?: 1) - 1).coerceAtLeast(0) }) { Text("−") }
                Text(season?.toString() ?: "—")
                TextButton(onClick = { season = (season ?: 0) + 1 }) { Text("+") }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.settings_webdav_review_offset))
                TextButton(onClick = { offset -= 1 }) { Text("−") }
                Text(offset.toString())
                TextButton(onClick = { offset += 1 }) { Text("+") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = selected != null,
                    onClick = {
                        val hit = selected ?: return@Button
                        scope.launch {
                            WebDavLibraryRepository.applyOverride(
                                folderKey = row.folderKey,
                                hit = hit,
                                season = season,
                                episodeOffset = offset,
                                treatAsMovie = false,
                            )
                            expanded = false
                            onChanged()
                        }
                    },
                ) { Text(stringResource(Res.string.settings_webdav_review_apply)) }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            WebDavLibraryRepository.rematch(row.folderKey)
                            expanded = false
                            onChanged()
                        }
                    },
                ) { Text(stringResource(Res.string.settings_webdav_review_rematch)) }
            }
        }
    }
}
