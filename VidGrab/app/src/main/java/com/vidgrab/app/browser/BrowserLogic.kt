package com.vidgrab.app.browser

import com.vidgrab.app.core.Bookmark
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.HistoryEntry
import com.vidgrab.app.core.Suggestion
import com.vidgrab.app.core.SuggestionKind
import java.net.URLEncoder
import java.util.Locale

/*
 * Pure functions (no Android classes) used by the in-app browser. They are kept separate so they can be
 * tested on a normal JVM.
 */

private val URL_PARTS =
    Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/?#@]*@)?(\[[^\]]+]|[^/?#:]+)(?::\d+)?([^?#]*)(?:\?([^#]*))?""")
private val ANY_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
private val HOST_PORT = Regex("""^[\w.-]+:\d+""")
private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

data class UrlParts(val scheme: String, val host: String, val path: String, val query: String)

fun parseUrl(url: String): UrlParts? {
    val m = URL_PARTS.find(url.trim()) ?: return null
    return UrlParts(
        scheme = m.groupValues[1].lowercase(Locale.ROOT),
        host = m.groupValues[2].lowercase(Locale.ROOT),
        path = m.groupValues[3].ifEmpty { "/" },
        query = m.groupValues[4],
    )
}

data class SearchEngine(val id: String, val name: String, val template: String)

val SEARCH_ENGINES = listOf(
    SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
    SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s"),
    SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    SearchEngine("yandex", "Yandex", "https://yandex.com/search/?text=%s"),
    SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
    SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
)

const val DEFAULT_SEARCH_TEMPLATE = "https://www.google.com/search?q=%s"

fun searchTemplateById(id: String): String = SEARCH_ENGINES.firstOrNull { it.id == id }?.template ?: DEFAULT_SEARCH_TEMPLATE

fun searchUrl(query: String, template: String = DEFAULT_SEARCH_TEMPLATE): String =
    template.replace("%s", URLEncoder.encode(query, "UTF-8"))

/** What the user typed in the address bar -> a loadable http(s) URL, or a web search. */
fun normalizeInput(input: String, searchTemplate: String = DEFAULT_SEARCH_TEMPLATE): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    val lower = t.lowercase(Locale.ROOT)
    if (lower.startsWith("http://") || lower.startsWith("https://")) return t
    // javascript:, file:, intent:, data: ... are never loaded - they become a search
    if (ANY_SCHEME.containsMatchIn(t) && !HOST_PORT.containsMatchIn(t) && !lower.startsWith("localhost")) {
        return searchUrl(t, searchTemplate)
    }
    val looksLikeHost = !t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))
    if (!looksLikeHost) return searchUrl(t, searchTemplate)
    // devices on the local network / localhost rarely have a certificate: plain http
    val hostPart = lower.substringBefore('/').substringBefore('?').substringBefore(':')
    return if (isIpAddress(hostPart) || hostPart == "localhost") "http://$t" else "https://$t"
}

fun stripFragment(url: String): String = url.substringBefore('#')

