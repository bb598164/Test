package com.vidgrab.app.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import com.vidgrab.app.R
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.DetectedMedia
import com.vidgrab.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The built-in browser: a list of tabs (each with its own WebView, created lazily), bookmarks / history,
 * and everything the screen needs as flows. All methods must be called on the main thread, except
 * the [BrowserTab.Host] callbacks which may arrive from WebView helper threads.
 */
class BrowserController(
    private val app: Application,
    private val settings: SettingsRepository,
    val store: BrowserStore,
) : BrowserTab.Host {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var tabList: List<BrowserTab> = emptyList()
    private var nextTabId = 1L

    private val _tabInfos = MutableStateFlow<List<TabInfo>>(emptyList())
    val tabInfos: StateFlow<List<TabInfo>> = _tabInfos.asStateFlow()

    private val _activeId = MutableStateFlow(0L)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    private val _active = MutableStateFlow(TabState())
    val activeState: StateFlow<TabState> = _active.asStateFlow()

    private val _fullscreen = MutableStateFlow<View?>(null)
    val fullscreenView: StateFlow<View?> = _fullscreen.asStateFlow()
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenTabId = 0L

    private val _openSheet = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The page asked for the download sheet by itself (the user tapped a video file link). */
    val openSheet: SharedFlow<Unit> = _openSheet.asSharedFlow()

    private val _contextMenus = MutableSharedFlow<ContextMenuRequest>(extraBufferCapacity = 4)
    val contextMenus: SharedFlow<ContextMenuRequest> = _contextMenus.asSharedFlow()

    private val _fileDownloads = MutableSharedFlow<FileDownloadRequest>(extraBufferCapacity = 4)
    val fileDownloads: SharedFlow<FileDownloadRequest> = _fileDownloads.asSharedFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** String resource ids of short messages for the user (a snackbar). */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    init {
        restoreTabs()
        scope.launch {
            settings.state.map { it.pageTextZoom }.distinctUntilChanged().collect { zoom ->
                tabList.forEach { it.applyTextZoom(zoom) }
            }
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun activeTab(): BrowserTab? = tabList.firstOrNull { it.id == _activeId.value }

    private fun restoreTabs() {
        val (urls, active) = store.savedTabs()
        if (urls.isEmpty()) {
            openTab()
            return
        }
        tabList = urls.take(MAX_TABS).map { BrowserTab(nextTabId++, app, this, it.ifEmpty { null }) }
        activate(tabList[active.coerceIn(0, tabList.lastIndex)])
    }

    /** @return the new tab, or null when too many tabs are open */
    fun openTab(url: String? = null, activate: Boolean = true, openerId: Long? = null): BrowserTab? {
        if (tabList.size >= MAX_TABS) {
            _messages.tryEmit(R.string.too_many_tabs)
            return null
        }
        val tab = BrowserTab(nextTabId++, app, this, url?.takeIf { it.isNotEmpty() }, openerId)
        tabList = tabList + tab
        if (activate) {
            activate(tab)
        } else {
            refreshInfos()
            saveTabs()
        }
        return tab
    }

    private fun activate(tab: BrowserTab) {
        _activeId.value = tab.id
        _active.value = tab.state.value
        refreshInfos()
        saveTabs()
    }

    fun switchTo(id: Long) {
        tabList.firstOrNull { it.id == id }?.let { if (it.id != _activeId.value) activate(it) }
    }

    fun closeTab(id: Long) {
        val tab = tabList.firstOrNull { it.id == id } ?: return
        val index = tabList.indexOf(tab)
        val wasActive = tab.id == _activeId.value
        if (fullscreenTabId == id) exitFullscreen()
        tabList = tabList - tab
        tab.destroy()
        if (tabList.isEmpty()) {
            openTab()
            return
        }
        if (wasActive) {
            val next = tab.openerId?.let { opener -> tabList.firstOrNull { it.id == opener } }
                ?: tabList[minOf(index, tabList.lastIndex)]
            activate(next)
        } else {
            refreshInfos()
            saveTabs()
        }
    }

    fun closeAllTabs() {
        exitFullscreen()
        tabList.forEach { it.destroy() }
        tabList = emptyList()
        openTab()
    }

    // ------------------------------------------------------------------ hosting the WebView in the UI

    fun attachActive(context: Context): WebView {
        val tab = activeTab() ?: openTab() ?: error("no browser tab")
        return tab.attach(context)
    }

    fun detach(tabId: Long) {
        tabList.firstOrNull { it.id == tabId }?.detach()
    }

    fun onHostPause() {
        activeTab()?.onHostPause()
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // WebView not available
        }
    }

    fun onHostResume() {
        activeTab()?.onHostResume()
    }

    // ------------------------------------------------------------------ navigation

    /** What the user typed in the address bar: an address or search words. */
    fun load(input: String) {
        val url = normalizeInput(input, searchTemplateById(settings.current.searchEngine))
        if (url.isEmpty()) return
        val tab = activeTab() ?: openTab() ?: return
        tab.load(url)
    }

    fun openUrl(url: String, newTab: Boolean) {
        if (newTab) openTab(url) else load(url)
    }

    fun goBack() {
        activeTab()?.goBack()
    }

    fun goForward() {
        activeTab()?.goForward()
    }

    fun reload() {
        activeTab()?.reload()
    }

    fun stopLoading() {
        activeTab()?.stopLoading()
    }

    fun goHome() {
        activeTab()?.goHome()
    }

    /** @return true when the Back press was used by the browser (fullscreen, history, popup tab) */
    fun onBackPressed(): Boolean {
        if (_fullscreen.value != null) {
            exitFullscreen()
            return true
        }
        val tab = activeTab() ?: return false
        if (tab.goBack()) return true
        val opener = tab.openerId
        if (opener != null && tabList.any { it.id == opener }) {
            closeTab(tab.id)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ page tools

    fun toggleDesktop() {
        val tab = activeTab() ?: return
        tab.setDesktop(!tab.state.value.desktop)
    }

    fun cycleSpeed() {
        val tab = activeTab() ?: return
        val index = SPEEDS.indexOf(tab.state.value.speed)
        tab.setSpeed(SPEEDS[(index + 1) % SPEEDS.size])
    }

    fun find(query: String) {
        activeTab()?.find(query)
    }

    fun findNext(forward: Boolean) {
        activeTab()?.findNext(forward)
    }

    fun clearFind() {
        activeTab()?.clearFind()
    }

    fun sslProceed() {
        activeTab()?.sslProceed()
    }

    fun sslCancel() {
        activeTab()?.sslCancel()
    }

    /** Adds / removes the current page in the bookmarks. */
    fun toggleBookmark(): Boolean {
        val s = _active.value
        if (!s.hasPage || s.url.isEmpty()) return false
        val now = store.toggleBookmark(s.url, s.title)
        _messages.tryEmit(if (now) R.string.bookmark_added else R.string.bookmark_removed)
        return now
    }

    // ------------------------------------------------------------------ fullscreen video

    fun isFullscreen(): Boolean = _fullscreen.value != null

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenTabId = 0
        _fullscreen.value = null
    }

    // ------------------------------------------------------------------ downloads

    /** What the download sheet offers for the current page. */
    fun candidatesForSheet(): List<DetectedMedia> {
        val detected = _active.value.detected
        if (detected.any { it.kind == DetectedKind.PAGE }) return detected
        return listOfNotNull(activeTab()?.pageCandidate()) + detected
    }

    /** A link the user pressed: let yt-dlp try to download the video behind it. */
    fun linkCandidate(url: String): DetectedMedia =
        DetectedMedia(id = 0, kind = DetectedKind.PAGE, url = url, pageUrl = url, title = "")

    /** Normal files go to the system download manager (notification, Downloads folder). */
    fun downloadFile(request: FileDownloadRequest) {
        val ok = try {
            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val r = android.app.DownloadManager.Request(Uri.parse(request.url))
            if (request.mime.isNotEmpty()) r.setMimeType(request.mime)
            CookieManager.getInstance().getCookie(request.url)?.let { r.addRequestHeader("Cookie", it) }
            if (request.userAgent.isNotEmpty()) r.addRequestHeader("User-Agent", request.userAgent)
            if (request.referer.startsWith("http")) r.addRequestHeader("Referer", request.referer)
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, request.fileName)
            manager.enqueue(r)
            true
        } catch (e: Exception) {
            false
        }
        _messages.tryEmit(if (ok) R.string.file_download_started else R.string.file_download_failed)
    }

    /** Saves an image (from the long-press menu) to the Downloads folder. */
    fun downloadImage(url: String) {
        val tab = activeTab()
        downloadFile(
            FileDownloadRequest(
                url = url,
                userAgent = tab?.userAgent.orEmpty(),
                mime = "",
                length = -1,
                fileName = URLUtil.guessFileName(url, null, null),
                referer = tab?.state?.value?.url.orEmpty(),
            )
        )
    }

    // ------------------------------------------------------------------ data

    fun clearBrowsingData() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            // WebView not available
        }
        tabList.forEach { it.clearData() }
        store.clearHistory()
    }

    // ------------------------------------------------------------------ BrowserTab.Host

    override fun onTabChanged(tab: BrowserTab) {
        if (tab.id == _activeId.value) _active.value = tab.state.value
        refreshInfos()
    }

    override fun onPageVisited(url: String, title: String) {
        store.addHistory(url, title)
        saveTabs()
    }

    override fun onFullscreen(tab: BrowserTab, view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view != null) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = callback
            fullscreenTabId = tab.id
            _fullscreen.value = view
        } else if (fullscreenTabId == tab.id) {
            fullscreenCallback = null
            fullscreenTabId = 0
            _fullscreen.value = null
        }
    }

    override fun onNewWindow(opener: BrowserTab): BrowserTab? = openTab(null, activate = true, openerId = opener.id)

    override fun onCloseRequested(tab: BrowserTab) {
        closeTab(tab.id)
    }

    override fun onFileDownload(request: FileDownloadRequest) {
        _fileDownloads.tryEmit(request)
    }

    override fun onContextMenu(request: ContextMenuRequest) {
        _contextMenus.tryEmit(request)
    }

    override fun onOpenSheetRequested(tab: BrowserTab) {
        if (tab.id == _activeId.value) _openSheet.tryEmit(Unit)
    }

    override fun blockAds(): Boolean = settings.current.blockAds

    override fun textZoom(): Int = settings.current.pageTextZoom

    // ------------------------------------------------------------------ bookkeeping

    private fun refreshInfos() {
        val infos = tabList.map {
            val s = it.state.value
            TabInfo(it.id, s.title, s.url, s.favicon, s.loading)
        }
        if (infos != _tabInfos.value) _tabInfos.value = infos
    }

    private fun saveTabs() {
        val urls = tabList.map { it.state.value.url }
        store.saveTabs(urls, tabList.indexOfFirst { it.id == _activeId.value }.coerceAtLeast(0))
    }

    private companion object {
        const val MAX_TABS = 12
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 3f, 0.5f)
    }
}
