package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_clear
import nuvio.composeapp.generated.resources.settings_hide_secret
import nuvio.composeapp.generated.resources.settings_show_secret

// Building blocks for the WebDAV library and imported subtitle pages. They follow
// the paddings and colours of SettingsNavigationRow so the rows sit in the same
// groups without looking foreign.

internal fun settingsRowHorizontalPadding(isTablet: Boolean): Dp = if (isTablet) 20.dp else 16.dp

internal fun settingsRowVerticalPadding(isTablet: Boolean): Dp = if (isTablet) 16.dp else 14.dp

/** Card shape of a settings group, for surfaces that stand alone in the list. */
@Composable
internal fun settingsCardShape(isTablet: Boolean) =
    if (isTablet) RoundedCornerShape(NuvioTokens.Radius.xl) else MaterialTheme.nuvio.shapes.compactCard

/** A card on its own, for list entries too many to share one group. */
@Composable
internal fun SettingsItemCard(
    isTablet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = settingsCardShape(isTablet)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        color = tokens.colors.surface,
        shape = shape,
        border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
        content = content,
    )
}

@Composable
internal fun SettingsMessageRow(
    text: String,
    isTablet: Boolean,
    color: Color = MaterialTheme.nuvio.colors.textMuted,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = settingsRowHorizontalPadding(isTablet),
                vertical = settingsRowVerticalPadding(isTablet),
            ),
    )
}

/** A read-only fact: label on the left, value on the right. */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    isTablet: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = settingsRowHorizontalPadding(isTablet),
                vertical = settingsRowVerticalPadding(isTablet),
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row whose only job is one irreversible action, drawn in the danger colour. */
@Composable
internal fun SettingsDestructiveRow(
    title: String,
    icon: ImageVector,
    isTablet: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = settingsRowHorizontalPadding(isTablet),
                vertical = settingsRowVerticalPadding(isTablet),
            )
            .alpha(if (enabled) 1f else tokens.opacity.medium),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.colors.danger,
            modifier = Modifier.size(tokens.icons.md),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.danger,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A number changed one step at a time. It always shows the value in effect: there
 * is no empty or automatic state to fall back to.
 */
@Composable
internal fun SettingsStepperRow(
    title: String,
    description: String?,
    value: String,
    isTablet: Boolean,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = settingsRowHorizontalPadding(isTablet),
                vertical = settingsRowVerticalPadding(isTablet),
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
        SettingsStepper(
            value = value,
            canDecrement = canDecrement,
            canIncrement = canIncrement,
            onDecrement = onDecrement,
            onIncrement = onIncrement,
        )
    }
}

@Composable
internal fun SettingsStepper(
    value: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(tokens.colors.surfaceCard),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDecrement, enabled = canDecrement) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = null,
                tint = if (canDecrement) tokens.colors.textPrimary else tokens.colors.textDisabled,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        IconButton(onClick = onIncrement, enabled = canIncrement) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = if (canIncrement) tokens.colors.textPrimary else tokens.colors.textDisabled,
            )
        }
    }
}

@Composable
internal fun SettingsFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, maxLines = 1)
                Text(
                    text = count.toString(),
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = tokens.colors.borderDefault,
            selectedBorderColor = tokens.colors.accent.copy(alpha = 0.7f),
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = tokens.colors.surface,
            labelColor = tokens.colors.textSecondary,
            selectedContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.selected),
            selectedLabelColor = tokens.colors.textPrimary,
        ),
    )
}

