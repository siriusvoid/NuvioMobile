package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.webdav.WebDavHttpException
import com.nuvio.app.features.webdav.WebDavLibraryRepository
import com.nuvio.app.features.webdav.WebDavProvider
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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

/** Why a source was refused: the plain reason first, the evidence under it. */
private data class FormError(val headline: String, val detail: String?)

private suspend fun Throwable.toFormError(): FormError = when (this) {
    is WebDavHttpException -> FormError(
        headline = explanation.replaceFirstChar { it.titlecase() },
        detail = listOfNotNull("HTTP $status", serverMessage).joinToString(" · "),
    )
    // No status means no answer at all; the platform's own words are the evidence.
    else -> FormError(
        headline = getString(Res.string.settings_webdav_unreachable),
        detail = message?.takeIf { it.isNotBlank() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavAddSourceSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // Outlives the sheet's own composition, which on iOS is torn down as it closes.
    val scope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // The form is five rows; a full-height sheet leaves most of itself empty.
        fullHeight = false,
    ) {
        val close = { scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }; Unit }
        AddSourceForm(onAdded = close, onCancel = close)
    }
}

@Composable
private fun AddSourceForm(onAdded: () -> Unit, onCancel: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(WebDavProvider.RealDebrid) }
    var displayName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(provider.defaultBaseUrl) }
    var rootPath by remember { mutableStateOf(provider.defaultRootPath) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // A known provider fills in its own server, so those fields stay folded away
    // until asked for.
    var editServer by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }

    val providers = remember { WebDavProvider.entries.toList() }
    val providerLabel = provider.label()
    val isTorbox = provider == WebDavProvider.Torbox
    val showServerFields = provider == WebDavProvider.Custom || editServer

    fun selectProvider(next: WebDavProvider) {
        if (next == provider) return
        provider = next
        baseUrl = next.defaultBaseUrl
        rootPath = next.defaultRootPath
        username = next.fixedUsername.orEmpty()
        editServer = false
        message = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = tokens.spacing.sheetPadding)
            .padding(bottom = tokens.spacing.sheetPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(Res.string.settings_webdav_section_add),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.settings_webdav_add_sheet_description),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        SettingsSegmentedTabs(
            labels = providers.map { it.label() },
            counts = null,
            selectedIndex = providers.indexOf(provider),
            onSelect = { index -> selectProvider(providers[index]) },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        if (showServerFields) {
            SettingsFormField(
                value = baseUrl,
                onValueChange = { baseUrl = it; message = null },
                placeholder = stringResource(Res.string.settings_webdav_server_url),
                keyboardOptions = verbatimKeyboard.copy(keyboardType = KeyboardType.Uri),
            )
            SettingsFormField(
                value = rootPath,
                onValueChange = { rootPath = it; message = null },
                placeholder = stringResource(Res.string.settings_webdav_root_path),
                keyboardOptions = verbatimKeyboard,
            )
        } else {
            SettingsFilledValueRow(
                label = stringResource(Res.string.settings_webdav_server_url),
                value = listOf(baseUrl.substringAfter("://"), rootPath)
                    .filter { it.isNotBlank() }
                    .joinToString("/"),
                actionLabel = stringResource(Res.string.settings_webdav_change),
                onAction = { editServer = true },
            )
        }

        if (provider.fixedUsername == null) {
            SettingsFormField(
                value = username,
                onValueChange = { username = it; message = null },
                placeholder = stringResource(Res.string.settings_webdav_username),
                keyboardOptions = verbatimKeyboard,
            )
        }

        // TorBox's WebDAV takes the same API key the debrid settings already hold,
        // so the field offers it until it holds exactly that.
        val storedKey = remember { DebridSettingsRepository.snapshot().torboxApiKey }
        val offerStoredKey = isTorbox && storedKey.isNotBlank() && password != storedKey
        SettingsFormField(
            value = password,
            onValueChange = { password = it; message = null },
            placeholder = stringResource(
                if (isTorbox) Res.string.settings_webdav_api_key else Res.string.settings_webdav_password,
            ),
            secret = true,
            actionLabel = if (offerStoredKey) stringResource(Res.string.settings_webdav_use_stored_key) else null,
            onAction = { password = storedKey; message = null },
        )

        SettingsFormField(
            value = displayName,
            onValueChange = { displayName = it },
            placeholder = stringResource(Res.string.settings_webdav_display_name),
        )

        message?.let { FormErrorCard(it) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsGhostCapsuleButton(
                text = stringResource(Res.string.action_cancel),
                enabled = !busy,
                onClick = onCancel,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Adding runs the same check a test would, and says why it refused.
            SettingsCapsuleButton(
                text = stringResource(
                    if (busy) Res.string.settings_webdav_testing else Res.string.settings_webdav_add,
                ),
                enabled = !busy && baseUrl.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = WebDavLibraryRepository.addSource(
                            provider = provider,
                            displayName = displayName.ifBlank { providerLabel },
                            baseUrl = baseUrl,
                            username = username,
                            password = password,
                            rootPath = rootPath,
                        )
                        busy = false
                        result.fold(
                            onSuccess = { onAdded() },
                            onFailure = { error -> message = error.toFormError() },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FormErrorCard(error: FormError) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = tokens.shapes.button,
        color = tokens.colors.danger.copy(alpha = tokens.opacity.selected),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = tokens.colors.danger,
                modifier = Modifier.padding(top = 1.dp).size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = error.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                error.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
