package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.webdav.AnimeReleaseParser
import com.nuvio.app.features.webdav.AnimeSearchHit
import com.nuvio.app.features.webdav.MatchReviewRow
import com.nuvio.app.features.webdav.PlacementStep
import com.nuvio.app.features.webdav.ReleaseLabel
import com.nuvio.app.features.webdav.WebDavReleaseName
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import com.nuvio.app.features.webdav.WebDavMatch
import com.nuvio.app.features.webdav.WebDavUiState
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val LOW_CONFIDENCE = 0.75f

/** Past this many folders the list gets a filter field. */
private const val FIND_FIELD_THRESHOLD = 6

private const val CANDIDATE_LIMIT = 8

/** The sheet's own column, so title, pill, tick and Apply stay in one glance. */
private val SHEET_CONTENT_MAX_WIDTH = 620.dp

/** Separators start past the poster, as iOS insets them past a row's leading art. */
private val REVIEW_SEPARATOR_INSET = 72.dp
private val CANDIDATE_SEPARATOR_INSET = 68.dp

/** Below this the filters stay chips, which can wrap; segments cannot. */
private val SEGMENTED_MIN_WIDTH = 640.dp

private const val MAX_SEASON = 99

internal enum class WebDavReviewFilter { WorthChecking, Matched, Excluded }

internal fun MatchReviewRow.isUnmatched(): Boolean =
    match == null || match.placementStep == PlacementStep.Unresolved

/** Placed, but by a rung of the ladder that guesses. A fix by hand is never unsure. */
internal fun MatchReviewRow.isUnsure(): Boolean {
    val match = match ?: return false
    return !isUnmatched() &&
        !match.userSet &&
        (match.confidence < LOW_CONFIDENCE || match.placementStep == PlacementStep.FlattenedAbsolute)
}

internal fun MatchReviewRow.reviewFilter(): WebDavReviewFilter = when {
    match?.excluded == true -> WebDavReviewFilter.Excluded
    isUnmatched() || isUnsure() -> WebDavReviewFilter.WorthChecking
    else -> WebDavReviewFilter.Matched
}

/**
 * "Shingeki no Kyojin Season 3 - 01 ~ 12 · 12 files [Erai-raws · 720p]". The brackets
 * hold what tells two copies of one show apart, and survive the cut before the title does.
 */
private fun folderLine(label: ReleaseLabel, fileCount: String, withTitle: Boolean): String {
    val head = if (withTitle) label.title + " \u00b7 " + fileCount else fileCount
    val extras = listOfNotNull(label.group, label.quality)
    return if (extras.isEmpty()) head else head + " [" + extras.joinToString(" \u00b7 ") + "]"
}

private fun MatchReviewRow.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return listOfNotNull(folderName, match?.displayName, match?.title)
        .any { it.contains(query, ignoreCase = true) }
}

private fun PlacementStep.labelRes(): StringResource = when (this) {
    PlacementStep.ExplicitSeasonEpisode -> Res.string.settings_webdav_step_filename
    PlacementStep.MapperSeason -> Res.string.settings_webdav_step_mapper_season
    PlacementStep.EpisodeCountFit -> Res.string.settings_webdav_step_episode_count
    PlacementStep.AirDateAnchor -> Res.string.settings_webdav_step_air_date
    PlacementStep.FlattenedAbsolute -> Res.string.settings_webdav_step_flattened
    PlacementStep.Manual -> Res.string.settings_webdav_step_manual
    PlacementStep.Unresolved -> Res.string.settings_webdav_step_unresolved
}

private fun WebDavReviewFilter.labelRes(): StringResource = when (this) {
    WebDavReviewFilter.WorthChecking -> Res.string.settings_webdav_review_worth_checking
    WebDavReviewFilter.Matched -> Res.string.settings_webdav_review_matched
    WebDavReviewFilter.Excluded -> Res.string.settings_webdav_review_excluded
}

/**
 * The review page's rows and what the user narrowed them to. Outside composition
 * for the same reason as [WebDavSettingsSelection], and because the rows feed
 * several lazy items at once.
 */
internal object WebDavReviewState {
    var sourceId: String? by mutableStateOf(null)
        private set
    var rows: List<MatchReviewRow>? by mutableStateOf(null)
        private set
    var filter by mutableStateOf(WebDavReviewFilter.WorthChecking)
        private set
    var query by mutableStateOf("")
        private set
    var editingFolderKey: String? by mutableStateOf(null)

