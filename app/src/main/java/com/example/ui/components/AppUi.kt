package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppTheme

// ---------------------------------------------------------------------------
// Design system primitives. Styled after modern React Native apps: neutral
// grouped backgrounds, white rounded cards with a soft shadow (hairline border
// in dark mode), large bold titles, filled inputs and pill controls.
// ---------------------------------------------------------------------------

enum class Tone { NEUTRAL, PRIMARY, SUCCESS, WARNING, DANGER, INFO }

data class ToneColors(val content: Color, val container: Color, val onContainer: Color, val onSolid: Color)

@Composable
fun toneColors(tone: Tone): ToneColors {
    val s = AppTheme.status
    val c = MaterialTheme.colorScheme
    return when (tone) {
        Tone.NEUTRAL -> ToneColors(c.onSurfaceVariant, c.surfaceVariant, c.onSurface, c.surface)
        Tone.PRIMARY -> ToneColors(c.primary, c.primaryContainer, c.onPrimaryContainer, c.onPrimary)
        Tone.SUCCESS -> ToneColors(s.success, s.successContainer, s.onSuccessContainer, s.onSuccess)
        Tone.WARNING -> ToneColors(s.warning, s.warningContainer, s.onWarningContainer, s.onWarning)
        Tone.DANGER -> ToneColors(s.danger, s.dangerContainer, s.onDangerContainer, s.onDanger)
        Tone.INFO -> ToneColors(s.info, s.infoContainer, s.onInfoContainer, c.surface)
    }
}

/** RN `Pressable`-style feedback: content shrinks slightly while pressed. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "pressScale"
    )
    return this.scale(scale)
}

/** Clickable with press-scale feedback and a ripple. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val base = modifier
        .then(if (onClick != null) Modifier.pressScale(source, 0.97f) else Modifier)
        .clay(shape, color)
        .then(if (borderColor != null && borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape) else Modifier)
        .clip(shape)
        .then(
            if (onClick != null) Modifier.clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick
            ) else Modifier
        )
    // Like Material's Surface: text and icons inside default to the matching "on" colour,
    // otherwise they fall back to black (invisible on dark cards).
    CompositionLocalProvider(LocalContentColor provides contentColorFor(color).takeOrElse { MaterialTheme.colorScheme.onSurface }) {
        Column(modifier = base.padding(contentPadding), content = content)
    }
}

/** Large-title header like an RN stack screen with `headerLargeTitle`. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelLarge,
                color = if (onTrailingClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (onTrailingClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTrailingClick).padding(4.dp) else Modifier
            )
        }
    }
}

/** Small uppercase caption used to label groups inside cards/forms. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
        color = color,
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// Icons, avatars, badges
// ---------------------------------------------------------------------------

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.PRIMARY,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    val t = toneColors(tone)
    Box(
        modifier = modifier.size(size).clip(shape).background(t.container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = t.content, modifier = Modifier.size(iconSize))
    }
}

private val AvatarTones = listOf(Tone.PRIMARY, Tone.SUCCESS, Tone.WARNING, Tone.INFO, Tone.DANGER)

/** Initials avatar; tone is derived from the name so it stays stable. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val t = toneColors(AvatarTones[(name.hashCode() and Int.MAX_VALUE) % AvatarTones.size])
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(t.container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            color = t.onContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp
        )
    }
}

@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val t = toneColors(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(t.container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = t.onContainer, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = t.onContainer, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

enum class ButtonVariant { PRIMARY, SECONDARY, DANGER, SUCCESS, GHOST }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 52.dp
) {
    val c = MaterialTheme.colorScheme
    val s = AppTheme.status
    val (container, content) = when (variant) {
        ButtonVariant.PRIMARY -> c.primary to c.onPrimary
        ButtonVariant.SECONDARY -> c.surfaceVariant to c.onSurface
        ButtonVariant.DANGER -> s.danger to s.onDanger
        ButtonVariant.SUCCESS -> s.success to s.onSuccess
        ButtonVariant.GHOST -> Color.Transparent to c.primary
    }
    val bg by animateColorAsState(if (enabled) container else c.surfaceVariant, label = "btnBg")
    val fg = if (enabled) content else c.onSurfaceVariant
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(height)
            .pressScale(source)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled && !loading,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Circular icon button with a tinted background (RN "icon pill"). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    size: Dp = 40.dp,
    solid: Boolean = false
) {
    val t = toneColors(tone)
    val bg = if (solid) t.content else t.container
    val fg = if (solid) t.onSolid else if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else t.content
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(source, 0.92f)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

@Composable
fun appFieldColors(): TextFieldColors {
    val c = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surfaceVariant,
        disabledContainerColor = c.surfaceVariant,
        errorContainerColor = c.surface,
        focusedBorderColor = c.primary,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedTextColor = c.onSurface,
        unfocusedTextColor = c.onSurface,
        focusedPlaceholderColor = c.onSurfaceVariant,
        unfocusedPlaceholderColor = c.onSurfaceVariant,
        focusedLeadingIconColor = c.primary,
        unfocusedLeadingIconColor = c.onSurfaceVariant,
        focusedTrailingIconColor = c.onSurfaceVariant,
        unfocusedTrailingIconColor = c.onSurfaceVariant,
        cursorColor = c.primary
    )
}

/** Filled RN-style text input with its label above the box. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supportingText: String? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fieldModifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
            trailingIcon = trailing,
            singleLine = singleLine,
            minLines = minLines,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(14.dp),
            colors = appFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) AppTheme.status.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = appFieldColors().copy(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

// ---------------------------------------------------------------------------
// Selection controls
// ---------------------------------------------------------------------------

/** iOS / RN segmented control. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (AppTheme.status.isDark) c.surface else c.surfaceDim)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(
                if (isSelected) (if (AppTheme.status.isDark) c.surfaceVariant else c.surface) else Color.Transparent,
                label = "segBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(
                        if (isSelected && !AppTheme.status.isDark)
                            Modifier.shadow(2.dp, RoundedCornerShape(11.dp), ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                        else Modifier
                    )
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) c.onSurface else c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Pill chip; selected state is a high-contrast inverted fill. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    count: Int? = null
) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.onSurface else c.surface, label = "chipBg")
    val fg = if (selected) c.surface else c.onSurface
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(38.dp)
            .pressScale(source, 0.95f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, color = fg, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) c.surface.copy(alpha = 0.75f) else c.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** Settings-style row: icon, title, subtitle and trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.PRIMARY,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IconBadge(icon, tone = tone, size = 36.dp, iconSize = 18.dp, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(icon, tone = tone, size = 64.dp, iconSize = 30.dp, shape = CircleShape)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            AppButton(actionText, onClick = onAction, variant = ButtonVariant.SECONDARY, height = 44.dp)
        }
    }
}

/** Tinted banner for inline status messages. */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val t = toneColors(tone)
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.container).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = t.onContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.onContainer, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Horizontal two-value summary line, e.g. "Subtotal ...... GH₵ 120.00". */
@Composable
fun SummaryLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
            color = valueColor
        )
    }
}
