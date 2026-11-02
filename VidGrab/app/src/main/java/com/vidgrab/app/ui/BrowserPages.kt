@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.R
import com.vidgrab.app.browser.ERROR_SSL_REJECTED
import com.vidgrab.app.browser.TabInfo
import com.vidgrab.app.browser.displayUrl
import com.vidgrab.app.browser.parseUrl
import com.vidgrab.app.browser.registrableDomain
import com.vidgrab.app.core.Bookmark
import com.vidgrab.app.core.HistoryEntry
import com.vidgrab.app.core.Suggestion
import com.vidgrab.app.core.SuggestionKind

// ---------------------------------------------------------------------- video speed

/** Playback speed of the videos on the page; tap to cycle 1x, 1.25x, 1.5x, 2x, 3x, 0.5x. */
@Composable
internal fun SpeedChip(speed: Float, onClick: () -> Unit) {
    val label = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    FilterChip(
        selected = speed != 1f,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

// ---------------------------------------------------------------------- start page

private class Tile(val name: String, val url: String, val color: Color)

private val DEFAULT_TILES = listOf(
    Tile("Google", "https://www.google.com", Color(0xFF4285F4)),
    Tile("YouTube", "https://m.youtube.com", Color(0xFFE53935)),
    Tile("Facebook", "https://m.facebook.com", Color(0xFF1877F2)),
    Tile("Instagram", "https://www.instagram.com", Color(0xFFC13584)),
    Tile("X", "https://x.com", Color(0xFF111111)),
    Tile("TikTok", "https://www.tiktok.com", Color(0xFF00A7A0)),
    Tile("Vimeo", "https://vimeo.com", Color(0xFF1AB7EA)),
    Tile("Dailymotion", "https://www.dailymotion.com", Color(0xFF0066DC)),
    Tile("Reddit", "https://www.reddit.com", Color(0xFFFF4500)),
    Tile("Twitch", "https://m.twitch.tv", Color(0xFF9146FF)),
    Tile("Wikipedia", "https://www.wikipedia.org", Color(0xFF546E7A)),
    Tile("Bing", "https://www.bing.com", Color(0xFF008373)),
)

private val AVATAR_COLORS = listOf(
    Color(0xFF3F51B5), Color(0xFF009688), Color(0xFFE91E63), Color(0xFF795548),
    Color(0xFF607D8B), Color(0xFFFF9800), Color(0xFF8E24AA), Color(0xFF2E7D32),
)

/** The user's bookmarks first, then well-known sites that are not bookmarked yet (12 tiles at most). */
private fun quickAccess(bookmarks: List<Bookmark>): List<Tile> {
    val seen = HashSet<String>()
    val out = ArrayList<Tile>()
    for (bookmark in bookmarks) {
        if (out.size >= 8) break
        val host = parseUrl(bookmark.url)?.host ?: continue
        val domain = registrableDomain(host)
        if (!seen.add(domain)) continue
        val color = AVATAR_COLORS[Math.floorMod(domain.hashCode(), AVATAR_COLORS.size)]
        out += Tile(bookmark.title.ifBlank { domain }.take(14), bookmark.url, color)
    }
    for (tile in DEFAULT_TILES) {
        if (out.size >= 12) break
        val host = parseUrl(tile.url)?.host ?: continue
        if (seen.add(registrableDomain(host))) out += tile
    }
    return out
}

/** The new-tab page: quick access tiles (bookmarks + popular sites) and recently visited pages. */
@Composable
internal fun StartPage(
    bookmarks: List<Bookmark>,
    history: List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onShowHistory: () -> Unit,
) {
    val tiles = remember(bookmarks) { quickAccess(bookmarks) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.browser_start_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.browser_start_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionTitle(stringResource(R.string.quick_access)) }
        items(tiles.chunked(4)) { rowTiles ->
            Row(Modifier.fillMaxWidth()) {
                rowTiles.forEach { tile -> ShortcutTile(tile, Modifier.weight(1f)) { onOpen(tile.url) } }
                repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (history.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.recent_pages), Modifier.weight(1f))
                    TextButton(onClick = onShowHistory) { Text(stringResource(R.string.history)) }
                }
            }
            items(history.take(5)) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(displayUrl(entry.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(entry.url) },
                )
            }
        }
    }
}

@Composable
private fun ShortcutTile(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(tile.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(tile.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Text(
            tile.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------- errors

@Composable
internal fun ErrorOverlay(message: String, url: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.error_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (message == ERROR_SSL_REJECTED) stringResource(R.string.ssl_rejected) else message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (url.isNotEmpty()) {
            Text(
                displayUrl(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.retry))
        }
    }
}

/** The site's certificate is not valid: the user decides whether to continue. */
@Composable
internal fun SslOverlay(code: String, host: String, onBack: () -> Unit, onProceed: () -> Unit) {
    val detail = stringResource(
        when (code) {
            "untrusted" -> R.string.ssl_untrusted
            "expired" -> R.string.ssl_expired
            "mismatch" -> R.string.ssl_mismatch
            "notyetvalid" -> R.string.ssl_notyetvalid
            else -> R.string.ssl_other
        }
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.ssl_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(host, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        TextButton(onClick = onProceed) {
            Text(stringResource(R.string.ssl_continue), color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---------------------------------------------------------------------- address suggestions / find in page

@Composable
internal fun SuggestionsList(
    query: String,
    suggestions: List<Suggestion>,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column {
            SuggestionRow(Icons.Filled.Search, stringResource(R.string.suggest_search, query), null, onSearch)
            suggestions.forEach { suggestion ->
                SuggestionRow(
                    icon = if (suggestion.kind == SuggestionKind.BOOKMARK) Icons.Filled.Star else Icons.Filled.History,
                    title = suggestion.title,
                    subtitle = displayUrl(suggestion.url),
                    onClick = { onOpen(suggestion.url) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun FindBar(
    query: String,
    count: Int,
    index: Int,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val none = stringResource(R.string.find_none)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.find_hint)) },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            val counter = if (query.isEmpty()) "" else if (count == 0) none else "$index / $count"
            Text(counter, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = onPrevious, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
            }
            IconButton(onClick = onNext, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}

// ---------------------------------------------------------------------- tab switcher

@Composable
internal fun TabSwitcher(
    tabs: List<TabInfo>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tabs_title, tabs.size),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCloseAll) {
                    Icon(Icons.Filled.ClearAll, contentDescription = stringResource(R.string.tabs_close_all))
                }
                IconButton(onClick = onNew) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tab_new))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(tabs, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        selected = tab.id == activeId,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCard(tab: TabInfo, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val newTabLabel = stringResource(R.string.tab_new)
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = tab.favicon
                if (icon != null) {
                    Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp))
                } else {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.tab_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                tab.title.ifEmpty { if (tab.url.isEmpty()) newTabLabel else displayUrl(tab.url) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.url.isNotEmpty()) {
                Text(
                    displayUrl(tab.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
