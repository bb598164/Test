package com.vidgrab.app.browser

import android.graphics.Bitmap
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.DetectedMedia

/** Everything the UI needs to know about one tab. */
data class TabState(
    val id: Long = 0,
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    /** false = show the start page instead of a web page */
    val hasPage: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val detected: List<DetectedMedia> = emptyList(),
    val videoPlaying: Boolean = false,
    val favicon: Bitmap? = null,
    /** the page failed to load: the system's description, or ERROR_SSL_REJECTED */
    val error: String? = null,
    /** certificate problem waiting for the user's decision: untrusted / expired / mismatch / notyetvalid / other */
    val sslError: String? = null,
    val desktop: Boolean = false,
    val speed: Float = 1f,
    val findQuery: String = "",
    val findCount: Int = 0,
    val findIndex: Int = 0,
    /** ads / trackers blocked on the current page */
    val blocked: Int = 0,
) {
    /** The floating download button: shown as soon as a video plays (or a stream manifest is loading). */
    val showDownload: Boolean
        get() = videoPlaying || detected.any { it.kind == DetectedKind.HLS || it.kind == DetectedKind.DASH }
}

/** Light-weight description of a tab for the tab switcher. */
data class TabInfo(
    val id: Long,
    val title: String,
    val url: String,
    val favicon: Bitmap?,
    val loading: Boolean,
)

enum class ContextKind { LINK, IMAGE }

/** Long press on a link / image. For a link wrapping an image, [imageUrl] is the image. */
data class ContextMenuRequest(val kind: ContextKind, val url: String, val imageUrl: String? = null)

/** A file (not a video / audio) the page wants to download. */
data class FileDownloadRequest(
    val url: String,
    val userAgent: String,
    /** empty when the server did not say */
    val mime: String,
    val length: Long,
    val fileName: String,
    val referer: String,
)

const val ERROR_SSL_REJECTED = "ssl-rejected"
