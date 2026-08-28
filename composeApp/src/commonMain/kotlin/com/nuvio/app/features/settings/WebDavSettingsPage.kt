package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.webdav.ScanPhase
import com.nuvio.app.features.webdav.WebDavConnectionResult
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import com.nuvio.app.features.webdav.WebDavProvider
import com.nuvio.app.features.webdav.WebDavSource
import com.nuvio.app.features.webdav.WebDavUiState
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.webDavSettingsContent(
    isTablet: Boolean,
    state: WebDavUiState,
    onReviewClick: (String) -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_sources),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (state.sources.isEmpty()) {
                    WebDavMessageRow(
                        text = stringResource(Res.string.settings_webdav_empty),
                        isTablet = isTablet,
                    )
                } else {
                    state.sources.forEachIndexed { index, source ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        WebDavSourceCard(
                            isTablet = isTablet,
                            source = source,
                            state = state,
                            onReviewClick = { onReviewClick(source.id) },
                        )
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_webdav_section_add),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                AddWebDavSourceForm(isTablet = isTablet)
            }
        }
    }
}

/** One source: what it holds, what you can do with it, and how far back it scans. */
@Composable
private fun ColumnScope.WebDavSourceCard(
    isTablet: Boolean,
    source: WebDavSource,
    state: WebDavUiState,
    onReviewClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = state.progress[source.id]
    val running = progress?.isRunning == true
    val folders = state.folderCounts[source.id] ?: 0
    val files = state.fileCounts[source.id] ?: 0
    val matched = state.matchedCounts[source.id] ?: 0

    val status = when (progress?.phase) {
        ScanPhase.Listing -> stringResource(Res.string.settings_webdav_status_listing)
        ScanPhase.Folders -> stringResource(
            Res.string.settings_webdav_status_folders,
            progress.foldersDone,
            progress.foldersPlanned,
            progress.filesFound,
        )

        ScanPhase.Matching -> stringResource(
            Res.string.settings_webdav_status_matching,
            progress.foldersPlanned,
        )

        ScanPhase.Failed -> stringResource(
            Res.string.settings_webdav_status_failed,
            progress.errorMessage.orEmpty(),
        )

        else -> if (source.lastScanAt == null && folders == 0) {
            stringResource(Res.string.settings_webdav_never_scanned)
        } else {
            stringResource(Res.string.settings_webdav_status_idle, folders, files, matched)
        }
    }

    Column(
        modifier = Modifier.padding(
            horizontal = rowHorizontalPadding(isTablet),
            vertical = rowVerticalPadding(isTablet),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_webdav_window_size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Plain tappable glyphs: a TextButton would reserve a 48dp touch target
            // each and leave the row surrounded by empty space.
            StepperGlyph(
                symbol = "−",
                onClick = {
                    WebDavLibraryRepository.setWindowSize(source.id, source.windowSize - 10)
                },
            )
            Text(
                text = source.windowSize.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            StepperGlyph(
                symbol = "+",
                onClick = {
                    WebDavLibraryRepository.setWindowSize(source.id, source.windowSize + 10)
                },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { WebDavLibraryRepository.scan(source.id) },
                enabled = !running,
            ) {
                Text(
                    stringResource(
                        if (running) {
                            Res.string.settings_webdav_scanning
                        } else {
                            Res.string.settings_webdav_scan
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = { WebDavLibraryRepository.rebuild(source.id) },
                enabled = !running,
            ) { Text(stringResource(Res.string.settings_webdav_rebuild)) }
            OutlinedButton(onClick = onReviewClick) {
                Text(stringResource(Res.string.settings_webdav_review))
            }
            TextButton(
                onClick = { scope.launch { WebDavLibraryRepository.removeSource(source.id) } },
            ) { Text(stringResource(Res.string.settings_webdav_remove)) }
        }
    }
}

@Composable
private fun StepperGlyph(symbol: String, onClick: () -> Unit) {
    Text(
        text = symbol,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun WebDavMessageRow(text: String, isTablet: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = rowHorizontalPadding(isTablet),
            vertical = rowVerticalPadding(isTablet),
        ),
    )
}

private fun rowHorizontalPadding(isTablet: Boolean): Dp = if (isTablet) 20.dp else 16.dp

private fun rowVerticalPadding(isTablet: Boolean): Dp = if (isTablet) 16.dp else 14.dp

/**
 * Credentials, URLs and paths are case-sensitive. Left to its defaults iOS
 * capitalises the first letter and autocorrects, which silently turns a valid
 * username into a rejected one.
 */
private val verbatimKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)

@Composable
private fun AddWebDavSourceForm(isTablet: Boolean) {
    val scope = rememberCoroutineScope()

    var provider by rememberSaveable { mutableStateOf(WebDavProvider.RealDebrid.id) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(WebDavProvider.RealDebrid.defaultBaseUrl) }
    var rootPath by rememberSaveable { mutableStateOf(WebDavProvider.RealDebrid.defaultRootPath) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val selectedProvider = WebDavProvider.fromId(provider)

    fun applyProvider(next: WebDavProvider) {
        provider = next.id
        baseUrl = next.defaultBaseUrl
        rootPath = next.defaultRootPath
        username = next.fixedUsername.orEmpty()
        message = null
    }

    Column(
        modifier = Modifier.padding(
            horizontal = rowHorizontalPadding(isTablet),
            vertical = rowVerticalPadding(isTablet),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_webdav_provider),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebDavProvider.entries.forEach { option ->
                if (option == selectedProvider) {
                    Button(onClick = { applyProvider(option) }) { Text(option.displayName) }
                } else {
                    OutlinedButton(onClick = { applyProvider(option) }) { Text(option.displayName) }
                }
            }
        }

        // Fields read as a form rather than as full-bleed bars on a tablet.
        val fieldModifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(Res.string.settings_webdav_display_name)) },
            singleLine = true,
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(Res.string.settings_webdav_server_url)) },
            singleLine = true,
            keyboardOptions = verbatimKeyboard,
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = rootPath,
            onValueChange = { rootPath = it },
            label = { Text(stringResource(Res.string.settings_webdav_root_path)) },
            singleLine = true,
            keyboardOptions = verbatimKeyboard,
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.settings_webdav_username)) },
            singleLine = true,
            enabled = selectedProvider.fixedUsername == null,
            keyboardOptions = verbatimKeyboard,
            modifier = fieldModifier,
        )
        SettingsSecretTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(
                if (selectedProvider == WebDavProvider.Torbox) {
                    Res.string.settings_webdav_api_key
                } else {
                    Res.string.settings_webdav_password
                },
            ),
            modifier = fieldModifier,
        )

        if (selectedProvider == WebDavProvider.Torbox) {
            Text(
                text = stringResource(Res.string.settings_webdav_torbox_key_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val storedKey = DebridSettingsRepository.snapshot().torboxApiKey
            if (storedKey.isNotBlank()) {
                TextButton(onClick = { password = storedKey }) {
                    Text(stringResource(Res.string.settings_webdav_use_stored_key))
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = WebDavLibraryRepository.testConnection(
                            baseUrl = baseUrl,
                            username = selectedProvider.fixedUsername ?: username,
                            password = password,
                            rootPath = rootPath,
                        )
                        message = when (result) {
                            is WebDavConnectionResult.Success ->
                                "Connected. Found ${result.entryCount} entries."

                            is WebDavConnectionResult.Failure -> result.message
                        }
                        busy = false
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) Res.string.settings_webdav_testing else Res.string.settings_webdav_test,
                    ),
                )
            }
            Button(
                enabled = !busy && baseUrl.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = WebDavLibraryRepository.addSource(
                            provider = selectedProvider,
                            displayName = displayName,
                            baseUrl = baseUrl,
                            username = username,
                            password = password,
                            rootPath = rootPath,
                        )
                        result.fold(
                            onSuccess = {
                                displayName = ""
                                password = ""
                                message = null
                            },
                            onFailure = { error ->
                                message = error.message ?: "Could not add the source."
                            },
                        )
                        busy = false
                    }
                },
            ) { Text(stringResource(Res.string.settings_webdav_add)) }
        }
    }
}
