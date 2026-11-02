@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.R
import com.vidgrab.app.browser.ContextMenuRequest
import com.vidgrab.app.browser.FileDownloadRequest
import com.vidgrab.app.browser.TabState
import com.vidgrab.app.browser.buildSuggestions
import com.vidgrab.app.browser.displayUrl
import com.vidgrab.app.browser.parseUrl
import com.vidgrab.app.core.DetectedMedia
import kotlinx.coroutines.launch

/**
 * The Browser tab: address bar + the active tab's WebView (or the start page), with the download button,
 * tab switcher, bookmarks / history, find in page and the long-press / file-download dialogs around it.
 */
@Composable
fun BrowserScreen(vm: MainViewModel, bottomPadding: Dp, onOpenHome: () -> Unit) {
    val controller = vm.browser
    val ui by controller.activeState.collectAsStateWithLifecycle()
    val tabs by controller.tabInfos.collectAsStateWithLifecycle()
    val activeId by controller.activeId.collectAsStateWithLifecycle()
    val fullscreen by controller.fullscreenView.collectAsStateWithLifecycle()
    val bookmarks by controller.store.bookmarks.collectAsStateWithLifecycle()
    val history by controller.store.history.collectAsStateWithLifecycle()
    val pip by vm.inPictureInPicture.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var field by remember { mutableStateOf(TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    /** what the download sheet is showing; null = closed */
    var sheetCandidates by remember { mutableStateOf<List<DetectedMedia>?>(null) }
    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var contextMenu by remember { mutableStateOf<ContextMenuRequest?>(null) }
    var fileDownload by remember { mutableStateOf<FileDownloadRequest?>(null) }

    val inFullscreen = fullscreen != null
    /** the address bar, buttons and sheets are hidden while a video is fullscreen / in the PiP window */
    val chrome = !inFullscreen && !pip
    val bookmarked = remember(bookmarks, ui.url) { ui.url.isNotEmpty() && controller.store.isBookmarked(ui.url) }
    val editing = focused && field.text.isNotBlank() && field.text != displayUrl(ui.url)
    val suggestions = remember(field.text, bookmarks, history) { buildSuggestions(field.text, bookmarks, history) }

    val go: (String) -> Unit = { text ->
        focusManager.clearFocus()
        controller.load(text)
    }

    // the address bar shows the address of the page, unless the user is typing in it
    LaunchedEffect(ui.id, ui.url, ui.hasPage, focused) {
        if (!focused) field = TextFieldValue(if (ui.hasPage) displayUrl(ui.url) else "")
    }
    // another tab became active: the find bar and the keyboard belonged to the previous one
    LaunchedEffect(activeId) {
        showFind = false
        focusManager.clearFocus()
    }
    LaunchedEffect(controller) {
        // e.g. the user tapped a link to a video file: offer the download straight away
        launch { controller.openSheet.collect { sheetCandidates = controller.candidatesForSheet() } }
        launch { controller.contextMenus.collect { contextMenu = it } }
        launch { controller.fileDownloads.collect { fileDownload = it } }
        launch { controller.messages.collect { vm.showMessage(it) } }
    }

    // Pause the page when the app is hidden, except in the small picture-in-picture window where
    // the video has to keep playing. Android pauses the activity when it enters PiP, hence the extra resume.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (!vm.inPictureInPicture.value) controller.onHostPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onHostResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.onHostPause() }
    LaunchedEffect(pip) { if (pip) controller.onHostResume() }

    // Back: tab switcher, find bar, keyboard, then fullscreen / page history / popup tab, then Home
    BackHandler {
        if (showTabs) {
            showTabs = false
        } else if (showFind) {
            controller.clearFind()
            showFind = false
        } else if (focused) {
            focusManager.clearFocus()
        } else if (!controller.onBackPressed()) {
            onOpenHome()
        }
    }

    val menu = BrowserMenu(
        onForward = controller::goForward,
        onHome = controller::goHome,
        onNewTab = { controller.openTab() },
        onToggleBookmark = { controller.toggleBookmark() },
        onBookmarks = { showBookmarks = true },
        onHistory = { showHistory = true },
        onFind = { showFind = true },
        onDesktop = controller::toggleDesktop,
        onAnalyze = { sheetCandidates = controller.candidatesForSheet() },
        onCopyLink = {
            copyText(context, ui.url)
            vm.showMessage(R.string.link_copied)
        },
        onShareLink = { shareText(context, ui.url) },
        onOpenExternal = { openExternally(context, ui.url) },
        onClearData = vm::clearBrowsingData,
    )

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = if (chrome) bottomPadding else 0.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (chrome) {
                AddressBar(
                    field = field,
                    onFieldChange = { field = it },
                    onFocusChange = { hasFocus ->
                        focused = hasFocus
                        if (hasFocus) field = field.copy(selection = TextRange(0, field.text.length))
                    },
                    focused = focused,
                    state = ui,
                    tabCount = tabs.size,
                    bookmarked = bookmarked,
                    onGo = go,
                    onBack = controller::goBack,
                    onReload = controller::reload,
                    onStop = controller::stopLoading,
                    onTabs = {
                        focusManager.clearFocus()
                        showTabs = true
                    },
                    menu = menu,
                )
                if (showFind) {
                    FindBar(
                        query = ui.findQuery,
                        count = ui.findCount,
                        index = ui.findIndex,
                        onQueryChange = controller::find,
                        onNext = { controller.findNext(true) },
                        onPrevious = { controller.findNext(false) },
                        onClose = {
                            controller.clearFind()
                            showFind = false
                        },
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // One WebView per tab. Switching tabs recreates this holder; the old tab's view is detached
                // (and paused) when its holder leaves the composition.
                key(activeId) {
                    val tabId = activeId
                    DisposableEffect(tabId) { onDispose { controller.detach(tabId) } }
                    AndroidView(
                        factory = { ctx -> controller.attachActive(ctx) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!ui.hasPage) {
                    StartPage(
                        bookmarks = bookmarks,
                        history = history,
                        onOpen = controller::load,
                        onShowHistory = { showHistory = true },
                    )
                }
                val ssl = ui.sslError
                val error = ui.error
                if (ssl != null) {
                    SslOverlay(
                        code = ssl,
                        host = parseUrl(ui.url)?.host ?: ui.url,
                        onBack = controller::sslCancel,
                        onProceed = controller::sslProceed,
                    )
                } else if (error != null) {
                    ErrorOverlay(message = error, url = ui.url, onRetry = controller::reload)
                }
                if (chrome && editing) {
                    SuggestionsList(
                        query = field.text.trim(),
                        suggestions = suggestions,
                        onSearch = { go(field.text) },
                        onOpen = { go(it) },
                    )
                }
            }
        }

        // The floating buttons live directly in the outer Box on purpose: inside the Column above, Kotlin
        // would pick ColumnScope.AnimatedVisibility, which has no Modifier.align(Alignment).
        AnimatedVisibility(
            visible = chrome && ui.showDownload && !showTabs,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            ExtendedFloatingActionButton(
                onClick = { sheetCandidates = controller.candidatesForSheet() },
                icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                text = {
                    val count = ui.detected.size
                    Text(
                        if (count > 1) stringResource(R.string.browser_download) + " · $count"
                        else stringResource(R.string.browser_download)
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = chrome && ui.hasPage && (ui.videoPlaying || ui.speed != 1f) && !showTabs,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SpeedChip(speed = ui.speed, onClick = controller::cycleSpeed)
        }

        // HTML5 fullscreen video: the WebView hands over a view that must cover everything
        fullscreen?.let { custom ->
            key(custom) {
                AndroidView(
                    factory = {
                        (custom.parent as? ViewGroup)?.removeView(custom)
                        custom
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }
        }

        if (showTabs && chrome) {
            TabSwitcher(
                tabs = tabs,
                activeId = activeId,
                onSelect = {
                    controller.switchTo(it)
                    showTabs = false
                },
                onClose = controller::closeTab,
                onNew = {
                    controller.openTab()
                    showTabs = false
                },
                onCloseAll = {
                    controller.closeAllTabs()
                    showTabs = false
                },
                onDismiss = { showTabs = false },
            )
        }
    }

    // ---- sheets and dialogs
    sheetCandidates?.let { candidates ->
        DownloadSheet(
            vm = vm,
            candidates = candidates,
            onDismiss = { sheetCandidates = null },
            onOpenHome = {
                sheetCandidates = null
                onOpenHome()
            },
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onOpen = {
                showBookmarks = false
                controller.load(it)
            },
            onRemove = controller.store::removeBookmark,
            onDismiss = { showBookmarks = false },
        )
    }
    if (showHistory) {
        HistorySheet(
            history = history,
            onOpen = {
                showHistory = false
                controller.load(it)
            },
            onRemove = controller.store::removeHistory,
            onClear = controller.store::clearHistory,
            onDismiss = { showHistory = false },
        )
    }
    contextMenu?.let { request ->
        ContextMenuDialog(
            request = request,
            onAction = { action ->
                when (action) {
                    ContextAction.OPEN_NEW_TAB -> controller.openUrl(request.url, newTab = true)
                    ContextAction.OPEN_BACKGROUND -> {
                        if (controller.openTab(request.url, activate = false) != null) {
                            vm.showMessage(R.string.tab_opened_background)
                        }
                    }
                    ContextAction.COPY_LINK -> {
                        copyText(context, request.url)
                        vm.showMessage(R.string.link_copied)
                    }
                    ContextAction.SHARE_LINK -> shareText(context, request.url)
                    ContextAction.DOWNLOAD_MEDIA -> {
                        sheetCandidates = listOf(controller.linkCandidate(request.url))
                    }
                    ContextAction.DOWNLOAD_IMAGE -> controller.downloadImage(request.imageUrl ?: request.url)
                    ContextAction.COPY_IMAGE -> {
                        copyText(context, request.imageUrl ?: request.url)
                        vm.showMessage(R.string.link_copied)
                    }
                }
            },
            onDismiss = { contextMenu = null },
        )
    }
    fileDownload?.let { request ->
        FileDownloadDialog(
            request = request,
            onConfirm = { controller.downloadFile(request) },
            onDismiss = { fileDownload = null },
        )
    }
}

// ---------------------------------------------------------------------- address bar

/** Everything the three-dot menu can do. */
private class BrowserMenu(
    val onForward: () -> Unit,
    val onHome: () -> Unit,
    val onNewTab: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onBookmarks: () -> Unit,
    val onHistory: () -> Unit,
    val onFind: () -> Unit,
    val onDesktop: () -> Unit,
    val onAnalyze: () -> Unit,
    val onCopyLink: () -> Unit,
    val onShareLink: () -> Unit,
    val onOpenExternal: () -> Unit,
    val onClearData: () -> Unit,
)

@Composable
private fun AddressBar(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    focused: Boolean,
    state: TabState,
    tabCount: Int,
    bookmarked: Boolean,
    onGo: (String) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onTabs: () -> Unit,
    menu: BrowserMenu,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                TextField(
                    value = field,
                    onValueChange = onFieldChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    leadingIcon = {
                        val favicon = state.favicon
                        if (state.hasPage && !focused && favicon != null) {
                            Image(bitmap = favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                when {
                                    !state.hasPage || focused -> Icons.Filled.Search
                                    state.url.startsWith("https://") -> Icons.Filled.Lock
                                    else -> Icons.Filled.Language
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    trailingIcon = {
                        if (focused && field.text.isNotEmpty()) {
                            IconButton(onClick = { onFieldChange(TextFieldValue("")) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo(field.text) }),
                )
                IconButton(onClick = if (state.loading) onStop else onReload, enabled = state.hasPage) {
                    Icon(
                        if (state.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = stringResource(if (state.loading) R.string.stop else R.string.reload),
                    )
                }
                TabCountButton(count = tabCount, onClick = onTabs)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val close = { menuOpen = false }
                        MenuEntry(R.string.menu_new_tab, Icons.Filled.Add, onDismiss = close, onClick = menu.onNewTab)
                        MenuEntry(
                            R.string.menu_forward, Icons.AutoMirrored.Filled.ArrowForward,
                            enabled = state.canGoForward, onDismiss = close, onClick = menu.onForward,
                        )
                        MenuEntry(
                            R.string.menu_home, Icons.Filled.Home,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onHome,
                        )
                        MenuEntry(
                            if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark_add,
                            if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onToggleBookmark,
                        )
                        MenuEntry(R.string.menu_bookmarks, Icons.Filled.Bookmarks, onDismiss = close, onClick = menu.onBookmarks)
                        MenuEntry(R.string.menu_history, Icons.Filled.History, onDismiss = close, onClick = menu.onHistory)
                        MenuEntry(
                            R.string.menu_find, Icons.Filled.FindInPage,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onFind,
                        )
                        MenuEntry(
                            R.string.menu_desktop, Icons.Filled.DesktopWindows,
                            enabled = state.hasPage, checked = state.desktop, onDismiss = close, onClick = menu.onDesktop,
                        )
                        MenuEntry(
                            R.string.menu_analyze_page, Icons.Filled.Download,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onAnalyze,
                        )
                        MenuEntry(
                            R.string.menu_copy_link, Icons.Filled.ContentCopy,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onCopyLink,
                        )
                        MenuEntry(
                            R.string.menu_share_link, Icons.Filled.Share,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onShareLink,
                        )
                        MenuEntry(
                            R.string.menu_open_external, Icons.AutoMirrored.Filled.OpenInNew,
                            enabled = state.hasPage, onDismiss = close, onClick = menu.onOpenExternal,
                        )
                        MenuEntry(R.string.menu_clear_data, Icons.Filled.DeleteSweep, onDismiss = close, onClick = menu.onClearData)
                        if (state.blocked > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_blocked, state.blocked)) },
                                enabled = false,
                                leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                                onClick = {},
                            )
                        }
                    }
                }
            }
            if (state.loading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun MenuEntry(
    textRes: Int,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checked: Boolean = false,
) {
    DropdownMenuItem(
        text = { Text(stringResource(textRes)) },
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else null,
        onClick = {
            onDismiss()
            onClick()
        },
    )
}

/** The square "tabs" button of mobile browsers, showing how many tabs are open. */
@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.tabs_button)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(
            Modifier
                .size(24.dp)
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "99+" else count.toString(),
                fontSize = if (count > 9) 10.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