    /**
     * Folders excluded or included from the list itself. They stay where they are
     * until the list is narrowed again, so a row never vanishes under the finger
     * that tapped it.
     */
    var keptVisible by mutableStateOf(emptySet<String>())
        private set

    fun selectFilter(value: WebDavReviewFilter) {
        filter = value
        keptVisible = emptySet()
    }

    fun setQuery(value: String) {
        query = value
        keptVisible = emptySet()
    }

    fun keepVisible(folderKey: String) {
        keptVisible = keptVisible + folderKey
    }

    /** Forgets the last visit, so the page opens on what needs attention now. */
    fun reset() {
        sourceId = null
        rows = null
        editingFolderKey = null
        keptVisible = emptySet()
    }

    suspend fun load(sourceId: String) {
        val loaded = WebDavLibraryRepository.reviewRows(sourceId)
        if (this.sourceId != sourceId) {
            filter = if (loaded.any { it.reviewFilter() == WebDavReviewFilter.WorthChecking }) {
                WebDavReviewFilter.WorthChecking
            } else {
                WebDavReviewFilter.Matched
            }
            query = ""
            editingFolderKey = null
            keptVisible = emptySet()
        }
        this.sourceId = sourceId
        rows = loaded
    }
}

internal fun LazyListScope.webDavReviewContent(
    isTablet: Boolean,
    state: WebDavUiState,
) {
    val sourceId = WebDavSettingsSelection.sourceId
    val rows = WebDavReviewState.rows.takeIf { sourceId != null && WebDavReviewState.sourceId == sourceId }

    item(key = "webdav-review-controls") {
        if (sourceId != null) {
            val phase = state.progress[sourceId]?.phase
            LaunchedEffect(sourceId, phase, state.matchedCounts[sourceId], state.folderCounts[sourceId]) {
                WebDavReviewState.load(sourceId)
            }
        }
        when {
            rows == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                NuvioLoadingIndicator()
            }

            rows.isEmpty() -> SettingsGroup(isTablet = isTablet) {
                SettingsMessageRow(
                    text = stringResource(Res.string.settings_webdav_review_empty),
                    isTablet = isTablet,
                )
            }

            else -> ReviewControls(rows = rows)
        }
    }

    if (rows.isNullOrEmpty() || sourceId == null) return

    val query = WebDavReviewState.query.trim()
    if (query.isEmpty()) {
        val filter = WebDavReviewState.filter
        // A row just excluded or included keeps its place until the list is narrowed again.
        val visible = rows.filter { it.folderKey in WebDavReviewState.keptVisible || it.reviewFilter() == filter }
        if (visible.isEmpty()) {
            item(key = "webdav-review-none") {
                SettingsGroup(isTablet = isTablet) {
                    SettingsMessageRow(
                        text = stringResource(
                            if (filter == WebDavReviewFilter.WorthChecking) {
                                Res.string.settings_webdav_review_nothing_to_check
                            } else {
                                Res.string.settings_webdav_review_none
                            },
                        ),
                        isTablet = isTablet,
                    )
                }
            }
            return
        }
        reviewRowItems(isTablet = isTablet, sourceId = sourceId, rows = visible)
        return
    }

    // A search looks through every folder, whatever the chips say, and groups what it
    // finds so the answer to "where is it?" is on screen.
    val groups = WebDavReviewFilter.entries
        .map { bucket -> bucket to rows.filter { it.reviewFilter() == bucket && it.matchesQuery(query) } }
        .filter { (_, matches) -> matches.isNotEmpty() }

    if (groups.isEmpty()) {
        item(key = "webdav-review-no-results") {
            SettingsGroup(isTablet = isTablet) {
                SettingsMessageRow(
                    text = stringResource(Res.string.settings_webdav_review_no_results),
                    isTablet = isTablet,
                )
            }
        }
        return
    }

    groups.forEach { (bucket, matches) ->
        item(key = "webdav-review-group-" + bucket.name) {
            NuvioSectionLabel(
                text = stringResource(bucket.labelRes()).uppercase() + " \u00b7 " + matches.size,
                modifier = Modifier.padding(horizontal = settingsRowHorizontalPadding(isTablet)),
            )
        }
        reviewRowItems(isTablet = isTablet, sourceId = sourceId, rows = matches)
    }
}

