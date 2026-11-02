package com.vidgrab.app.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.DetectedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Object exposed to the web pages as `window.VidGrabBridge`.
 * It is public on purpose (WebView reflection) and deliberately harmless: it can only *report* a video.
 */
class VidGrabJsBridge(private val onReport: (String) -> Unit) {
    @JavascriptInterface
    fun onMedia(json: String?) {
        if (json != null && json.length < 8000) onReport(json)
    }
}

/**
 * One browser tab = one WebView (created the first time the tab is shown) + its state.
 *
 * Detecting videos (the user taps "download" - nothing is downloaded automatically):
 *  1. a script injected into every frame reports <video>/<audio> elements as soon as they PLAY;
 *  2. the page address itself becomes a candidate (yt-dlp understands YouTube, Facebook, Instagram, X ...);
 *  3. network requests for .mp4 / .m3u8 / .mpd / .mp3 ... files become "direct" candidates.
 */
class BrowserTab(
    val id: Long,
    private val app: Application,
    private val host: Host,
    initialUrl: String?,
    /** the tab that opened this one (window.open / target=_blank): Back closes this tab and returns there */
    val openerId: Long? = null,
) {
    interface Host {
        fun onTabChanged(tab: BrowserTab)
        fun onPageVisited(url: String, title: String)
        fun onFullscreen(tab: BrowserTab, view: View?, callback: WebChromeClient.CustomViewCallback?)
        fun onNewWindow(opener: BrowserTab): BrowserTab?
        fun onCloseRequested(tab: BrowserTab)
        fun onFileDownload(request: FileDownloadRequest)
        fun onContextMenu(request: ContextMenuRequest)
        fun onOpenSheetRequested(tab: BrowserTab)
        fun blockAds(): Boolean
        fun textZoom(): Int
    }

    private val _state = MutableStateFlow(
        TabState(id = id, url = initialUrl ?: "", hasPage = !initialUrl.isNullOrEmpty())
    )
    val state: StateFlow<TabState> = _state.asStateFlow()

    private val contextWrapper = MutableContextWrapper(app)
    private var webViewInstance: WebView? = null
    private var pendingUrl: String? = initialUrl?.takeIf { it.isNotEmpty() }
    private var attached = false
    private var defaultUserAgent = ""
    private var pendingSsl: SslErrorHandler? = null
    private var documentStartSupported = false
    private val ids = AtomicLong(1)

    @Volatile
    private var currentUrl: String = initialUrl ?: ""

    @Volatile
    var userAgent: String = ""
        private set

    /** Created on first use (and on the main thread). A restored tab loads its saved address now. */
    val webView: WebView
        get() {
            webViewInstance?.let { return it }
            val wv = createWebView()
            webViewInstance = wv
            val url = pendingUrl
            pendingUrl = null
            if (url != null) wv.loadUrl(url)
            return wv
        }

    private fun update(transform: (TabState) -> TabState) {
        _state.update(transform)
        host.onTabChanged(this)
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(contextWrapper)
        defaultUserAgent = wv.settings.userAgentString
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // links that open a new window (target=_blank / window.open after a tap) become tabs
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = host.textZoom()
            userAgentString = mobileUserAgent(defaultUserAgent)
            this@BrowserTab.userAgent = userAgentString
        }
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(VidGrabJsBridge { json -> handleMediaReport(json) }, BRIDGE_NAME)
        wv.webViewClient = client
        wv.webChromeClient = chrome
        wv.setDownloadListener { url, ua, _, mimetype, contentLength ->
            handleDownload(url, ua, mimetype, contentLength)
        }
        wv.setFindListener { ordinal, count, _ ->
            update { it.copy(findCount = count, findIndex = if (count == 0) 0 else ordinal + 1) }
        }
        wv.setOnLongClickListener { v -> onLongPress(v as WebView) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, DETECTOR_JS, setOf("*"))
            documentStartSupported = true
        }
        return wv
    }

    // ------------------------------------------------------------------ attach to the UI

    fun attach(context: Context): WebView {
        contextWrapper.baseContext = context
        val wv = webView
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.onResume()
        attached = true
        return wv
    }

    fun detach() {
        attached = false
        webViewInstance?.onPause()
        contextWrapper.baseContext = app
    }

    fun onHostPause() {
        webViewInstance?.onPause()
    }

    fun onHostResume() {
        if (attached) webViewInstance?.onResume()
    }

    fun destroy() {
        pendingSsl?.cancel()
        pendingSsl = null
        webViewInstance?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webViewInstance = null
    }

    // ------------------------------------------------------------------ navigation

    fun load(url: String) {
        pendingUrl = null
        currentUrl = url
        update { it.copy(hasPage = true, url = url, loading = true, progress = 5, error = null, sslError = null) }
        webView.loadUrl(url)
    }

    fun goBack(): Boolean {
        val wv = webViewInstance ?: return false
        if (!wv.canGoBack()) return false
        wv.goBack()
        return true
    }

    fun goForward() {
        webViewInstance?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reload() {
        update { it.copy(error = null, sslError = null) }
        if (webViewInstance == null) {
            val url = state.value.url
            if (url.isNotEmpty()) load(url)
        } else {
            webViewInstance?.reload()
        }
    }

    fun stopLoading() {
        webViewInstance?.stopLoading()
    }

    /** Back to the start page of this tab. */
    fun goHome() {
        pendingUrl = null
        currentUrl = ""
        webViewInstance?.let {
            it.loadUrl("about:blank")
            it.clearHistory()
        }
        update { TabState(id = id) }
    }

    // ------------------------------------------------------------------ page tools

    fun find(query: String) {
        update { it.copy(findQuery = query, findCount = 0, findIndex = 0) }
        val wv = webViewInstance ?: return
        if (query.isEmpty()) wv.clearMatches() else wv.findAllAsync(query)
    }

    fun findNext(forward: Boolean) {
        webViewInstance?.findNext(forward)
    }

    fun clearFind() {
        webViewInstance?.clearMatches()
        update { it.copy(findQuery = "", findCount = 0, findIndex = 0) }
    }

    fun setSpeed(rate: Float) {
        update { it.copy(speed = rate) }
        webViewInstance?.evaluateJavascript("window.__vgSetSpeed && window.__vgSetSpeed($rate);", null)
    }

    fun setDesktop(enabled: Boolean) {
        val wv = webView
        wv.settings.userAgentString =
            if (enabled) desktopUserAgent(defaultUserAgent) else mobileUserAgent(defaultUserAgent)
        userAgent = wv.settings.userAgentString
        update { it.copy(desktop = enabled) }
        wv.reload()
    }

    fun applyTextZoom(percent: Int) {
        webViewInstance?.settings?.textZoom = percent
    }

    /** Cache, history stack and form data of this tab (cookies and storage are cleared globally). */
    fun clearData() {
        webViewInstance?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
        update { it.copy(canGoBack = false, canGoForward = false) }
    }

    fun sslProceed() {
        pendingSsl?.proceed()
        pendingSsl = null
        update { it.copy(sslError = null, loading = true) }
    }

    fun sslCancel() {
        pendingSsl?.cancel()
        pendingSsl = null
        update { it.copy(sslError = null, error = ERROR_SSL_REJECTED, loading = false) }
    }

    /** The current page as a candidate (used when the user asks to download "this page" by hand). */
    fun pageCandidate(): DetectedMedia? {
        val s = _state.value
        if (!s.hasPage || !s.url.startsWith("http")) return null
        return DetectedMedia(id = 0, kind = DetectedKind.PAGE, url = s.url, pageUrl = s.url, title = s.title)
    }

    // ------------------------------------------------------------------ detection

    private fun nextId() = ids.getAndIncrement()

    private fun addCandidates(items: List<DetectedMedia>, playing: Boolean) {
        update { s ->
            val list = s.detected.toMutableList()
            for (n in items) {
                val key = candidateKey(n.kind, n.url)
                val index = list.indexOfFirst { candidateKey(it.kind, it.url) == key }
                if (index >= 0) list[index] = n.copy(id = list[index].id) else list.add(n)
            }
            val pages = list.filter { it.kind == DetectedKind.PAGE || it.kind == DetectedKind.FRAME }
            val files = list.filter { it.kind != DetectedKind.PAGE && it.kind != DetectedKind.FRAME }.takeLast(8)
            s.copy(detected = pages + files, videoPlaying = s.videoPlaying || playing)
        }
    }

    /** A <video>/<audio> started playing (reported by the injected script, from any frame). */
    private fun handleMediaReport(json: String) {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        val src = o.optString("src")
        val frame = o.optString("frame")
        val top = o.optBoolean("top")
        val isAudio = o.optBoolean("audio")
        val duration = o.optLong("duration")
        val page = currentUrl
        val title = _state.value.title.ifEmpty { o.optString("title") }
        val poster = o.optString("poster").ifEmpty { null }
        val width = o.optInt("w")
        val height = o.optInt("h")

        val found = ArrayList<DetectedMedia>()
        // 1) the page itself: yt-dlp knows hundreds of sites and picks the best quality
        if (page.startsWith("http") && isVideoPageUrl(page)) {
            found += DetectedMedia(nextId(), DetectedKind.PAGE, page, page, title, durationSec = duration,
                width = width, height = height, poster = poster)
        }
        // 2) an embedded player (iframe) that has its own address
        if (!top && frame.startsWith("http") && stripFragment(frame) != stripFragment(page) && isVideoPageUrl(frame)) {
            found += DetectedMedia(nextId(), DetectedKind.FRAME, frame, page, title, durationSec = duration,
                width = width, height = height, poster = poster)
        }
        // 3) the file / stream the player is actually using (blob: URLs cannot be downloaded directly)
        if (src.startsWith("http") && !looksLikeAd(src)) {
            val kind = classifyMediaUrl(src) ?: if (isAudio) DetectedKind.AUDIO else DetectedKind.DIRECT
            found += DetectedMedia(nextId(), kind, src, page, title, referer = page, userAgent = userAgent,
                durationSec = duration, width = width, height = height, poster = poster)
        }
        addCandidates(found, playing = true)
    }

    private fun handleDownload(url: String, ua: String?, mime: String?, length: Long) {
        if (!url.startsWith("http")) return
        val m = (mime ?: "").lowercase(Locale.ROOT)
        val kind = classifyMediaUrl(url) ?: when {
            m.startsWith("audio/") -> DetectedKind.AUDIO
            m.startsWith("video/") -> DetectedKind.DIRECT
            else -> null
        }
        val page = currentUrl
        if (kind != null) {
            // a video / audio file: offer it in the download sheet (quality, audio only, ...)
            addCandidates(
                listOf(DetectedMedia(nextId(), kind, url, page, _state.value.title, referer = page, userAgent = userAgent)),
                playing = true,
            )
            host.onOpenSheetRequested(this)
        } else {
            // any other file (pdf, zip, apk ...): handled like a normal browser download
            host.onFileDownload(
                FileDownloadRequest(url, ua ?: userAgent, mime.orEmpty(), length, URLUtil.guessFileName(url, null, mime), page)
            )
        }
    }

    // ------------------------------------------------------------------ long press on links / images

    private fun onLongPress(view: WebView): Boolean {
        val type = view.hitTestResult.type
        if (type != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
            type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE &&
            type != WebView.HitTestResult.IMAGE_TYPE
        ) return false
        // the WebView tells us the link and the image under the finger
        val handler = Handler(Looper.getMainLooper()) { msg ->
            val data = msg.data
            val link = data?.getString("url").orEmpty()
            val image = data?.getString("src").orEmpty().takeIf { it.startsWith("http") }
            if (link.startsWith("http")) {
                host.onContextMenu(ContextMenuRequest(ContextKind.LINK, link, image))
            } else if (image != null) {
                host.onContextMenu(ContextMenuRequest(ContextKind.IMAGE, image))
            }
            true
        }
        view.requestFocusNodeHref(handler.obtainMessage())
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun scheduleScan(view: WebView, delayMs: Long) {
        view.postDelayed({ view.evaluateJavascript("window.__vgScan && window.__vgScan();", null) }, delayMs)
    }

    private fun launchExternal(uri: Uri) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // no app can handle it
        }
    }

    /** intent:// links are never launched; only their web fallback address is followed. */
    private fun followIntentFallback(view: WebView, url: String) {
        try {
            val fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            if (fallback != null && fallback.startsWith("http")) view.loadUrl(fallback)
        } catch (e: Exception) {
            // malformed link
        }
    }

    private fun sslCode(error: SslError): String = when (error.primaryError) {
        SslError.SSL_UNTRUSTED -> "untrusted"
        SslError.SSL_EXPIRED -> "expired"
        SslError.SSL_IDMISMATCH -> "mismatch"
        SslError.SSL_NOTYETVALID -> "notyetvalid"
        else -> "other"
    }

    // ------------------------------------------------------------------ WebView clients

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase(Locale.ROOT)) {
                // never navigate the page itself to an ad / pop-under network
                "http", "https" -> request.isForMainFrame && host.blockAds() && AdBlocker.isBlockedHost(uri.host ?: "")
                "about", "blob", "data", "javascript" -> false
                "tel", "mailto", "sms", "smsto", "geo" -> {
                    launchExternal(uri)
                    true
                }
                "intent" -> {
                    followIntentFallback(view, uri.toString())
                    true
                }
                else -> true // market:, app deep links ... are ignored
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
            val blank = url == "about:blank"
            update {
                it.copy(
                    url = if (blank) "" else url,
                    loading = !blank,
                    progress = 5,
                    hasPage = !blank,
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward(),
                    detected = emptyList(),
                    videoPlaying = false,
                    error = null,
                    sslError = null,
                    speed = 1f,
                    blocked = 0,
                    findQuery = "",
                    findCount = 0,
                    findIndex = 0,
                    favicon = null,
                )
            }
            if (!documentStartSupported) view.evaluateJavascript(DETECTOR_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val title = view.title ?: ""
            update {
                it.copy(
                    loading = false,
                    progress = 100,
                    title = title.ifEmpty { it.title },
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward(),
                )
            }
            if (url != "about:blank") host.onPageVisited(url, title)
            if (!documentStartSupported) view.evaluateJavascript(DETECTOR_JS, null)
            scheduleScan(view, 600)
        }

        /** Also fires for in-page navigation (YouTube and other single-page sites never reload). */
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val changed = stripFragment(url) != stripFragment(currentUrl)
            currentUrl = url
            update {
                var next = it.copy(
                    url = if (url == "about:blank") "" else url,
                    canGoBack = view.canGoBack(),
                    canGoForward = view.canGoForward(),
                )
                if (changed) next = next.copy(detected = emptyList(), videoPlaying = false, speed = 1f)
                next
            }
            // single-page sites keep the same <video> element: ask the page to re-report what is playing
            if (changed) scheduleScan(view, 1500)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                val text = error.description?.toString().orEmpty()
                update { it.copy(error = text.ifEmpty { "Error ${error.errorCode}" }, loading = false, progress = 100) }
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            pendingSsl?.cancel()
            pendingSsl = handler
            update { it.copy(sslError = sslCode(error), loading = false) }
        }

        /** Runs on a background thread for every request: blocks ads, spots media files. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            try {
                val uri = request.url
                if (host.blockAds() && AdBlocker.isBlockedHost(uri.host ?: "")) {
                    update { it.copy(blocked = it.blocked + 1) }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (!request.isForMainFrame && request.method == "GET") {
                    val url = uri.toString()
                    val kind = classifyMediaUrl(url)
                    if (kind != null && !looksLikeAd(url)) {
                        val referer = request.requestHeaders["Referer"]?.takeIf { it.startsWith("http") } ?: currentUrl
                        addCandidates(
                            listOf(
                                DetectedMedia(nextId(), kind, url, currentUrl, _state.value.title,
                                    referer = referer, userAgent = userAgent)
                            ),
                            playing = false,
                        )
                    }
                }
            } catch (e: Exception) {
                // detection must never break page loading
            }
            return null
        }
    }

    private val chrome = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            update { it.copy(progress = newProgress, loading = newProgress < 100) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            update { it.copy(title = title ?: "") }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            update { it.copy(favicon = icon) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host.onFullscreen(this@BrowserTab, view, callback)
        }

        override fun onHideCustomView() {
            host.onFullscreen(this@BrowserTab, null, null)
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        /** Only windows opened by a tap become tabs; scripts cannot open pop-ups by themselves. */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val tab = host.onNewWindow(this@BrowserTab) ?: return false
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = tab.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            host.onCloseRequested(this@BrowserTab)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny() // camera / microphone are never granted to web pages
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }
}
