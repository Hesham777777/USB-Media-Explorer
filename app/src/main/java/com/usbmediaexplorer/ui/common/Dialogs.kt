package com.usbmediaexplorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.ops.BulkRenamePlanner
import com.usbmediaexplorer.data.ops.BulkRenameRules
import com.usbmediaexplorer.data.store.PlaybackPosition
import com.usbmediaexplorer.util.Formatters

/** Single-line text prompt used for rename / new folder / new file / archive name. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Resume playback prompt (spec §19). */
@Composable
fun ResumeDialog(
    position: PlaybackPosition,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_resume_title)) },
        text = {
            Text(
                stringResource(
                    R.string.dialog_resume_body,
                    Formatters.duration(position.positionMs),
                    Formatters.duration(position.remainingMs),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onStartOver) { Text(stringResource(R.string.action_start_over)) }
            }
        },
    )
}

/** Batch renamer with a live preview (spec §16). */
@Composable
fun BulkRenameDialog(
    items: List<DocNode>,
    onApply: (BulkRenameRules) -> Unit,
    onDismiss: () -> Unit,
) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var numbering by remember { mutableStateOf(false) }
    var startAt by remember { mutableStateOf("1") }
    var padding by remember { mutableStateOf("2") }
    var position by remember { mutableIntStateOf(0) }

    val rules = BulkRenameRules(
        find = find,
        replace = replace,
        prefix = prefix,
        suffix = suffix,
        numbering = numbering,
        startAt = startAt.toIntOrNull() ?: 1,
        padding = padding.toIntOrNull() ?: 2,
        numberingPosition = if (position == 0) {
            BulkRenameRules.NumberingPosition.BEFORE_NAME
        } else {
            BulkRenameRules.NumberingPosition.AFTER_NAME
        },
    )
    val preview = remember(items, rules) { BulkRenamePlanner.plan(items, rules).take(6) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_bulk_rename)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(stringResource(R.string.bulk_find)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text(stringResource(R.string.bulk_replace)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = { prefix = it },
                        label = { Text(stringResource(R.string.bulk_prefix)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = suffix,
                        onValueChange = { suffix = it },
                        label = { Text(stringResource(R.string.bulk_suffix)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.bulk_numbering),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = numbering, onCheckedChange = { numbering = it })
                }
                if (numbering) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startAt,
                            onValueChange = { startAt = it.filter(Char::isDigit).take(4) },
                            label = { Text(stringResource(R.string.bulk_start_at)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = padding,
                            onValueChange = { padding = it.filter(Char::isDigit).take(1) },
                            label = { Text(stringResource(R.string.bulk_padding)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { position = 0 }) {
                            Text(
                                text = stringResource(R.string.bulk_prefix),
                                fontWeight = if (position == 0) {
                                    MaterialTheme.typography.titleSmall.fontWeight
                                } else {
                                    MaterialTheme.typography.bodyMedium.fontWeight
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { position = 1 }) {
                            Text(
                                text = stringResource(R.string.bulk_suffix),
                                fontWeight = if (position == 1) {
                                    MaterialTheme.typography.titleSmall.fontWeight
                                } else {
                                    MaterialTheme.typography.bodyMedium.fontWeight
                                },
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.bulk_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(preview, key = { it.first.key }) { (node, newName) ->
                        Column {
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = newName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.items_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(rules) },
                enabled = !rules.isEmpty,
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Simple list dialog used for track/speed/aspect pickers in the player. */
@Composable
fun OptionDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                LazyColumn {
                    items(options.size) { index ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = options[index],
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (index == selectedIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onSelect(index) }) {
                                Text(stringResource(R.string.action_apply))
                            }
                        }
                    }
                }
                footer?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
