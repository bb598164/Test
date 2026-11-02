package com.vidgrab.app.browser

import android.webkit.CookieManager

/** Reads the login cookies of the in-app browser so downloads can reach what the user may see when logged in. */
object WebCookies {

    /** YouTube / Google cookies are never exported: public videos need none, and they identify the Google account. */
    private val EXCLUDED = setOf(
        "youtube.com", "youtu.be", "google.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com",
    )

    /** Netscape cookie lines for the sites of [urls], or null when the browser has no cookies for them. */
    fun netscapeFor(urls: List<String>): String? {
        val manager = try {
            CookieManager.getInstance()
        } catch (t: Throwable) {
            return null
        }
        val lines = LinkedHashSet<String>()
        for (url in urls) {
            val p = parseUrl(url) ?: continue
            if (p.scheme != "http" && p.scheme != "https") continue
            val registrable = registrableDomain(p.host)
            if (registrable in EXCLUDED) continue
            val merged = LinkedHashMap<String, String>()
            for (probe in listOf("https://${p.host}/", "https://$registrable/", "https://www.$registrable/")) {
                val header = try {
                    manager.getCookie(probe)
                } catch (t: Throwable) {
                    null
                } ?: continue
                for (part in header.split(';')) {
                    val t = part.trim()
                    val eq = t.indexOf('=')
                    if (eq > 0) merged[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                }
            }
            if (merged.isEmpty()) continue
            val header = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
            lines += buildNetscapeLines(p.host, true, header)
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }
}