/** Poster at 2:3, with an icon standing in when there is no artwork. */
@Composable
internal fun SettingsPosterThumb(
    url: String?,
    placeholderIcon: ImageVector,
    modifier: Modifier = Modifier,
    /** Null lets the caller size it, e.g. a tile that fills its column. */
    width: Dp? = null,
    dimmed: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surfaceCard)
            .alpha(if (dimmed) tokens.opacity.medium else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.fillMaxWidth(0.42f).aspectRatio(1f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Short label on a tinted pill: a season, a state, a type. */
@Composable
internal fun SettingsTag(
    text: String,
    color: Color = MaterialTheme.nuvio.colors.textMuted,
) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * One choice among a few, drawn as a pill holding pills: the app already uses this
 * shape for chips and tabs, and the filled segment makes the active one plain.
 * A null selection (the list is showing search results instead) dims the whole control.
 */
@Composable
internal fun SettingsSegmentedTabs(
    labels: List<String>,
    /** Null where the choice carries no count, e.g. a provider picker. */
    counts: List<Int>?,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .clip(tokens.shapes.chip)
            .background(tokens.colors.surfaceCard)
            .padding(4.dp)
            .alpha(if (selectedIndex == null) tokens.opacity.medium else 1f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Row(
                modifier = Modifier
                    .clip(tokens.shapes.chip)
                    .background(
                        if (active) tokens.colors.accent.copy(alpha = tokens.opacity.selected) else Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) tokens.colors.textPrimary else tokens.colors.textSecondary,
                    maxLines = 1,
                )
                counts?.getOrNull(index)?.let { count ->
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) tokens.colors.textPrimary else tokens.colors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A grouped list the way iOS draws one: a single rounded container, hairline
 * separators between rows, no border and no card per row.
 */
@Composable
internal fun SettingsRowGroup(
    modifier: Modifier = Modifier,
    /**
     * A cell sits one step above whatever holds it: the page's own colour on a page,
     * a step lighter inside a sheet, which is already painted in it.
     */
    onSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.xxl),
        color = if (onSheet) tokens.colors.surfaceCard else tokens.colors.surface,
    ) {
        Column(content = content)
    }
}

/** Separator between grouped rows, inset past whatever leads the row. */
@Composable
internal fun SettingsRowSeparator(inset: Dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = MaterialTheme.nuvio.borders.hairline,
        color = MaterialTheme.nuvio.colors.borderSubtle,
    )
}

/** Filled capsule search field, the shape iOS 26 uses for search. */
@Composable
internal fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = tokens.shapes.chip,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.action_clear),
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** The app's filled button in the capsule shape iOS 26 gives its controls. */
@Composable
internal fun SettingsCapsuleButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = tokens.shapes.chip,
        contentPadding = PaddingValues(horizontal = 26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.accent,
            contentColor = tokens.colors.onAccent,
            disabledContainerColor = tokens.colors.accent.copy(alpha = tokens.opacity.disabled),
            disabledContentColor = tokens.colors.onAccent.copy(alpha = tokens.opacity.disabled),
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Every capsule row in a form stands the same height, buttons included. */
private val CAPSULE_ROW_HEIGHT = 46.dp

/** A form field in the capsule shape the search field uses. */
@Composable
internal fun SettingsFormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** An in-field shortcut, drawn like Change on [SettingsFilledValueRow]. */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    var revealed by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = tokens.shapes.chip,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = CAPSULE_ROW_HEIGHT)
                .padding(start = 16.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = if (secret && !revealed) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (actionLabel != null) {
                CapsuleRowAction(label = actionLabel, onClick = onAction)
            }
            if (secret) {
                IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (revealed) Res.string.settings_hide_secret else Res.string.settings_show_secret,
                        ),
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** A short action at the end of a capsule row, in the row's own text colour. */
@Composable
private fun CapsuleRowAction(label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = tokens.colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** A fact the form filled in for you, with a way to take it over. */
@Composable
internal fun SettingsFilledValueRow(
    label: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = tokens.shapes.chip,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = CAPSULE_ROW_HEIGHT)
                .padding(start = 16.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CapsuleRowAction(label = actionLabel, onClick = onAction)
        }
    }
}

/** The quiet capsule beside a filled one, for the action you take less often. */
@Composable
internal fun SettingsGhostCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = tokens.shapes.chip,
        contentPadding = PaddingValues(horizontal = 22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.surfaceCard,
            contentColor = tokens.colors.textPrimary,
            disabledContainerColor = tokens.colors.surfaceCard.copy(alpha = tokens.opacity.disabled),
            disabledContentColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.disabled),
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
