@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vidgrab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vidgrab.app.R
import com.vidgrab.app.core.MediaEntry
import com.vidgrab.app.core.MediaInfo
import com.vidgrab.app.core.MediaKind
import com.vidgrab.app.core.QualityOption
import com.vidgrab.app.core.UiText
import com.vidgrab.app.core.extractUrl
import com.vidgrab.app.core.formatBytes
import com.vidgrab.app.core.formatDuration
import com.vidgrab.app.engine.PyEngine
import com.vidgrab.app.ui.theme.HeroBrush

@Composable
fun HomeScreen(vm: MainViewModel, bottomPadding: Dp) {
    val ui by vm.home.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val info = ui.info
    val count = ui.selected.size

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "hero") { Hero() }

            if (engine !is PyEngine.State.Ready) {
                item(key = "engine") { EngineBanner(engine, onRetry = vm::retryEngine) }
            }

            ui.clipboardUrl?.let { clip ->
                item(key = "clip") {
                    ClipboardCard(clip, onUse = vm::acceptClipboard, onDismiss = vm::dismissClipboard)
                }
            }

            item(key = "input") {
                InputCard(
                    value = ui.input,
                    analyzing = ui.analyzing,
                    onChange = vm::onInputChange,
                    onPaste = vm::pasteFromClipboard,
                    onClear = vm::clearInput,
                    onAnalyze = vm::analyze,
                )
            }

            ui.error?.let { err ->
                item(key = "error") {
                    val site = extractUrl(ui.input)
                    ErrorCard(
                        err,
                        ui.errorKind,
                        onOpenBrowser = if (site != null) ({ vm.openInBrowser(site) }) else null,
                    )
                }
            }

            if (info != null) {
                item(key = "media") { MediaCard(info) }

                if (info.playlistUrl != null) {
                    item(key = "plurl") { LoadPlaylistCard(onClick = vm::loadWholePlaylist) }
                }

                item(key = "options") {
                    OptionsCard(
                        ui = ui,
                        info = info,
                        onMode = vm::setMode,
                        onHeight = vm::setHeight,
                        onAudioFormat = vm::setAudioFormat,
                        onAudioBitrate = vm::setAudioBitrate,
                    )
                }

                if (info.isPlaylist) {
                    item(key = "plheader") {
                        PlaylistHeader(total = info.entries.size, selected = count, onToggleAll = vm::toggleAll)
                    }
                    itemsIndexed(info.entries, key = { index, _ -> "entry$index" }) { index, entry ->
                        EntryRow(entry, index in ui.selected) { vm.toggleEntry(index) }
                    }
                }
            }
        }

        if (info != null && count > 0) {
            ExtendedFloatingActionButton(
                onClick = vm::startDownload,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                text = {
                    Text(
                        if (info.isPlaylist) stringResource(R.string.download_count, count)
                        else stringResource(R.string.download)
                    )
                },
            )
        }
    }
}

@Composable
private fun Hero() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(HeroBrush)
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.hero_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun EngineBanner(state: PyEngine.State, onRetry: () -> Unit) {
    when (state) {
        is PyEngine.State.Ready -> Unit
        is PyEngine.State.Starting -> Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.engine_preparing), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            when (state.step) {
                                PyEngine.Step.PYTHON -> R.string.engine_step_python
                                PyEngine.Step.FFMPEG -> R.string.engine_step_ffmpeg
                                PyEngine.Step.YTDLP -> R.string.engine_step_ytdlp
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        is PyEngine.State.Failed -> Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.engine_failed), fontWeight = FontWeight.SemiBold)
                }
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun ClipboardCard(url: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.clipboard_found),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onUse) { Text(stringResource(R.string.use_link)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun InputCard(
    value: String,
    analyzing: Boolean,
    onChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onAnalyze: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.link_label)) },
                placeholder = { Text("https://") },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                trailingIcon = {
                    if (value.isEmpty()) {
                        IconButton(onClick = onPaste) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste))
                        }
                    } else {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
                shape = RoundedCornerShape(14.dp),
            )
            Button(
                onClick = onAnalyze,
                enabled = value.isNotBlank() && !analyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.analyzing))
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.analyze))
                }
            }
            Text(
                stringResource(R.string.supported_sites),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ErrorCard(text: UiText, kind: String, onOpenBrowser: (() -> Unit)? = null) {
    val hint = when (kind) {
        "login" -> R.string.hint_login
        "unsupported" -> R.string.hint_unsupported
        "drm" -> R.string.hint_drm
        "network" -> R.string.hint_network
        else -> null
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.Error, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text.asString(), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (hint != null) {
                    Text(
                        stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (kind == "login" && onOpenBrowser != null) {
                    TextButton(onClick = onOpenBrowser) { Text(stringResource(R.string.open_in_browser)) }
                }
            }
        }
    }
}

@Composable
internal fun MediaCard(info: MediaInfo) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        if (!info.thumbnail.isNullOrEmpty()) {
            Box {
                AsyncImage(
                    model = info.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
                val duration = formatDuration(info.durationSec)
                if (duration.isNotEmpty() && !info.isPlaylist) {
                    Text(
                        duration,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info.isPlaylist) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.playlist_count, info.entries.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                info.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(info.uploader, info.extractor.takeIf { it.isNotEmpty() }).joinToString(" • ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadPlaylistCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.in_playlist_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.in_playlist_action), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun OptionsCard(
    ui: HomeState,
    info: MediaInfo,
    onMode: (MediaKind) -> Unit,
    onHeight: (Int) -> Unit,
    onAudioFormat: (String) -> Unit,
    onAudioBitrate: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = ui.mode == MediaKind.VIDEO,
                    onClick = { onMode(MediaKind.VIDEO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.video)) }
                SegmentedButton(
                    selected = ui.mode == MediaKind.AUDIO,
                    onClick = { onMode(MediaKind.AUDIO) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.audio)) }
            }

            if (ui.mode == MediaKind.VIDEO) {
                Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    info.qualities.forEach { q ->
                        FilterChip(
                            selected = ui.height == q.height,
                            onClick = { onHeight(q.height) },
                            label = { Text(qualityChipLabel(q)) },
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.audio_format), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "m4a", "opus").forEach { format ->
                        FilterChip(
                            selected = ui.audioFormat == format,
                            onClick = { onAudioFormat(format) },
                            label = { Text(format.uppercase()) },
                        )
                    }
                }
                if (ui.audioFormat != "opus") {
                    Text(stringResource(R.string.audio_bitrate), style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(128, 192, 256, 320).forEach { kbps ->
                            FilterChip(
                                selected = ui.audioBitrate == kbps,
                                onClick = { onAudioBitrate(kbps) },
                                label = { Text("$kbps kbps") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun qualityChipLabel(q: QualityOption): String {
    val base = if (q.height == 0) stringResource(R.string.quality_best) else "${q.height}p"
    return if (q.sizeBytes > 0) "$base · ~${formatBytes(q.sizeBytes)}" else base
}

@Composable
private fun PlaylistHeader(total: Int, selected: Int, onToggleAll: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleAll)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected == total, onCheckedChange = { onToggleAll() })
        Text(
            stringResource(R.string.select_all_count, selected, total),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun EntryRow(entry: MediaEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Thumb(entry.thumbnail, Modifier.width(92.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val duration = formatDuration(entry.durationSec)
            if (duration.isNotEmpty()) {
                Text(
                    duration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
