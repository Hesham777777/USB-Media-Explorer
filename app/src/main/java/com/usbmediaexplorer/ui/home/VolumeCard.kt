package com.usbmediaexplorer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.volume.FileSystemProbe
import com.usbmediaexplorer.data.volume.VolumeInfo
import com.usbmediaexplorer.data.volume.VolumeKind
import com.usbmediaexplorer.data.volume.VolumeState
import com.usbmediaexplorer.util.Formatters

/**
 * One storage entry (spec §1): name, kind icon, used/free space, and a single "grant access"
 * button when Android still needs permission for this volume.
 */
@Composable
fun VolumeCard(
    volume: VolumeInfo,
    onOpen: () -> Unit,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = iconFor(volume.kind),
                    contentDescription = stringResource(R.string.cd_volume_icon),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = volume.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = subtitleFor(volume)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StateChip(volume.state)
            }

            if (volume.totalBytes != null && volume.totalBytes > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { volume.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.volume_free_of,
                        Formatters.size(volume.freeBytes ?: 0),
                        Formatters.size(volume.totalBytes ?: 0),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (volume.state == VolumeState.READY) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.volume_space_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (volume.state) {
                    VolumeState.READY -> Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_open))
                    }

                    VolumeState.NEEDS_PERMISSION -> OutlinedButton(
                        onClick = onGrant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_grant_access))
                    }

                    VolumeState.UNMOUNTED -> OutlinedButton(
                        onClick = onGrant,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.volume_unmounted))
                    }
                }
            }
        }
    }
}

@Composable
private fun subtitleFor(volume: VolumeInfo): String {
    val parts = ArrayList<String>(3)
    FileSystemProbe.labelFor(volume.fileSystem)?.let {
        parts += stringResource(R.string.volume_fs_label, it)
    }
    volume.deviceLabel?.let { parts += it }
    if (volume.state == VolumeState.NEEDS_PERMISSION) {
        parts += stringResource(R.string.volume_needs_permission)
    }
    volume.description?.takeIf { volume.state == VolumeState.UNMOUNTED }?.let { parts += it }
    return parts.joinToString(" • ")
}

@Composable
private fun StateChip(state: VolumeState) {
    val (label, color) = when (state) {
        VolumeState.READY -> stringResource(R.string.action_open) to MaterialTheme.colorScheme.primary
        VolumeState.NEEDS_PERMISSION ->
            stringResource(R.string.volume_needs_permission) to MaterialTheme.colorScheme.tertiary

        VolumeState.UNMOUNTED ->
            stringResource(R.string.volume_unmounted) to MaterialTheme.colorScheme.outline
    }
    if (state == VolumeState.READY) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun iconFor(kind: VolumeKind) = when (kind) {
    VolumeKind.INTERNAL -> Icons.Outlined.Storage
    VolumeKind.SD_CARD -> Icons.Outlined.SdCard
    VolumeKind.USB -> Icons.Outlined.Usb
    VolumeKind.EXTERNAL -> Icons.Outlined.Storage
}
