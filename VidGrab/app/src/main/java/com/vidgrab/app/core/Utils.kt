package com.vidgrab.app.core

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** First http(s) link found in arbitrary text (shared messages often contain extra words). */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_REGEX.find(text) ?: return null
    return match.value.trimEnd('.', ',', ';', ')', ']', '}', '!', '?', '،', '؛')
}

data class YoutubeListInfo(val singleVideoInList: Boolean, val playlistUrl: String?)

/** watch?v=ID&list=PL... is treated as ONE video; we then offer a button to load the whole list. */
fun youtubeListInfo(url: String): YoutubeListInfo {
    val uri = try {
        Uri.parse(url)
    } catch (e: Exception) {
        return YoutubeListInfo(false, null)
    }
    val host = (uri.host ?: "").lowercase(Locale.ROOT)
    val isYoutube = host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
    if (!isYoutube) return YoutubeListInfo(false, null)
    val list = uri.getQueryParameter("list")
    val path = uri.path ?: ""
    val hasVideo = uri.getQueryParameter("v") != null || (host == "youtu.be" && path.length > 1)
    if (list.isNullOrEmpty() || !hasVideo) return YoutubeListInfo(false, null)
    val playlistUrl = if (list.startsWith("RD")) null else "https://www.youtube.com/playlist?list=$list"
    return YoutubeListInfo(true, playlistUrl)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    if (index == 0) return "$bytes B"
    val pattern = if (value >= 100) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[index])
}

fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatEta(seconds: Long): String = formatDuration(seconds).ifEmpty { "0:00" }

fun sanitizeFileName(name: String, maxLength: Int = 120): String {
    var s = name
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ' ')
    if (s.length > maxLength) s = s.take(maxLength).trim()
    return s.ifEmpty { "video" }
}

fun mimeForExtension(ext: String): String {
    val e = ext.lowercase(Locale.ROOT)
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)?.let { return it }
    return when (e) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp4", "m4v" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

/** Reads the clipboard; only call this from a user action or while the window has focus. */
fun readClipboardText(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }
}

/**
 * A context whose resources follow the in-app language. Needed for notifications and other places that
 * are not an Activity (the per-app locale of AppCompat is only applied to activities on Android 12-).
 */
fun Context.localized(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val locale = locales[0] ?: return this
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

fun Context.resolve(text: UiText): String = when (text) {
    is UiText.Res -> getString(text.id, *text.args.toTypedArray())
    is UiText.Raw -> text.text
}
