package com.usbmediaexplorer.ui.browse.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode

/**
 * Explorer-style path bar (spec §2). Horizontally scrollable and auto-scrolled to the deepest
 * segment so the current folder is always visible, even on a 5-level path in Arabic RTL.
 */
@Composable
fun BreadcrumbBar(
    trail: List<DocNode>,
    onNavigate: (DocNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trail.isEmpty()) return
    val scrollState = rememberScrollState()
    LaunchedEffect(trail.size) { scrollState.scrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = stringResource(R.string.cd_volume_icon),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        trail.forEachIndexed { index, node ->
            val isLast = index == trail.lastIndex
            TextButton(
                onClick = { if (!isLast) onNavigate(node) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    text = node.name.ifEmpty { stringResource(R.string.breadcrumb_root) },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isLast) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isLast) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
