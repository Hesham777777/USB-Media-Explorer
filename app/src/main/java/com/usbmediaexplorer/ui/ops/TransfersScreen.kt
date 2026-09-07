package com.usbmediaexplorer.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.ops.JobProgress
import com.usbmediaexplorer.data.ops.JobState
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.common.StateBlock
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.util.Formatters

/** Live list of every background operation with pause / resume / cancel (spec §14). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val jobs by container.fileOpsManager.jobs.collectAsStateWithLifecycle()
    val manager = container.fileOpsManager

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.ops_title)) },
                actions = {
                    if (jobs.any { !it.isActive }) {
                        IconButton(onClick = { manager.clearFinished() }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                StateBlock(
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(R.string.ops_no_jobs),
                    body = stringResource(R.string.ops_no_jobs_body),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(jobs, key = { it.jobId }) { job ->
                JobCard(
                    job = job,
                    onPause = { manager.pause(job.jobId) },
                    onResume = { manager.resume(job.jobId) },
                    onCancel = { manager.cancel(job.jobId) },
                    onDismiss = { manager.dismiss(job.jobId) },
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: JobProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(opTitleRes(job.type)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = job.currentItemName.ifBlank { job.destinationLabel },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stateLabel(job),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (job.state) {
                        JobState.FAILED -> MaterialTheme.colorScheme.error
                        JobState.DONE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(6.dp))
                when (job.state) {
                    JobState.RUNNING, JobState.QUEUED -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.action_pause))
                    }

                    JobState.PAUSED -> IconButton(onClick = onResume) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(R.string.action_resume_op),
                        )
                    }

                    JobState.CANCELED, JobState.FAILED, JobState.DONE ->
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { job.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${job.doneItems}/${job.totalItems} • ${job.percent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        if (job.speedBytesPerSec > 1024) {
                            append(Formatters.speed(job.speedBytesPerSec))
                        }
                        if (job.etaMs > 0) {
                            if (isNotEmpty()) append(" • ")
                            append(stringResource(R.string.transfer_eta, Formatters.eta(job.etaMs)))
                        }
                        if (job.totalBytes > 0) {
                            if (isNotEmpty()) append(" • ")
                            append(
                                "${Formatters.size(job.transferredBytes)} / " +
                                    Formatters.size(job.totalBytes),
                            )
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (job.state == JobState.FAILED && job.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = job.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun stateLabel(job: JobProgress): String = when (job.state) {
    JobState.QUEUED -> stringResource(R.string.transfer_state_queued)
    JobState.RUNNING -> stringResource(R.string.transfer_state_running)
    JobState.PAUSED -> stringResource(R.string.transfer_state_paused)
    JobState.CANCELED -> stringResource(R.string.transfer_state_canceled)
    JobState.FAILED -> stringResource(R.string.transfer_state_failed)
    JobState.DONE -> stringResource(R.string.transfer_state_done)
}
