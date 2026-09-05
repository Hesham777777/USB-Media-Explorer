package com.usbmediaexplorer.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.ui.theme.AppTheme
import com.usbmediaexplorer.ui.theme.AppTouch
import com.usbmediaexplorer.ui.theme.Palette

/* ---------------------------------------------------------------------------
 * Bidirectional text (spec §25)
 *
 * Arabic is the primary language, so the whole tree lays out right-to-left — but file names,
 * paths, sizes and extensions must never be reordered by the bidi algorithm. Isolating them keeps
 * an English name reading left-to-right inside an Arabic sentence, an Arabic name reading
 * right-to-left, and a path like /storage/emulated/0/Movies from being mirrored into
 * Movies/0/emulated/storage.
 * ------------------------------------------------------------------------- */

/** First-strong isolate: the text keeps its own direction (Arabic names stay RTL). */
fun String.bidiName(): String = "\u2068$this\u2069"

/** Left-to-right isolate: for paths, extensions, versions and any technical value. */
fun String.bidiLtr(): String = "\u2066$this\u2069"

/* ---------------------------------------------------------------------------
 * Surfaces
 * ------------------------------------------------------------------------- */

/**
 * The one clickable surface of the app: ripple, a 2 % press scale and an animated container colour
 * for the selected state. Every card, row and tile uses this, so "pressed" and "selected" feel the
 * same everywhere.
 */
@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(AppRadius.md),
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    selectedColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    border: androidx.compose.foundation.BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "press",
    )
    val container by animateColorAsState(
        targetValue = if (selected) selectedColor else color,
        animationSpec = tween(durationMillis = 160),
        label = "container",
    )
    val resolvedBorder = border ?: if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    Surface(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = shape,
        color = container,
        border = resolvedBorder,
    ) {
        Box(
            Modifier
                .clip(shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            content()
        }
    }
}

/* ---------------------------------------------------------------------------
 * Section headers
 * ------------------------------------------------------------------------- */

/**
 * Section header: a label with an optional count and an optional text action on the other side.
 * Compact on purpose — the content, not the header, should own the screen.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.xs, end = AppSpacing.xs, top = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(AppSpacing.sm))
            CountBadge(count)
        }
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = 0.dp),
                modifier = Modifier.height(AppTouch.compact),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Small pill with a number — used by headers and tiles. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppRadius.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/** A one-word chip used for storage kind, connection state, filters. */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    icon: ImageVector? = null,
) {
    Surface(shape = RoundedCornerShape(AppRadius.pill), color = container, modifier = modifier) {
        Row(
            Modifier.padding(start = 8.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

/** Connection/permission dot: green = ready, amber = needs action, grey = unavailable. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/* ---------------------------------------------------------------------------
 * Storage usage ring
 * ------------------------------------------------------------------------- */

/**
 * Circular storage meter. The colour is a function of how full the volume is (comfortable →
 * getting full → full), which is why it never uses the primary colour: a red ring has to mean
 * "almost no space left", not "this is the app's brand".
 */
@Composable
fun UsageRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    stroke: Dp = 5.dp,
    centerLabel: String? = null,
    color: Color = Palette.usageColor(fraction, AppTheme.isDark),
) {
    val progress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550),
        label = "usage",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = this.size.minDimension - stroke.toPx()
            val topLeft = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        if (centerLabel != null) {
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Empty / error / loading states
 * ------------------------------------------------------------------------- */

/**
 * The one state composable: an icon on a soft tinted disc, a title, an explanation and up to two
 * actions. Empty, error and "no access" states are the same component with different colours, so
 * they read as one system instead of three ad-hoc layouts.
 */
@Composable
fun StateBlock(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.xxl, vertical = AppSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(AppSpacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (content != null) {
            Spacer(Modifier.height(AppSpacing.md))
            content()
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            Button(
                onClick = onAction,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(actionLabel)
            }
            if (secondaryLabel != null && onSecondaryAction != null) {
                Spacer(Modifier.height(AppSpacing.sm))
                OutlinedButton(onClick = onSecondaryAction) { Text(secondaryLabel) }
            }
        }
    }
}

/** Standard "cannot reach this storage" block: explains, then offers the grant. */
@Composable
fun AccessStateBlock(
    title: String,
    body: String,
    grantLabel: String,
    onGrant: () -> Unit,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StateBlock(
        icon = Icons.Outlined.ErrorOutline,
        title = title,
        body = body,
        tint = AppTheme.extended.warning,
        container = AppTheme.extended.warningContainer,
        actionLabel = grantLabel,
        onAction = onGrant,
        secondaryLabel = retryLabel,
        onSecondaryAction = onRetry,
        modifier = modifier,
    )
}

/* ---------------------------------------------------------------------------
 * Skeleton loading
 * ------------------------------------------------------------------------- */

/**
 * Shimmer brush shared by every skeleton. One infinite transition per screen, subtle alpha, no
 * moving gradients across the whole window: loading should be visible, never distracting.
 */
@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    val extended = AppTheme.extended
    return Brush.linearGradient(
        colors = listOf(
            extended.skeleton.copy(alpha = alpha),
            extended.skeletonShine.copy(alpha = alpha),
            extended.skeleton.copy(alpha = alpha),
        ),
    )
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(AppRadius.sm)) {
    Box(modifier = modifier.clip(shape).background(shimmerBrush()))
}

/** Placeholder rows shown while a folder is being listed. */
@Composable
fun SkeletonRows(count: Int = 8, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.listGap),
    ) {
        repeat(count) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(52.dp), RoundedCornerShape(AppRadius.sm))
                Spacer(Modifier.width(AppSpacing.md))
                Column(Modifier.weight(1f)) {
                    SkeletonBox(
                        Modifier
                            .fillMaxWidth(if (index % 3 == 0) 0.85f else 0.6f)
                            .height(12.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.4f).height(10.dp))
                }
            }
        }
    }
}

/** Placeholder tiles shown while a grid of thumbnails is being produced. */
@Composable
fun SkeletonTiles(columns: Int, rows: Int = 3, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.gridGap),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.gridGap)) {
                repeat(columns) {
                    Column(Modifier.weight(1f)) {
                        SkeletonBox(
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                            RoundedCornerShape(AppRadius.md),
                        )
                        Spacer(Modifier.height(6.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.7f).height(10.dp))
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Rows and sheets
 * ------------------------------------------------------------------------- */

/** Label + value row used by details sheets, settings and volume info. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    ltrValue: Boolean = false,
) {
    if (value.isBlank()) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = if (ltrValue) value.bidiLtr() else value.bidiName(),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.62f),
        )
    }
}

/** Header of a bottom sheet: the item's icon, its name and its type. */
@Composable
fun SheetHeader(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.xs, bottom = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(AppSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title.bidiName(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle.bidiLtr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Group label inside a sheet ("أساسية", "إدارة", "تنظيم"). */
@Composable
fun SheetGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = AppSpacing.lg,
            end = AppSpacing.lg,
            top = AppSpacing.md,
            bottom = AppSpacing.xs,
        ),
    )
}

/**
 * One action row of a context sheet. Destructive actions are coloured and separated; disabled
 * actions stay visible (greyed) so the user learns what the selection supports.
 */
@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.lg))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

@Composable
fun SheetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/* ---------------------------------------------------------------------------
 * Toolbar pieces
 * ------------------------------------------------------------------------- */

/** Icon + label action used by the dynamic browse toolbar and the selection bar. */
@Composable
fun ToolAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    destructive: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        destructive -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.sm))
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
            .width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A hairline separator between toolbar groups. */
@Composable
fun ToolSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
