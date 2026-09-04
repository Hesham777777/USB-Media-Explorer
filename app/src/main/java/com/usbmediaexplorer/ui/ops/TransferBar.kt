package com.usbmediaexplorer.ui.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.ops.JobProgress
import com.usbmediaexplorer.data.ops.JobState
import com.usbmediaexplorer.data.ops.OpType
import com.usbmediaexplorer.util.Formatters

/**
 * Persistent "something is running" bar. A copy from a 2 TB stick can take an hour; the user
 * must always see percent, speed and remaining time without hunting for the transfers screen
 * (spec §14).
 */
@Composable
fun TransferBar(
    job: JobProgress,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(opTitleRes(job.type)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = job.currentItemName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (job.state == JobState.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.action_resume_op))
                    }
                } else if (job.state == JobState.RUNNING || job.state == JobState.QUEUED) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.action_pause))
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { job.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stateLabel(job),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append("${job.percent}%")
                        if (job.speedBytesPerSec > 1024) {
                            append(" • ").append(Formatters.speed(job.speedBytesPerSec))
                        }
                        if (job.etaMs > 0) {
                            append(" • ").append(stringResource(R.string.transfer_eta, Formatters.eta(job.etaMs)))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun stateLabel(job: JobProgress): String = when (job.state) {
    JobState.QUEUED -> stringResource(R.string.transfer_state_queued)
    JobState.RUNNING -> "${job.doneItems}/${job.totalItems}"
    JobState.PAUSED -> stringResource(R.string.transfer_state_paused)
    JobState.CANCELED -> stringResource(R.string.transfer_state_canceled)
    JobState.FAILED -> job.error ?: stringResource(R.string.transfer_state_failed)
    JobState.DONE -> stringResource(R.string.transfer_state_done)
}

@Composable
fun opTitleRes(type: OpType): Int = when (type) {
    OpType.COPY -> R.string.op_copy
    OpType.MOVE -> R.string.op_move
    OpType.DELETE -> R.string.op_delete
    OpType.ZIP -> R.string.op_zip
    OpType.UNZIP -> R.string.op_unzip
    OpType.BULK_RENAME -> R.string.op_rename
}