fun displayUrl(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

fun isIpAddress(host: String): Boolean = IPV4.matches(host) || host.startsWith("[") || host.contains(':')

/** news.example.co.uk -> example.co.uk ; m.facebook.com -> facebook.com (good enough without a public-suffix list) */
fun registrableDomain(host: String): String {
    val h = host.lowercase(Locale.ROOT).trim('.')
    if (h.isEmpty() || isIpAddress(h) || !h.contains('.')) return h
    val parts = h.split('.')
    if (parts.size <= 2) return h
    val tld = parts.last()
    val sld = parts[parts.size - 2]
    val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")
    val take = if (tld.length == 2 && sld in secondLevel) 3 else 2
    return parts.takeLast(take).joinToString(".")
}

/**
 * Does this page address look like ONE video? On big sites the home page / feed also plays previews,
 * and yt-dlp cannot download a feed, so those pages must not trigger the "download" suggestion.
 * Unknown sites are always accepted (yt-dlp's generic extractor can find <video> tags).
 */
fun isVideoPageUrl(url: String): Boolean {
    val p = parseUrl(url) ?: return false
    if (p.scheme != "http" && p.scheme != "https") return false
    val path = p.path.lowercase(Locale.ROOT)
    return when (registrableDomain(p.host)) {
        "youtube.com" -> path.startsWith("/watch") || path.startsWith("/shorts/") || path.startsWith("/live/") ||
            path.startsWith("/embed/") || path.startsWith("/clip/")
        "youtu.be" -> path.length > 1
        "facebook.com" -> path.startsWith("/watch") || path.contains("/videos/") || path.contains("/reel/") ||
            path.startsWith("/share/v/") || path.startsWith("/share/r/") || path.contains("/posts/") ||
            path.contains("/permalink")
        "fb.watch" -> true
        "instagram.com" -> path.startsWith("/p/") || path.startsWith("/reel/") || path.startsWith("/reels/") ||
            path.startsWith("/tv/") || path.startsWith("/stories/") || path.contains("/p/") || path.contains("/reel/")
        "x.com", "twitter.com" -> path.contains("/status/")
        "tiktok.com" -> path.contains("/video/") || p.host.startsWith("vm.") || p.host.startsWith("vt.")
        else -> true
    }
}

private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp")
private val AUDIO_EXT = setOf("mp3", "m4a", "ogg", "oga", "opus", "flac", "wav")
private val SEGMENT = Regex("""(?i)(^|[/_.-])(seg|segment|chunk|frag|fragment)[-_]?\d+""")

/** Media type of a sniffed network request, or null when it is not a (whole) media file / manifest. */
fun classifyMediaUrl(url: String): DetectedKind? {
    val p = parseUrl(url) ?: return null
    if (p.scheme != "http" && p.scheme != "https") return null
    val path = p.path.lowercase(Locale.ROOT)
    val file = path.substringAfterLast('/')
    if (SEGMENT.containsMatchIn(path) || file.startsWith("init.") || file.startsWith("init-")) return null
    return when (file.substringAfterLast('.', "")) {
        "m3u8" -> DetectedKind.HLS
        "mpd" -> DetectedKind.DASH
        in AUDIO_EXT -> DetectedKind.AUDIO
        in VIDEO_EXT -> DetectedKind.DIRECT
        else -> null
    }
}

private val AD_HOSTS = listOf(
    "doubleclick", "googlesyndication", "googleadservices", "adservice", "adsystem", "imasdk", "moatads",
    "adnxs", "scorecardresearch", "taboola", "outbrain", "criteo", "adsrvr", "smartadserver", "2mdn.net",
    "pubmatic", "rubiconproject", "teads.tv", "innovid",
)

fun looksLikeAd(url: String): Boolean {
    val p = parseUrl(url) ?: return false
    if (AD_HOSTS.any { p.host.contains(it) }) return true
    val path = p.path.lowercase(Locale.ROOT)
    return path.contains("/ads/") || path.contains("/adserver") || path.contains("/preroll")
}

/** Two sniffed URLs with the same host + path are the same file (only the token in the query changes). */
fun candidateKey(kind: DetectedKind, url: String): String = when (kind) {
    DetectedKind.PAGE, DetectedKind.FRAME -> "P|" + stripFragment(url)
    else -> parseUrl(url)?.let { "D|${it.host}${it.path}" } ?: "D|$url"
}

/**
 * Cookie header ("a=1; b=2") of one site -> lines of a Netscape cookies.txt, the format yt-dlp reads.
 * Format: domain, include-subdomains, path, secure, expiry, name, value (tab separated).
 */
fun buildNetscapeLines(host: String, secure: Boolean, cookieHeader: String): List<String> {
    val h = host.lowercase(Locale.ROOT).trim('.')
    if (h.isEmpty()) return emptyList()
    val plainHost = isIpAddress(h) || h == "localhost"
    val domain = if (plainHost) h else "." + registrableDomain(h)
    val includeSubdomains = if (plainHost) "FALSE" else "TRUE"
    val secureFlag = if (secure) "TRUE" else "FALSE"
    return cookieHeader.split(';').mapNotNull { part ->
        val t = part.trim()
        val eq = t.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = t.substring(0, eq).trim()
        val value = t.substring(eq + 1).trim()
        val bad = { s: String -> s.any { it == '\t' || it == '\n' || it == '\r' } }
        if (name.isEmpty() || bad(name) || bad(value)) null
        else "$domain\t$includeSubdomains\t/\t$secureFlag\t2147483647\t$name\t$value"
    }
}

/** Two addresses that differ only by scheme, "www.", a trailing slash or a #fragment are the same page. */
fun compareKey(url: String): String = displayUrl(stripFragment(url)).lowercase(Locale.ROOT)

fun isSameUrl(a: String, b: String): Boolean = compareKey(a) == compareKey(b)

/** Address-bar suggestions from the user's bookmarks and history (best matches first, no duplicates). */
fun buildSuggestions(
    query: String,
    bookmarks: List<Bookmark>,
    history: List<HistoryEntry>,
    limit: Int = 6,
): List<Suggestion> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<Suggestion>()
    val seen = HashSet<String>()
    fun add(url: String, title: String, kind: SuggestionKind) {
        if (out.size < limit && seen.add(compareKey(url))) out += Suggestion(url, title, kind)
    }
    fun matches(url: String, title: String) =
        displayUrl(url).lowercase(Locale.ROOT).contains(q) || title.lowercase(Locale.ROOT).contains(q)
    fun startsWith(url: String, title: String) =
        displayUrl(url).lowercase(Locale.ROOT).startsWith(q) || title.lowercase(Locale.ROOT).startsWith(q)
    bookmarks.filter { matches(it.url, it.title) }.sortedByDescending { startsWith(it.url, it.title) }
        .forEach { add(it.url, it.title, SuggestionKind.BOOKMARK) }
    history.filter { matches(it.url, it.title) }.sortedByDescending { startsWith(it.url, it.title) }
        .forEach { add(it.url, it.title, SuggestionKind.HISTORY) }
    return out
}

