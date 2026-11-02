package com.vidgrab.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.R
import com.vidgrab.app.VidGrabApp
import com.vidgrab.app.browser.BrowserController
import com.vidgrab.app.core.DetectedKind
import com.vidgrab.app.core.DetectedMedia
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.MediaInfo
import com.vidgrab.app.core.MediaKind
import com.vidgrab.app.core.NewDownload
import com.vidgrab.app.core.Settings
import com.vidgrab.app.core.ThemeMode
import com.vidgrab.app.core.UiText
import com.vidgrab.app.core.extractUrl
import com.vidgrab.app.core.readClipboardText
import com.vidgrab.app.core.youtubeListInfo
import com.vidgrab.app.engine.DownloadManager
import com.vidgrab.app.engine.FileActions
import com.vidgrab.app.engine.PyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeState(
    val input: String = "",
    val analyzing: Boolean = false,
    val error: UiText? = null,
    val errorKind: String = "",
    val info: MediaInfo? = null,
    val mode: MediaKind = MediaKind.VIDEO,
    /** 0 = best available */
    val height: Int = 0,
    val audioFormat: String = "mp3",
    val audioBitrate: Int = 192,
    val selected: Set<Int> = emptySet(),
    val clipboardUrl: String? = null,
    /** set when the link came from the in-app browser as a direct file / stream */
    val referer: String? = null,
    val userAgent: String? = null,
    val titleOverride: String? = null,
)

sealed interface UiEvent {
    data class Message(val text: UiText) : UiEvent
    data class Queued(val count: Int) : UiEvent
    data object OpenBrowserTab : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val vg = application as VidGrabApp

    val settings: StateFlow<Settings> = vg.settings.state
    val downloads: StateFlow<List<DownloadItem>> = vg.downloads.items
    val engineState: StateFlow<PyEngine.State> = vg.engine.state
    val browser: BrowserController = vg.browser

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    val updating = MutableStateFlow(false)

    /** true while the Activity is shown in the small picture-in-picture window */
    val inPictureInPicture = MutableStateFlow(false)
    val cookiesPresent = MutableStateFlow(cookiesFile().isFile)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var analyzeJob: Job? = null
    private var lastDismissedClip: String? = null

    // ------------------------------------------------------------------ home: input

    fun onInputChange(text: String) = _home.update { it.copy(input = text, error = null) }

    fun clearInput() {
        analyzeJob?.cancel()
        _home.update { HomeState() }
    }

    fun pasteFromClipboard() {
        val url = extractUrl(readClipboardText(app))
        if (url == null) {
            post(UiText.Res(R.string.clipboard_no_link))
            return
        }
        _home.update { it.copy(input = url, error = null) }
        analyze()
    }

    /** A link that was shared to the app from YouTube / Facebook / Instagram ... */
    fun onIncomingText(text: String) {
        val url = extractUrl(text)
        if (url == null) {
            post(UiText.Res(R.string.clipboard_no_link))
            return
        }
        _home.update { it.copy(input = url, error = null) }
        analyze()
    }

    /** Called when the app window gets focus: offer to use a link found in the clipboard. */
    fun onClipboardChecked(text: String?) {
        val url = extractUrl(text) ?: return
        val s = _home.value
        if (url == s.input || url == lastDismissedClip || s.analyzing) return
        _home.update { it.copy(clipboardUrl = url) }
    }

    fun acceptClipboard() {
        val url = _home.value.clipboardUrl ?: return
        _home.update { it.copy(input = url, clipboardUrl = null) }
        analyze()
    }

    fun dismissClipboard() {
        lastDismissedClip = _home.value.clipboardUrl
        _home.update { it.copy(clipboardUrl = null) }
    }

    // ------------------------------------------------------------------ analyze (Home tab and Browser sheet)

    /** Analyze the link typed / pasted in the Home tab. */
    fun analyze() {
        val url = extractUrl(_home.value.input)
        if (url == null) {
            _home.update { it.copy(error = UiText.Res(R.string.invalid_link), errorKind = "", info = null) }
            return
        }
        analyzeUrl(url, referer = null, userAgent = null, titleOverride = null)
    }

