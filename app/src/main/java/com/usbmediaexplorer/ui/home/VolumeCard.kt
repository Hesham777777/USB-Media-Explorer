package com.usbmediaexplorer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.volume.FileSystemProbe
import com.usbmediaexplorer.data.volume.GrantKind
import com.usbmediaexplorer.data.volume.VolumeInfo
import com.usbmediaexplorer.data.volume.VolumeKind
import com.usbmediaexplorer.data.volume.VolumeState
import com.usbmediaexplorer.ui.common.InfoRow
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.common.PressableSurface
import com.usbmediaexplorer.ui.common.SheetGroupLabel
import com.usbmediaexplorer.ui.common.SheetHeader
import com.usbmediaexplorer.ui.common.StatusDot
import com.usbmediaexplorer.ui.common.TagChip
import com.usbmediaexplorer.ui.common.UsageRing
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.volumeColor
import com.usbmediaexplorer.ui.common.volumeIcon
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.ui.theme.AppTheme
import com.usbmediaexplorer.ui.theme.Palette
import com.usbmediaexplorer.util.Formatters

/**
 * Compact storage card (spec §1).
 *
 * One card answers four questions without a single button taking a line of its own: what is this
 * drive, how full is it, is it reachable, and what can I do with it. Tapping the card opens it —
 * the old full-width "Open" button is gone, and everything secondary (details, rescan, remove the
 * grant) lives in the overflow menu.
 *
 * Two cards fit in a row on a phone; a single volume gets the [wide] layout so the space is used
 * for information instead of being left empty.
 */