private val CHROME_VERSION = Regex("""Chrome/([\d.]+)""")

/** The WebView adds "; wv" to its user agent; without it sites treat the browser like Chrome for Android. */
fun mobileUserAgent(webViewAgent: String): String = webViewAgent.replace("; wv", "")

/** A desktop Chrome user agent with the same Chrome version as the device's WebView. */
fun desktopUserAgent(webViewAgent: String): String {
    val version = CHROME_VERSION.find(webViewAgent)?.groupValues?.get(1) ?: "124.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
}

/**
 * A small, careful list of advertising / tracking / pop-under networks. Hosts are matched by whole labels
 * (so "pagead2.googlesyndication.com" is blocked, "notgooglesyndication.com" is not). Video-player
 * infrastructure that sites need to work (IMA SDK, Google Tag Manager, social login SDKs) is NOT listed.
 */
object AdBlocker {
    private val DOMAINS: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "adservice.google.com", "2mdn.net", "adnxs.com", "adsrvr.org", "advertising.com", "adform.net",
        "adition.com", "adblade.com", "adcolony.com", "adcash.com", "admob.com", "adroll.com",
        "adsafeprotected.com", "adtechus.com", "amazon-adsystem.com", "appnexus.com", "bidswitch.net",
        "bluekai.com", "casalemedia.com", "contextweb.com", "conversantmedia.com", "criteo.com", "criteo.net",
        "demdex.net", "districtm.io", "exoclick.com", "exosrv.com", "gumgum.com", "indexww.com",
        "inmobi.com", "innovid.com", "juicyads.com", "lijit.com", "liveintent.com", "lkqd.net",
        "mathtag.com", "media.net", "mediamath.com", "mgid.com", "moatads.com", "mookie1.com",
        "onclickads.net", "openx.net", "outbrain.com", "popads.net", "popcash.net", "propellerads.com",
        "pubmatic.com", "quantserve.com", "revcontent.com", "rfihub.com", "rubiconproject.com",
        "scorecardresearch.com", "serving-sys.com", "sharethrough.com", "smartadserver.com",
        "spotxchange.com", "springserve.com", "taboola.com", "teads.tv", "tapad.com", "tidaltv.com",
        "trafficjunky.net", "trafficfactory.biz", "tremorhub.com", "turn.com", "undertone.com",
        "yieldmo.com", "zedo.com", "zemanta.com", "adsterra.com", "hilltopads.net", "clickadu.com",
        "richads.com", "evadav.com", "a-ads.com", "ad-maven.com", "admaven.com", "monetag.com",
        "plugrush.com", "ero-advertising.com", "bidvertiser.com", "adskeeper.com",
        "adspyglass.com", "pushnami.com", "yllix.com", "popmyads.com",
        "advertserve.com", "smaato.net", "mopub.com", "applovin.com",
        "ads.yahoo.com", "advertising.yahoo.com",
        "ads.twitter.com", "ads-twitter.com", "static.ads-twitter.com", "analytics.twitter.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "ads.pinterest.com", "ads.tiktok.com",
        "analytics.tiktok.com", "hotjar.com", "mixpanel.com", "segment.io", "fullstory.com",
        "crazyegg.com", "mouseflow.com", "chartbeat.com",
    )

    fun isBlockedHost(host: String): Boolean {
        var h = host.lowercase(Locale.ROOT).trim('.')
        while (h.contains('.')) {
            if (h in DOMAINS) return true
            h = h.substringAfter('.')
        }
        return false
    }

    fun isBlockedUrl(url: String): Boolean = parseUrl(url)?.let { isBlockedHost(it.host) } ?: false
}
