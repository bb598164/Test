@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.R
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.DownloadStatus
import com.vidgrab.app.core.MediaKind
import com.vidgrab.app.core.formatBytes
import com.vidgrab.app.core.formatEta
import com.vidgrab.app.core.formatSpeed

@Composable
fun DownloadsScreen(vm: MainViewModel, bottomPadding: Dp) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }

    val visible = remember(downloads, filter) {
        val base = when (filter) {
            1 -> downloads.filter { it.status != DownloadStatus.COMPLETED }
            2 -> downloads.filter { it.status == DownloadStatus.COMPLETED }
            else -> downloads
        }
        base.sortedByDescending { it.createdAt }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_downloads), fontWeight = FontWeight.Bold) },
            actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pause_all)) },
                            leadingIcon = { Icon(Icons.Filled.Pause, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                vm.pauseAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.resume_all)) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                vm.resumeAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_completed)) },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                vm.clearCompleted()
                            },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(R.string.filter_all, R.string.filter_active, R.string.filter_done).forEachIndexed { index, res ->
                FilterChip(
                    selected = filter == index,
                    onClick = { filter = index },
                    label = { Text(stringResource(res)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (visible.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.empty_downloads_title),
                subtitle = stringResource(R.string.empty_downloads_subtitle),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPause = { vm.pause(item.id) },
                        onResume = { vm.resume(item.id) },
                        onCancel = { vm.cancel(item.id) },
                        onOpen = { vm.openFile(context, item) },
                        onShare = { vm.shareFile(context, item) },
                        onRemove = {
                            if (item.status == DownloadStatus.COMPLETED) confirmDelete = item
                            else vm.remove(item.id, false)
                        },
                    )
                }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(item.id, true)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete_file), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.remove(item.id, false)
                    confirmDelete = null
                }) { Text(stringResource(R.string.remove_from_list)) }
            },
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(
                    url = item.thumbnail,
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(16f / 9f),
                    icon = if (item.kind == MediaKind.AUDIO) Icons.Filled.MusicNote else Icons.Filled.VideoLibrary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitleText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val barModifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
            when (item.status) {
                DownloadStatus.QUEUED, DownloadStatus.PROCESSING -> LinearProgressIndicator(modifier = barModifier)
                DownloadStatus.DOWNLOADING -> LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = barModifier,
                )
                DownloadStatus.PAUSED -> LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = barModifier,
                    color = colors.outline,
                )
                else -> Unit
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        statusText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(item),
                    )
                    if (item.status == DownloadStatus.FAILED && !item.error.isNullOrEmpty()) {
                        Text(
                            item.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        ActionButton(Icons.Filled.Pause, R.string.pause, onPause)
                        ActionButton(Icons.Filled.Close, R.string.cancel, onCancel)
                    }
                    DownloadStatus.QUEUED -> ActionButton(Icons.Filled.Close, R.string.cancel, onCancel)
                    DownloadStatus.PAUSED -> {
                        ActionButton(Icons.Filled.PlayArrow, R.string.resume, onResume)
                        ActionButton(Icons.Filled.Close, R.string.cancel, onCancel)
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        ActionButton(Icons.Filled.Refresh, R.string.retry, onResume)
                        ActionButton(Icons.Filled.Delete, R.string.remove_from_list, onRemove)
                    }
                    DownloadStatus.COMPLETED -> {
                        ActionButton(Icons.Filled.PlayArrow, R.string.open, onOpen)
                        ActionButton(Icons.Filled.Share, R.string.share, onShare)
                        ActionButton(Icons.Filled.Delete, R.string.delete_file, onRemove)
                    }
                    DownloadStatus.PROCESSING -> Unit
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun subtitleText(item: DownloadItem): String {
    val quality = if (item.qualityLabel == "best") stringResource(R.string.quality_best) else item.qualityLabel
    return listOfNotNull(quality, item.uploader?.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

@Composable
private fun statusText(item: DownloadItem): String = when (item.status) {
    DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
    DownloadStatus.DOWNLOADING -> downloadingLine(item)
    DownloadStatus.PROCESSING -> stringResource(R.string.status_processing)
    DownloadStatus.PAUSED -> stringResource(R.string.status_paused) + " · ${(item.progress * 100).toInt()}%"
    DownloadStatus.COMPLETED ->
        stringResource(R.string.status_completed) + (if (item.outputSize > 0) " · ${formatBytes(item.outputSize)}" else "")
    DownloadStatus.FAILED -> stringResource(R.string.status_failed)
    DownloadStatus.CANCELLED -> stringResource(R.string.status_cancelled)
}

/** 45% · 12.3 MB / 27 MB · 2.3 MB/s · 0:28 */
private fun downloadingLine(item: DownloadItem): String = buildString {
    append("${(item.progress * 100).toInt()}%")
    if (item.totalBytes > 0) append(" · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}")
    if (item.speedBps > 0) append(" · ${formatSpeed(item.speedBps)}")
    if (item.etaSec > 0) append(" · ${formatEta(item.etaSec)}")
}

@Composable
private fun statusColor(item: DownloadItem): Color = when (item.status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