@Composable
fun StorageCard(
    volume: VolumeInfo,
    onOpen: () -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    onDetails: () -> Unit = {},
    onRelease: () -> Unit = {},
    onRescan: () -> Unit = {},
) {
    val ready = volume.state == VolumeState.READY
    val mounted = volume.state != VolumeState.UNMOUNTED
    val accent = volumeColor(volume.kind)
    val extended = AppTheme.extended
    var menuOpen by remember { mutableStateOf(false) }
    val settings = LocalSettings.current

    val stateColor = when (volume.state) {
        VolumeState.READY -> extended.success
        VolumeState.NEEDS_PERMISSION -> extended.warning
        VolumeState.UNMOUNTED -> MaterialTheme.colorScheme.outline
    }

    PressableSurface(
        onClick = { if (ready) onOpen() else if (mounted) onGrant() },
        onLongClick = { onDetails() },
        enabled = mounted,
        modifier = modifier,
        shape = RoundedCornerShape(AppRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(AppSpacing.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = if (AppTheme.isDark) 0.18f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (volume.state == VolumeState.NEEDS_PERMISSION) {
                            Icons.Outlined.Lock
                        } else {
                            volumeIcon(volume.kind)
                        },
                        contentDescription = stringResource(R.string.cd_volume_icon),
                        tint = if (volume.state == VolumeState.NEEDS_PERMISSION) stateColor else accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = volume.name.bidiName(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = cardSubtitle(volume, settings.showTechnicalPaths)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle.bidiLtr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_details)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDetails()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_refresh)) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRescan()
                            },
                        )
                        if (ready && volume.grantKind == GrantKind.SAF_TREE && volume.isRemovable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.volume_remove_grant)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onRelease()
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.md))

            if (wide) {
                WideSpace(volume = volume, stateColor = stateColor, accent = accent)
            } else {
                CompactSpace(volume = volume, stateColor = stateColor, mounted = mounted)
            }

            // USB gets an explicit indicator: "is my stick still plugged in?" must be answerable
            // without opening anything.
            if (volume.kind == VolumeKind.USB) {
                Spacer(Modifier.height(AppSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(
                        text = stringResource(R.string.volume_usb_tag),
                        color = accent,
                        container = accent.copy(alpha = if (AppTheme.isDark) 0.18f else 0.12f),
                        icon = Icons.Outlined.Usb,
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusDot(color = stateColor)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(
                            when (volume.state) {
                                VolumeState.READY -> R.string.volume_connected
                                VolumeState.NEEDS_PERMISSION -> R.string.volume_needs_permission_short
                                VolumeState.UNMOUNTED -> R.string.volume_not_connected
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (volume.state != VolumeState.READY) {
                Spacer(Modifier.height(AppSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = stateColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (volume.state == VolumeState.NEEDS_PERMISSION) {
                                R.string.volume_needs_permission_short
                            } else {
                                R.string.volume_unmounted
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Ring + numbers, stacked: the layout used when two cards share a row. */
@Composable
private fun CompactSpace(volume: VolumeInfo, stateColor: Color, mounted: Boolean) {
    val total = volume.totalBytes ?: 0L
    val free = volume.freeBytes ?: 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mounted && total > 0) {
            // Two cards share a row on a phone, so the content column is barely 88dp wide:
            // the ring is small and each line carries one number that actually fits.
            UsageRing(
                fraction = volume.progress,
                size = 40.dp,
                stroke = 4.dp,
                centerLabel = "${Formatters.percent(total - free, total)}%",
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.volume_free_space, Formatters.size(free)).bidiLtr(),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = stringResource(R.string.volume_capacity_short, Formatters.size(total)).bidiLtr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { volume.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(AppRadius.pill)),
                    color = stateColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
        } else {
            Text(
                text = if (mounted) {
                    stringResource(R.string.volume_space_unknown)
                } else {
                    stringResource(R.string.volume_unmounted)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ring + a full breakdown, side by side: the layout used when one volume owns the row. */
@Composable
private fun WideSpace(volume: VolumeInfo, stateColor: Color, accent: Color) {
    val total = volume.totalBytes ?: 0L
    val free = volume.freeBytes ?: 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (total > 0) {
            UsageRing(
                fraction = volume.progress,
                size = 62.dp,
                stroke = 7.dp,
                centerLabel = "${Formatters.percent(total - free, total)}%",
            )
            Spacer(Modifier.width(AppSpacing.lg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SpaceLine(stringResource(R.string.label_size), Formatters.size(total), accent)
                SpaceLine(stringResource(R.string.volume_used_label), Formatters.size(total - free), stateColor)
                SpaceLine(stringResource(R.string.volume_free_label), Formatters.size(free), stateColor)
                LinearProgressIndicator(
                    progress = { volume.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(AppRadius.pill)),
                    color = stateColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.volume_space_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpaceLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.bidiLtr(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** `FAT32 • 1234-ABCD` — technical identity, kept to one line and never mirrored by RTL. */
@Composable
private fun cardSubtitle(volume: VolumeInfo, technical: Boolean = false): String {
    val parts = ArrayList<String>(3)
    if (technical) FileSystemProbe.labelFor(volume.fileSystem)?.let { parts += it }
    volume.deviceLabel?.let { parts += it }
    if (parts.isEmpty()) {
        volume.description?.let { parts += it }
    }
    return parts.joinToString(" • ")
}

/** Everything about one volume, in a sheet instead of on the card. */
@Composable
fun VolumeDetailsSheet(volume: VolumeInfo, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings = LocalSettings.current
    val total = volume.totalBytes ?: 0L
    val free = volume.freeBytes ?: 0L
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetHeader(
            icon = volumeIcon(volume.kind),
            title = volume.name,
            subtitle = cardSubtitle(volume, settings.showTechnicalPaths)
                .ifEmpty { stringResource(R.string.volume_unknown) },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
        ) {
            if (total > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageRing(
                        fraction = volume.progress,
                        size = 56.dp,
                        stroke = 6.dp,
                        centerLabel = "${Formatters.percent(total - free, total)}%",
                    )
                    Spacer(Modifier.width(AppSpacing.lg))
                    Column(Modifier.weight(1f)) {
                        InfoRow(stringResource(R.string.label_size), Formatters.size(total), ltrValue = true)
                        InfoRow(
                            stringResource(R.string.volume_used_label),
                            Formatters.size(total - free),
                            ltrValue = true,
                        )
                        InfoRow(
                            stringResource(R.string.volume_free_label),
                            Formatters.size(free),
                            valueColor = usageColorOf(volume.progress),
                            ltrValue = true,
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.sm))
            }

            SheetGroupLabel(stringResource(R.string.volume_details_group))
            InfoRow(
                stringResource(R.string.label_state),
                stringResource(
                    when (volume.state) {
                        VolumeState.READY -> R.string.volume_state_ready
                        VolumeState.NEEDS_PERMISSION -> R.string.volume_needs_permission
                        VolumeState.UNMOUNTED -> R.string.volume_unmounted
                    },
                ),
            )
            InfoRow(
                stringResource(R.string.label_type),
                stringResource(
                    when (volume.kind) {
                        VolumeKind.INTERNAL -> R.string.volume_internal
                        VolumeKind.SD_CARD -> R.string.volume_sd_card
                        VolumeKind.USB -> R.string.volume_usb
                        VolumeKind.EXTERNAL -> R.string.volume_unknown
                    },
                ),
            )
            // Mount points, file systems and UUIDs are advanced identity: hidden unless the
            // user opts in — the ordinary reader needs the name, the state and the numbers.
            if (settings.showTechnicalPaths) {
                volume.fileSystem?.let {
                    InfoRow(
                        stringResource(R.string.label_filesystem),
                        FileSystemProbe.labelFor(it) ?: it,
                        ltrValue = true,
                    )
                }
                volume.description?.takeIf { it.isNotBlank() }?.let {
                    InfoRow(stringResource(R.string.volume_mount_label), it, ltrValue = true)
                }
                volume.uuid?.takeIf { it.isNotBlank() }?.let {
                    InfoRow(stringResource(R.string.volume_id_label), it, ltrValue = true)
                }
            }
            InfoRow(
                stringResource(R.string.volume_access_label),
                stringResource(
                    if (volume.grantKind == GrantKind.SAF_TREE) {
                        R.string.volume_access_saf
                    } else {
                        R.string.volume_access_runtime
                    },
                ),
            )
            InfoRow(
                stringResource(R.string.volume_removable_label),
                stringResource(if (volume.isRemovable) R.string.answer_yes else R.string.answer_no),
            )
        }
    }
}

/** The usage colour for the current theme, without threading a colour through every call site. */
@Composable
private fun usageColorOf(fraction: Float): Color = Palette.usageColor(fraction, AppTheme.isDark)
