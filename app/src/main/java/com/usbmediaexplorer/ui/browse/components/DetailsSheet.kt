package com.usbmediaexplorer.ui.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.ui.browse.DetailsState
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.util.Formatters

/** File/folder information sheet (spec §21). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    state: DetailsState,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val node = state.node
    val metadata = state.metadata
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumbnail(
                    node = node,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = node.displayPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()

            InfoRow(stringResource(R.string.label_path), node.displayPath)
            InfoRow(
                stringResource(R.string.label_size),
                Formatters.size(state.sizeBytes ?: node.size.coerceAtLeast(0)),
            )
            InfoRow(
                stringResource(R.string.label_type),
                node.mimeType ?: MediaKind.mimeTypeFor(node.extension).ifBlank {
                    stringResource(R.string.label_unknown)
                },
            )
            if (node.lastModified > 0) {
                InfoRow(stringResource(R.string.label_modified), Formatters.dateTime(node.lastModified))
            }
            if (metadata != null) {
                if (metadata.durationMs > 0) {
                    InfoRow(stringResource(R.string.label_duration), Formatters.duration(metadata.durationMs))
                }
                if (metadata.width > 0) {
                    InfoRow(
                        stringResource(R.string.label_resolution),
                        "${metadata.effectiveWidth}×${metadata.effectiveHeight}" +
                            metadata.resolutionLabel.let { if (it.isNotEmpty()) " ($it)" else "" },
                    )
                }
                if (metadata.codecLabel.isNotEmpty()) {
                    InfoRow(stringResource(R.string.label_codec), metadata.codecLabel)
                }
                if (metadata.fps > 0f) {
                    InfoRow(stringResource(R.string.label_fps), metadata.fpsLabel)
                }
                if (metadata.bitrate > 0) {
                    InfoRow(
                        stringResource(R.string.label_size) + " / bitrate",
                        Formatters.size(metadata.bitrate / 8) + "/s",
                    )
                }
                if (metadata.audioTracks.isNotEmpty()) {
                    InfoRow(
                        stringResource(R.string.label_audio),
                        metadata.audioTracks.joinToString(", ") { it.displayName },
                    )
                }
                if (metadata.subtitleTracks.isNotEmpty()) {
                    InfoRow(
                        stringResource(R.string.label_subtitle),
                        metadata.subtitleTracks.joinToString(", ") { it.displayName },
                    )
                }
            }
            state.volumeName?.let { InfoRow(stringResource(R.string.label_storage), it) }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open))
                }
                if (!node.isDirectory) {
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Outlined.DriveFileRenameOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_rename))
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!node.isDirectory) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.58f),
        )
    }
}
