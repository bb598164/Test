package com.vidgrab.app.engine

import android.app.Application
import com.vidgrab.app.browser.WebCookies
import com.vidgrab.app.data.SettingsRepository
import java.io.File

/**
 * Decides which cookies.txt yt-dlp gets for a request:
 *  - the file the user imported in Settings (if enabled), and/or
 *  - the cookies of the in-app browser for the site (if enabled) - so logging in once in the Browser tab
 *    is enough to download private / login-only posts of Instagram, Facebook, X ...
 * When both exist they are merged into a temporary file that is deleted after use.
 */
class CookieProvider(private val app: Application, private val settings: SettingsRepository) {

    private val dir = File(app.cacheDir, "webcookies").apply { mkdirs() }

    fun cookieFileFor(urls: List<String>, key: String): String? {
        val s = settings.current
        val manual = File(app.filesDir, DownloadManager.COOKIES_FILE).takeIf { s.useCookies && it.isFile }
        val web = if (s.useBrowserCookies) WebCookies.netscapeFor(urls.filter { it.isNotEmpty() }) else null
        if (web == null) return manual?.absolutePath
        val out = File(dir, "$key.txt")
        val text = buildString {
            append("# Netscape HTTP Cookie File\n")
            manual?.let { file ->
                file.readLines().filterNot { it.startsWith("# Netscape") || it.startsWith("# HTTP Cookie") }
                    .forEach { append(it).append('\n') }
            }
            append(web).append('\n')
        }
        out.writeText(text)
        return out.absolutePath
    }

    fun cleanup(key: String) {
        File(dir, "$key.txt").delete()
    }
}