    /** Analyze something the in-app browser found. Direct files carry the page they came from. */
    fun analyzeCandidate(candidate: DetectedMedia) {
        val direct = candidate.kind != DetectedKind.PAGE && candidate.kind != DetectedKind.FRAME
        analyzeUrl(
            url = candidate.url,
            referer = if (direct) candidate.referer else null,
            userAgent = if (direct) candidate.userAgent else null,
            titleOverride = if (direct) candidate.title.ifEmpty { null } else null,
        )
    }

    private fun analyzeUrl(url: String, referer: String?, userAgent: String?, titleOverride: String?) {
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _home.update {
                it.copy(
                    input = url, analyzing = true, error = null, errorKind = "", info = null, clipboardUrl = null,
                    referer = referer, userAgent = userAgent, titleOverride = titleOverride,
                )
            }
            val cookieKey = "analyze"
            try {
                val list = youtubeListInfo(url)
                val cookieFile = withContext(Dispatchers.IO) {
                    vg.cookies.cookieFileFor(listOf(url, referer ?: ""), cookieKey)
                }
                var info = vg.engine.fetchInfo(url, list.singleVideoInList, cookieFile, referer, userAgent)
                if (list.singleVideoInList) info = info.copy(playlistUrl = list.playlistUrl)
                _home.update {
                    it.copy(
                        analyzing = false,
                        info = info,
                        mode = MediaKind.VIDEO,
                        height = defaultHeight(info),
                        audioFormat = "mp3",
                        audioBitrate = 192,
                        selected = info.entries.indices.toSet(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PyEngine.EngineException) {
                _home.update { it.copy(analyzing = false, error = UiText.Raw(e.message ?: ""), errorKind = e.kind) }
            } catch (e: Exception) {
                _home.update {
                    it.copy(analyzing = false, error = UiText.Raw(e.message ?: e.javaClass.simpleName), errorKind = "other")
                }
            } finally {
                vg.cookies.cleanup(cookieKey)
            }
        }
    }

    private fun defaultHeight(info: MediaInfo): Int {
        if (info.isPlaylist) return 720
        val heights = info.qualities.map { it.height }.filter { it > 0 } // sorted, highest first
        return heights.firstOrNull { it <= 1080 } ?: 0
    }

    fun setMode(mode: MediaKind) = _home.update { it.copy(mode = mode) }
    fun setHeight(height: Int) = _home.update { it.copy(height = height) }
    fun setAudioFormat(format: String) = _home.update { it.copy(audioFormat = format) }
    fun setAudioBitrate(kbps: Int) = _home.update { it.copy(audioBitrate = kbps) }

    fun toggleEntry(index: Int) = _home.update {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    fun toggleAll() = _home.update { s ->
        val total = s.info?.entries?.size ?: 0
        s.copy(selected = if (s.selected.size == total) emptySet() else (0 until total).toSet())
    }

    fun loadWholePlaylist() {
        val url = _home.value.info?.playlistUrl ?: return
        _home.update { it.copy(input = url) }
        analyze()
    }

    fun startDownload() {
        val s = _home.value
        val info = s.info ?: return
        val chosen = info.entries.filterIndexed { index, _ -> index in s.selected }
        if (chosen.isEmpty()) {
            post(UiText.Res(R.string.nothing_selected))
            return
        }
        val audio = s.mode == MediaKind.AUDIO
        val label = when {
            audio && s.audioFormat == "opus" -> "OPUS"
            audio -> "${s.audioFormat.uppercase()} · ${s.audioBitrate}k"
            s.height == 0 -> "best"
            else -> "${s.height}p"
        }
        val expected = if (info.isPlaylist) 0L
        else if (audio) info.audioSizeBytes
        else info.qualities.firstOrNull { it.height == s.height }?.sizeBytes ?: 0L

        vg.downloads.enqueue(
            chosen.map { e ->
                NewDownload(
                    url = e.url,
                    // a direct file's own name is usually "master" / "video": the page title is better
                    title = if (!info.isPlaylist && !s.titleOverride.isNullOrEmpty()) s.titleOverride else e.title,
                    uploader = e.uploader,
                    thumbnail = e.thumbnail,
                    durationSec = e.durationSec,
                    kind = s.mode,
                    maxHeight = if (audio) 0 else s.height,
                    audioFormat = s.audioFormat,
                    audioBitrate = s.audioBitrate,
                    qualityLabel = label,
                    expectedBytes = expected,
                    referer = s.referer,
                    userAgent = s.userAgent,
                )
            }
        )
        _events.tryEmit(UiEvent.Queued(chosen.size))
    }

    // ------------------------------------------------------------------ browser

    /** Open a site in the Browser tab (e.g. to log in when a download needs it). */
    fun setInPictureInPicture(value: Boolean) {
        inPictureInPicture.value = value
    }

    fun openInBrowser(url: String, newTab: Boolean = false) {
        browser.openUrl(url, newTab)
        _events.tryEmit(UiEvent.OpenBrowserTab)
    }

    fun clearBrowsingData() {
        browser.clearBrowsingData()
        showMessage(R.string.browsing_cleared)
    }

    fun showMessage(@StringRes id: Int) = post(UiText.Res(id))

    // ------------------------------------------------------------------ downloads list

    fun pause(id: Long) = vg.downloads.pause(id)
    fun resume(id: Long) = vg.downloads.resume(id)
    fun cancel(id: Long) = vg.downloads.cancel(id)
    fun remove(id: Long, deleteFile: Boolean) = vg.downloads.remove(id, deleteFile)
    fun pauseAll() = vg.downloads.pauseAll()
    fun resumeAll() = vg.downloads.resumeAll()
    fun clearCompleted() = vg.downloads.clearCompleted()

    fun openFile(context: Context, item: DownloadItem) {
        if (!FileActions.open(context, item)) post(UiText.Res(R.string.cannot_open_file))
    }

    fun shareFile(context: Context, item: DownloadItem) {
        if (!FileActions.share(context, item)) post(UiText.Res(R.string.cannot_open_file))
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (Settings) -> Settings) = vg.settings.update(transform)

    fun setTheme(mode: ThemeMode) {
        vg.settings.update { it.copy(theme = mode) }
        VidGrabApp.applyTheme(mode)
    }

    fun setLanguage(code: String) {
        vg.settings.update { it.copy(language = code) }
        VidGrabApp.applyLanguage(code)
    }

    fun setCustomFolder(uri: Uri?) {
        if (uri != null) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // the folder is still usable during this session
            }
        }
        vg.settings.update { it.copy(customFolderUri = uri?.toString()) }
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                // a Netscape cookies.txt has tab separated columns
                if (bytes.isEmpty() || !String(bytes, Charsets.UTF_8).contains('\t')) {
                    post(UiText.Res(R.string.cookies_invalid))
                    return@launch
                }
                cookiesFile().writeBytes(bytes)
                cookiesPresent.value = true
                vg.settings.update { it.copy(useCookies = true) }
                post(UiText.Res(R.string.cookies_imported))
            } catch (e: Exception) {
                post(UiText.Raw(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun clearCookies() {
        cookiesFile().delete()
        cookiesPresent.value = false
        vg.settings.update { it.copy(useCookies = false) }
        post(UiText.Res(R.string.cookies_removed))
    }

    private fun cookiesFile() = File(app.filesDir, DownloadManager.COOKIES_FILE)

    // ------------------------------------------------------------------ engine

    fun retryEngine() = vg.engine.retry()

    fun updateEngine() {
        if (updating.value) return
        viewModelScope.launch {
            updating.value = true
            try {
                val r = vg.engine.update(settings.value.updateChannel)
                when (r.status) {
                    "updated" -> {
                        if (!vg.downloads.hasActive() && vg.engine.reload()) {
                            post(UiText.Res(R.string.update_done, listOf(r.version)))
                        } else {
                            post(UiText.Res(R.string.update_done_restart, listOf(r.version)))
                        }
                    }
                    "uptodate" -> post(UiText.Res(R.string.update_uptodate, listOf(r.version)))
                    else -> post(UiText.Raw(r.message.ifEmpty { "Update failed" }))
                }
            } catch (e: Exception) {
                post(UiText.Raw(e.message ?: e.javaClass.simpleName))
            } finally {
                updating.value = false
            }
        }
    }

    private fun post(text: UiText) {
        _events.tryEmit(UiEvent.Message(text))
    }
}