private fun LazyListScope.reviewRowItems(
    isTablet: Boolean,
    sourceId: String,
    rows: List<MatchReviewRow>,
) {
    // One group, the way iOS draws a list: the rows are few enough per filter that
    // they can share a single item.
    item(key = "webdav-review-group-rows-" + rows.first().folderKey) {
        SettingsRowGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) SettingsRowSeparator(inset = REVIEW_SEPARATOR_INSET)
                ReviewRowItem(isTablet = isTablet, sourceId = sourceId, row = row)
            }
        }
    }
}

@Composable
private fun ReviewRowItem(
    isTablet: Boolean,
    sourceId: String,
    row: MatchReviewRow,
) {
    val scope = rememberCoroutineScope()
    val excluded = row.match?.excluded == true

    WebDavReviewCard(
        isTablet = isTablet,
        row = row,
        onClick = { WebDavReviewState.editingFolderKey = row.folderKey },
        onToggleExcluded = {
            WebDavReviewState.keepVisible(row.folderKey)
            scope.launch {
                WebDavLibraryRepository.setExcluded(row.folderKey, excluded = !excluded)
                WebDavReviewState.load(sourceId)
            }
        },
    )
    if (WebDavReviewState.editingFolderKey == row.folderKey) {
        WebDavFixMatchSheet(
            row = row,
            sourceId = sourceId,
            onDismiss = { WebDavReviewState.editingFolderKey = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewControls(rows: List<MatchReviewRow>) {
    val tokens = MaterialTheme.nuvio
    val counts = remember(rows) { rows.groupingBy { it.reviewFilter() }.eachCount() }
    val searching = WebDavReviewState.query.isNotBlank()
    val selected = WebDavReviewState.filter.takeUnless { searching }
    // Excluded only earns a place once something is in it.
    val shown = remember(counts, WebDavReviewState.filter) {
        WebDavReviewFilter.entries.filter { option ->
            option != WebDavReviewFilter.Excluded ||
                (counts[option] ?: 0) > 0 ||
                WebDavReviewState.filter == option
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints {
            // Segments cannot wrap, so a narrow page keeps the chips.
            if (maxWidth >= SEGMENTED_MIN_WIDTH) {
                SettingsSegmentedTabs(
                    labels = shown.map { stringResource(it.labelRes()) },
                    counts = shown.map { counts[it] ?: 0 },
                    selectedIndex = shown.indexOf(selected).takeIf { it >= 0 },
                    onSelect = { index -> WebDavReviewState.selectFilter(shown[index]) },
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.forEach { option ->
                        SettingsFilterChip(
                            label = stringResource(option.labelRes()),
                            count = counts[option] ?: 0,
                            selected = option == selected,
                            onClick = { WebDavReviewState.selectFilter(option) },
                        )
                    }
                }
            }
        }

        if (rows.size > FIND_FIELD_THRESHOLD) {
            SettingsSearchField(
                value = WebDavReviewState.query,
                onValueChange = { WebDavReviewState.setQuery(it) },
                placeholder = stringResource(Res.string.settings_webdav_review_find),
            )
        }
    }
}

@Composable
private fun WebDavReviewCard(
    isTablet: Boolean,
    row: MatchReviewRow,
    onClick: () -> Unit,
    onToggleExcluded: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val unmatched = row.isUnmatched()
    val match = row.match?.takeUnless { unmatched }
    val excluded = row.match?.excluded == true
    val fileCount = pluralStringResource(Res.plurals.settings_webdav_file_count, row.fileCount, row.fileCount)
    val label = remember(row.folderName) { WebDavReleaseName.describe(row.folderName) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsPosterThumb(
            url = match?.displayPoster,
            placeholderIcon = Icons.Rounded.Movie,
            width = if (isTablet) 46.dp else 42.dp,
            dimmed = excluded,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (excluded) tokens.opacity.medium else 1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = match?.displayName ?: label.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    unmatched -> StatusPill(color = tokens.colors.danger)
                    row.isUnsure() -> StatusPill(color = tokens.colors.warning)
                }
                match?.let { placedMatchTag(it) }?.let { SettingsTag(text = it) }
            }
            Text(
                text = folderLine(label = label, fileCount = fileCount, withTitle = match != null),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggleExcluded) {
            Icon(
                imageVector = if (excluded) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(
                    if (excluded) {
                        Res.string.settings_webdav_review_include
                    } else {
                        Res.string.settings_webdav_review_exclude
                    },
                ),
                tint = tokens.colors.textMuted,
            )
        }
    }
}

/** Colour alone: yellow for an uncertain match, red for none at all. */
@Composable
private fun StatusPill(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(MaterialTheme.nuvio.shapes.chip)
            .background(color),
    )
}

/** "S2" for a series placed in a season, "Movie" for a film. */
@Composable
private fun placedMatchTag(match: WebDavMatch): String? = when {
    !match.isSeries -> stringResource(Res.string.media_movie)
    match.season == 0 -> stringResource(Res.string.settings_webdav_anime_type_special)
    match.season != null -> stringResource(Res.string.settings_webdav_season_badge, match.season)
    else -> null
}

// ---------------------------------------------------------------- fix match

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebDavFixMatchSheet(
    row: MatchReviewRow,
    sourceId: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // Outlives the sheet's own composition, which on iOS is torn down as it closes.
    val scope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Half height fits the handful of candidates a search returns; drag lifts it.
        fullHeight = false,
    ) {
        FixMatchContent(
            row = row,
            onDone = {
                scope.launch {
                    WebDavReviewState.load(sourceId)
                    dismissNuvioBottomSheet(sheetState, onDismiss)
                }
            },
        )
    }
}

@Composable
private fun FixMatchContent(
    row: MatchReviewRow,
    onDone: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val current = row.match?.takeUnless { row.isUnmatched() }

    var query by remember {
        mutableStateOf(
            current?.title
                ?: AnimeReleaseParser.parseFolder(row.folderName).title.ifBlank { row.folderName },
        )
    }
    // Null until the first search answers, so "no candidates" never shows before it has.
    var results by remember { mutableStateOf<List<AnimeSearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AnimeSearchHit?>(null) }
    var season by remember { mutableStateOf(row.match?.season ?: 1) }
    // The picked result's season comes from the mapper, a network call, so Apply waits on it.
    var seasonLookup by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    suspend fun runSearch() {
        if (query.isBlank()) return
        searching = true
        results = WebDavLibraryRepository.searchForOverride(query)
        searching = false
    }

    LaunchedEffect(Unit) { runSearch() }

    // Whatever the search ranks first, the match in force is the one to compare against.
    val hits = results?.take(CANDIDATE_LIMIT)?.sortedByDescending { current != null && it.isSameAs(current) }
    val currentShown = current != null && hits?.any { it.isSameAs(current) } == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = tokens.spacing.sheetPadding)
            .padding(bottom = tokens.spacing.sheetPadding)
            // Header, field, rows and bar share one column, so nothing floats alone
            // at the far edge of a wide sheet.
            .widthIn(max = SHEET_CONTENT_MAX_WIDTH),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_webdav_review_fix),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = folderLine(
                        label = remember(row.folderName) { WebDavReleaseName.describe(row.folderName) },
                        fileCount = pluralStringResource(
                            Res.plurals.settings_webdav_file_count,
                            row.fileCount,
                            row.fileCount,
                        ),
                        withTitle = true,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The grid badges the current match, so say it in words only when that
                // match is not among the results on screen.
                if (current != null && !currentShown) {
                    Text(
                        text = listOfNotNull(
                            stringResource(Res.string.settings_webdav_fix_current) + ": " + current.displayName,
                            placedMatchTag(current),
                        ).joinToString(" \u00b7 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        WebDavLibraryRepository.rematch(row.folderKey)
                        onDone()
                    }
                },
            ) { Text(stringResource(Res.string.settings_webdav_review_rematch)) }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(Res.string.settings_webdav_review_search),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { scope.launch { runSearch() } }),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                hits == null -> Text(
                    text = stringResource(Res.string.settings_webdav_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )

                hits.isEmpty() -> Text(
                    text = stringResource(Res.string.settings_webdav_no_candidates),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )

                else -> SettingsRowGroup(onSheet = true) {
                    hits.forEachIndexed { index, hit ->
                        if (index > 0) SettingsRowSeparator(inset = CANDIDATE_SEPARATOR_INSET)
                        CandidateRow(
                            hit = hit,
                            selected = selected == hit,
                            isCurrent = current != null && hit.isSameAs(current),
                            fileCount = row.fileCount,
                            onClick = {
                                selected = hit
                                failed = false
                                // A season entry ("2nd Season") carries its season through the
                                // mapper. The number on screen stays until it answers, and stays
                                // for good when the mapper has none for this entry.
                                seasonLookup?.cancel()
                                seasonLookup = scope.launch {
                                    val mapped = WebDavLibraryRepository.mapperSeason(hit)
                                    if (isActive && mapped != null) season = mapped
                                }
                            },
                        )
                    }
                }
            }
            if (failed) {
                Text(
                    text = stringResource(Res.string.settings_webdav_fix_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                )
            }
        }

        // The decision stays put while the candidates scroll behind it.
        HorizontalDivider(color = tokens.colors.borderSubtle)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val picked = selected
            if (picked != null && !picked.isMovie) {
                Text(
                    text = stringResource(Res.string.settings_webdav_review_season),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                // A number set by hand wins over a lookup still on its way.
                SettingsStepper(
                    value = season.toString(),
                    canDecrement = season > 0,
                    canIncrement = season < MAX_SEASON,
                    onDecrement = {
                        seasonLookup?.cancel()
                        season = (season - 1).coerceAtLeast(0)
                    },
                    onIncrement = {
                        seasonLookup?.cancel()
                        season = (season + 1).coerceAtMost(MAX_SEASON)
                    },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            SettingsCapsuleButton(
                text = stringResource(Res.string.settings_webdav_review_apply),
                enabled = picked != null && !busy,
                onClick = {
                    val hit = picked ?: return@SettingsCapsuleButton
                    busy = true
                    failed = false
                    scope.launch {
                        seasonLookup?.join()
                        val result = WebDavLibraryRepository.applyOverride(
                            folderKey = row.folderKey,
                            hit = hit,
                            season = season,
                            treatAsMovie = false,
                        )
                        busy = false
                        if (result.isSuccess) onDone() else failed = true
                    }
                },
            )
        }
    }
}

/** The same entry the stored match came from, as far as the titles can tell. */
private fun AnimeSearchHit.isSameAs(match: WebDavMatch): Boolean =
    allTitles.any { it.equals(match.title, ignoreCase = true) }

@Composable
private fun CandidateRow(
    hit: AnimeSearchHit,
    selected: Boolean,
    isCurrent: Boolean,
    fileCount: Int,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val year = hit.startDateEpochSeconds?.let { GMTDate(it * 1000).year.toString() }
    val episodes = hit.episodeCount?.takeIf { it > 0 }
    // The count that decides most matches: a 50-file folder belongs to a 50-episode show.
    val fits = episodes != null && episodes == fileCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The pick tints inside the group rather than becoming a card of its own.
            .background(if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.selected) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsPosterThumb(
            url = hit.poster,
            placeholderIcon = Icons.Rounded.Movie,
            width = 42.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    // Wraps rather than truncates: on a phone these titles differ only at the end.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    SettingsTag(
                        text = stringResource(Res.string.settings_webdav_fix_current_badge),
                        color = tokens.colors.accent,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val head = listOfNotNull(animeTypeLabel(hit.subtype), year).joinToString(" \u00b7 ")
                if (head.isNotEmpty()) {
                    Text(
                        text = if (episodes == null) head else head + " \u00b7",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                    )
                }
                if (episodes != null) {
                    Text(
                        text = pluralStringResource(Res.plurals.settings_webdav_episode_count, episodes, episodes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fits) tokens.colors.success else tokens.colors.textMuted,
                        fontWeight = if (fits) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Kitsu and MyAnimeList type names; values not listed pass through as sent. */
@Composable
private fun animeTypeLabel(subtype: String?): String? = when (subtype?.lowercase()) {
    null, "" -> null
    "tv" -> stringResource(Res.string.settings_webdav_anime_type_tv)
    "movie" -> stringResource(Res.string.media_movie)
    "special" -> stringResource(Res.string.settings_webdav_anime_type_special)
    "tv special" -> stringResource(Res.string.settings_webdav_anime_type_tv_special)
    "music" -> stringResource(Res.string.settings_webdav_anime_type_music)
    else -> subtype
}
