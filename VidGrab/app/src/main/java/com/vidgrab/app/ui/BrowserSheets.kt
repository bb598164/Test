@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.R
import com.vidgrab.app.browser.ContextKind
import com.vidgrab.app.browser.ContextMenuRequest
import com.vidgrab.app.browser.FileDownloadRequest
import com.vidgrab.app.browser.displayUrl
import com.vidgrab.app.browser.parseUrl
import com.vidgrab.app.core.Bookmark
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.DetectedMedia
import com.vidgrab.app.core.HistoryEntry
import com.vidgrab.app.core.formatBytes
import com.vidgrab.app.core.formatDuration

// ---------------------------------------------------------------------- download sheet

@Composable
internal fun DownloadSheet(
    vm: MainViewModel,
    candidates: List<DetectedMedia>,
    onDismiss: () -> Unit,
    onOpenHome: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val home by vm.home.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<DetectedMedia?>(null) }

    // a single candidate (the usual case): no need to ask, start analysing right away
    LaunchedEffect(Unit) {
        if (candidates.size == 1) {
            chosen = candidates[0]
            vm.analyzeCandidate(candidates[0])
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (chosen == null) {
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.sheet_empty),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                candidates.forEach { candidate ->
                    CandidateRow(candidate) {
                        chosen = candidate
                        vm.analyzeCandidate(candidate)
                    }
                }
            } else {
                AnalysisStage(
                    vm = vm,
                    home = home,
                    canGoBackToList = candidates.size > 1,
                    onBackToList = { chosen = null },
                    onDone = onDismiss,
                    onOpenHome = onOpenHome,
                )
            }
        }
    }
}

@Composable
private fun AnalysisStage(
    vm: MainViewModel,
    home: HomeState,
    canGoBackToList: Boolean,
    onBackToList: () -> Unit,
    onDone: () -> Unit,
    onOpenHome: () -> Unit,
) {
    val info = home.info
    when {
        home.analyzing -> Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.sheet_analyzing))
        }
        home.error != null -> {
            ErrorCard(home.error, home.errorKind)
            if (canGoBackToList) {
                TextButton(onClick = onBackToList, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.sheet_back))
                }
            }
        }
        info != null -> {
            MediaCard(info)
            if (info.isPlaylist) {
                Text(
                    stringResource(R.string.sheet_playlist_note),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenHome,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text(stringResource(R.string.sheet_open_home)) }
            } else {
                OptionsCard(
                    ui = home,
                    info = info,
                    onMode = vm::setMode,
                    onHeight = vm::setHeight,
                    onAudioFormat = vm::setAudioFormat,
                    onAudioBitrate = vm::setAudioBitrate,
                )
                Button(
                    onClick = {
                        vm.startDownload()
                        onDone()
                    },
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: DetectedMedia, onClick: () -> Unit) {
    val title = stringResource(
        when (candidate.kind) {
            DetectedKind.PAGE -> R.string.cand_page
            DetectedKind.FRAME -> R.string.cand_frame
            DetectedKind.DIRECT -> R.string.cand_direct
            DetectedKind.AUDIO -> R.string.cand_audio
            DetectedKind.HLS -> R.string.cand_hls
            DetectedKind.DASH -> R.string.cand_dash
        }
    )
    val icon = when (candidate.kind) {
        DetectedKind.AUDIO -> Icons.Filled.MusicNote
        DetectedKind.PAGE, DetectedKind.FRAME -> Icons.Filled.Language
        else -> Icons.Filled.VideoLibrary
    }
    val meta = listOfNotNull(
        formatDuration(candidate.durationSec).ifEmpty { null },
        if (candidate.height > 0) "${candidate.height}p" else null,
    ).joinToString(" · ")
    Card(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (candidate.kind == DetectedKind.PAGE) {
                    Text(
                        stringResource(R.string.cand_page_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    displayUrl(candidate.url),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Download, contentDescription = null)
        }
    }
}

// ---------------------------------------------------------------------- bookmarks / history

@Composable
internal fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    onOpen: (String) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.bookmarks),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (bookmarks.isEmpty()) {
            Text(
                stringResource(R.string.bookmarks_empty),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(bookmarks) { bookmark ->
                    ListItem(
                        headlineContent = { Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(displayUrl(bookmark.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(bookmark) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_from_list))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onOpen(bookmark.url) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun HistorySheet(
    history: List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onRemove: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.history_clear)) }
            }
        }
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(history) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(displayUrl(entry.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(entry) }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_from_list))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onOpen(entry.url) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------- long press / file download

enum class ContextAction { OPEN_NEW_TAB, OPEN_BACKGROUND, COPY_LINK, SHARE_LINK, DOWNLOAD_MEDIA, DOWNLOAD_IMAGE, COPY_IMAGE }

/** What the user can do with the link / image they pressed on. */
@Composable
internal fun ContextMenuDialog(
    request: ContextMenuRequest,
    onAction: (ContextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList<Pair<ContextAction, Int>> {
        add(ContextAction.OPEN_NEW_TAB to R.string.ctx_open_new_tab)
        if (request.kind == ContextKind.LINK) {
            add(ContextAction.OPEN_BACKGROUND to R.string.ctx_open_background)
            add(ContextAction.COPY_LINK to R.string.ctx_copy_link)
            add(ContextAction.SHARE_LINK to R.string.ctx_share_link)
            add(ContextAction.DOWNLOAD_MEDIA to R.string.ctx_download_media)
        }
        if (request.kind == ContextKind.IMAGE || request.imageUrl != null) {
            add(ContextAction.DOWNLOAD_IMAGE to R.string.ctx_download_image)
            add(ContextAction.COPY_IMAGE to R.string.ctx_copy_image)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                displayUrl(request.url),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                options.forEach { (action, label) ->
                    TextButton(
                        onClick = {
                            onAction(action)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(label)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** A normal file (pdf, zip, apk ...) the page offers: ask first, then hand it to the system downloader. */
@Composable
internal fun FileDownloadDialog(
    request: FileDownloadRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val details = listOfNotNull(
        if (request.length > 0) formatBytes(request.length) else null,
        parseUrl(request.url)?.host,
    ).joinToString(" · ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_download_title)) },
        text = {
            Column {
                Text(request.fileName, fontWeight = FontWeight.SemiBold)
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
