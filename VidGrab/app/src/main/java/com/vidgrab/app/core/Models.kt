package com.vidgrab.app.core

import androidx.annotation.StringRes

enum class MediaKind { VIDEO, AUDIO }

enum class DownloadStatus { QUEUED, DOWNLOADING, PROCESSING, PAUSED, COMPLETED, FAILED, CANCELLED }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the in-app browser found on a page. */
enum class DetectedKind { PAGE, FRAME, DIRECT, AUDIO, HLS, DASH }

data class DetectedMedia(
    val id: Long,
    val kind: DetectedKind,
    val url: String,
    val pageUrl: String,
    val title: String,
    /** only for direct files / streams: the page they were loaded from (many CDNs check it) */
    val referer: String? = null,
    val userAgent: String? = null,
    val durationSec: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val poster: String? = null,
)

/** One row of the download list (persisted in downloads.json). */
data class DownloadItem(
    val id: Long,
    val url: String,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = 0,
    val kind: MediaKind = MediaKind.VIDEO,
    /** 0 = best available */
    val maxHeight: Int = 0,
    val audioFormat: String = "mp3",
    val audioBitrate: Int = 192,
    /** "best", "1080p", "MP3 · 192k" ... */
    val qualityLabel: String = "best",
    val expectedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    val outputUri: String? = null,
    val outputName: String? = null,
    val outputSize: Long = 0,
    val referer: String? = null,
    val userAgent: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PROCESSING
}

/** What the UI hands to the download manager. */
data class NewDownload(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val kind: MediaKind,
    val maxHeight: Int,
    val audioFormat: String,
    val audioBitrate: Int,
    val qualityLabel: String,
    val expectedBytes: Long,
    val referer: String? = null,
    val userAgent: String? = null,
)

data class MediaEntry(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val durationSec: Long,
    val uploader: String?,
)

/** height 0 = "best available" */
data class QualityOption(val height: Int, val sizeBytes: Long)

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val isPlaylist: Boolean,
    val entries: List<MediaEntry>,
    val qualities: List<QualityOption>,
    val audioSizeBytes: Long,
    val extractor: String,
    /** set when a single YouTube video URL also carries a &list= parameter */
    val playlistUrl: String? = null,
)

data class Bookmark(val url: String, val title: String, val addedAt: Long)

data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

enum class SuggestionKind { BOOKMARK, HISTORY }

data class Suggestion(val url: String, val title: String, val kind: SuggestionKind)

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "system", // "system" | "ar" | "en"
    val dynamicColor: Boolean = false,
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val connections: Int = 4,
    val speedLimitKb: Int = 0, // 0 = unlimited
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = false,
    val embedSubs: Boolean = false,
    val subLangs: String = "ar,en",
    val autoUpdate: Boolean = true,
    val updateChannel: String = "stable", // "stable" | "nightly"
    val useCookies: Boolean = false,
    /** send the cookies of the in-app browser (not YouTube/Google) with downloads */
    val useBrowserCookies: Boolean = true,
    /** id of a search engine, see SEARCH_ENGINES */
    val searchEngine: String = "google",
    /** blocks well-known ad / tracker / pop-under hosts in the browser */
    val blockAds: Boolean = true,
    /** page text size of the browser in percent */
    val pageTextZoom: Int = 100,
    val customFolderUri: String? = null,
    val lastUpdateCheck: Long = 0L,
)

/** A message that is resolved to text in the UI layer (so it follows the in-app language). */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}
