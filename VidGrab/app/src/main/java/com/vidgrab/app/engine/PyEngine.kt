package com.vidgrab.app.engine

import android.app.Application
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.vidgrab.app.core.MediaEntry
import com.vidgrab.app.core.MediaInfo
import com.vidgrab.app.core.QualityOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Starts Python (Chaquopy) + yt-dlp and wraps the JSON functions of `vidgrab_engine.py`.
 *
 * Boot sequence (runs once, in the background):
 *   1. start the Python interpreter
 *   2. unpack the FFmpeg shared libraries that ship inside the APK
 *   3. copy the bundled yt-dlp release and let the Python side install / pick the newest version
 */
class PyEngine(private val app: Application, private val scope: CoroutineScope) {

    enum class Step { PYTHON, FFMPEG, YTDLP }

    data class EngineInfo(val version: String, val jsRuntime: String, val ffmpeg: Boolean)

    sealed interface State {
        data class Starting(val step: Step) : State
        data class Ready(val info: EngineInfo) : State
        data class Failed(val message: String) : State
    }

    class EngineException(message: String, val kind: String = "other") : Exception(message)

    data class DownloadResult(
        val ok: Boolean,
        val stopped: Boolean,
        val path: String?,
        val error: String?,
        val kind: String,
    )

    data class UpdateResult(val status: String, val version: String, val message: String)

    private val _state = MutableStateFlow<State>(State.Starting(Step.PYTHON))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var module: PyObject? = null
    private val startLock = Any()
    private var starting = false

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        synchronized(startLock) {
            if (starting || _state.value is State.Ready) return
            starting = true
        }
        scope.launch(Dispatchers.IO) { boot() }
    }

    fun retry() {
        synchronized(startLock) {
            if (starting) return
        }
        _state.value = State.Starting(Step.PYTHON)
        start()
    }

    private fun boot() {
        try {
            _state.value = State.Starting(Step.PYTHON)
            if (!Python.isStarted()) Python.start(AndroidPlatform(app))
            val py = Python.getInstance()

            _state.value = State.Starting(Step.FFMPEG)
            val ffmpegLibDir = prepareFfmpeg()

            _state.value = State.Starting(Step.YTDLP)
            val bundled = copyBundledYtDlp()
            val nativeDir = File(app.applicationInfo.nativeLibraryDir)
            val ffmpegBin = File(nativeDir, "libffmpeg.so")
            val qjs = File(nativeDir, "libqjs.so")
            val ffmpegOk = ffmpegLibDir != null && ffmpegBin.exists()

            val cfg = JSONObject().apply {
                put("files_dir", File(app.filesDir, "engine").absolutePath)
                put("cache_dir", File(app.cacheDir, "ytdlp").absolutePath)
                put("bundled_zip", bundled.absolutePath)
                put("ffmpeg", if (ffmpegOk) ffmpegBin.absolutePath else "")
                put("ffmpeg_lib", ffmpegLibDir?.absolutePath ?: "")
                put("qjs", if (qjs.exists()) qjs.absolutePath else "")
            }
            val mod = py.getModule("vidgrab_engine")
            val res = JSONObject(mod.callAttr("init", cfg.toString()).toString())
            module = mod
            _state.value = State.Ready(EngineInfo(res.s("version"), res.s("js_runtime"), ffmpegOk))
        } catch (t: Throwable) {
            Log.e(TAG, "engine start failed", t)
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            synchronized(startLock) { starting = false }
        }
    }

    /** Unzips libffmpeg.zip.so (shared libraries of the ffmpeg executable) once per APK version. */
    private fun prepareFfmpeg(): File? {
        val zip = File(app.applicationInfo.nativeLibraryDir, "libffmpeg.zip.so")
        if (!zip.exists()) return null
        val dest = File(app.noBackupFilesDir, "ffmpeg")
        val marker = File(dest, ".stamp")
        val stamp = zip.length().toString()
        val libDir = File(dest, "usr/lib")
        if (libDir.isDirectory && marker.exists() && marker.readText() == stamp) return libDir

        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.startsWith("__MACOSX") && !name.endsWith(".DS_Store")) {
                    val out = File(dest, name)
                    if (!out.canonicalPath.startsWith(destPath)) throw SecurityException("Bad zip entry: $name")
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        marker.writeText(stamp)
        return libDir
    }

    /** Copies assets/yt-dlp (the official zipapp) to private storage; Python unpacks it. */
    private fun copyBundledYtDlp(): File {
        val dir = File(app.filesDir, "engine").apply { mkdirs() }
        val out = File(dir, "yt-dlp-bundled.zip")
        val installedAt = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        if (!out.exists() || out.lastModified() < installedAt) {
            val tmp = File(dir, "yt-dlp-bundled.zip.tmp")
            app.assets.open("yt-dlp").use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            out.delete()
            tmp.renameTo(out)
            out.setLastModified(System.currentTimeMillis())
        }
        return out
    }

    // ------------------------------------------------------------------ API used by the app

    private fun requireModule(): PyObject = module ?: throw EngineException("The download engine is not ready yet")

    suspend fun awaitReady() {
        val s = state.first { it !is State.Starting }
        if (s is State.Failed) throw EngineException(s.message)
    }

    suspend fun fetchInfo(
        url: String,
        noPlaylist: Boolean,
        cookiesPath: String?,
        referer: String? = null,
        userAgent: String? = null,
    ): MediaInfo =
        withContext(Dispatchers.IO) {
            awaitReady()
            val opts = JSONObject().apply {
                put("no_playlist", noPlaylist)
                put("cookies", cookiesPath ?: "")
                if (!referer.isNullOrEmpty()) put("headers", JSONObject().put("Referer", referer))
                if (!userAgent.isNullOrEmpty()) put("user_agent", userAgent)
            }
            val raw = requireModule().callAttr("fetch_info", url, opts.toString()).toString()
            val js = JSONObject(raw)
            if (js.has("error")) throw EngineException(js.s("error"), js.s("kind").ifEmpty { "other" })
            parseInfo(js, url)
        }

    /** Blocking. Returns when the download finished, failed or was stopped. */
    fun download(id: Long, url: String, options: JSONObject): DownloadResult {
        val raw = requireModule().callAttr("download", id.toString(), url, options.toString()).toString()
        val js = JSONObject(raw)
        return DownloadResult(
            ok = js.optBoolean("ok"),
            stopped = js.optBoolean("stopped"),
            path = js.s("path").ifEmpty { null },
            error = js.s("error").ifEmpty { null },
            kind = js.s("kind"),
        )
    }

    fun poll(id: Long): String? = try {
        requireModule().callAttr("get_state", id.toString()).toString()
    } catch (e: Exception) {
        null
    }

    fun requestStop(id: Long) {
        try {
            requireModule().callAttr("request_stop", id.toString())
        } catch (e: Exception) {
            Log.w(TAG, "requestStop failed", e)
        }
    }

    fun forget(id: Long) {
        try {
            requireModule().callAttr("forget", id.toString())
        } catch (e: Exception) {
            // not important
        }
    }

    suspend fun update(channel: String): UpdateResult = withContext(Dispatchers.IO) {
        awaitReady()
        val js = JSONObject(requireModule().callAttr("update", channel).toString())
        UpdateResult(js.s("status"), js.s("version"), js.s("message"))
    }

    /** Activates the newest installed yt-dlp. Call only while no download is running. */
    suspend fun reload(): Boolean = withContext(Dispatchers.IO) {
        val js = JSONObject(requireModule().callAttr("reload_engine").toString())
        if (!js.optBoolean("ok")) return@withContext false
        val old = (state.value as? State.Ready)?.info
        _state.value = State.Ready(
            EngineInfo(js.s("version"), js.s("js_runtime").ifEmpty { old?.jsRuntime ?: "" }, old?.ffmpeg ?: false)
        )
        true
    }

    // ------------------------------------------------------------------ parsing

    private fun parseInfo(js: JSONObject, requestUrl: String): MediaInfo {
        val title = js.s("title").ifEmpty { requestUrl }
        val uploader = js.s("uploader").ifEmpty { null }
        val thumb = js.s("thumbnail").ifEmpty { null }
        val duration = js.optLong("duration")
        val isPlaylist = js.optBoolean("playlist")
        val entries = ArrayList<MediaEntry>()
        val qualities: List<QualityOption>
        var audioSize = 0L

        if (isPlaylist) {
            val arr = js.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                entries += MediaEntry(
                    url = e.s("url"),
                    title = e.s("title").ifEmpty { e.s("url") },
                    thumbnail = e.s("thumbnail").ifEmpty { null },
                    durationSec = e.optLong("duration"),
                    uploader = e.s("uploader").ifEmpty { null },
                )
            }
            qualities = listOf(0, 1080, 720, 480, 360).map { QualityOption(it, 0L) }
        } else {
            val pageUrl = js.s("url").ifEmpty { requestUrl }
            entries += MediaEntry(pageUrl, title, thumb, duration, uploader)
            val built = buildQualities(js.optJSONArray("formats"))
            qualities = built.first
            audioSize = built.second
        }
        return MediaInfo(
            url = js.s("url").ifEmpty { requestUrl },
            title = title,
            uploader = uploader,
            thumbnail = thumb,
            durationSec = duration,
            isPlaylist = isPlaylist,
            entries = entries,
            qualities = qualities,
            audioSizeBytes = audioSize,
            extractor = js.s("extractor"),
        )
    }

    private class Fmt(val height: Int, val size: Long, val video: Boolean, val audio: Boolean)

    /** Available resolutions (with estimated size) + estimated size of the best audio-only stream. */
    private fun buildQualities(arr: JSONArray?): Pair<List<QualityOption>, Long> {
        if (arr == null) return listOf(QualityOption(0, 0L)) to 0L
        val formats = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Fmt(o.optInt("h"), o.optLong("size"), o.optBoolean("v"), o.optBoolean("a"))
        }
        val bestAudio = formats.filter { it.audio && !it.video }.maxOfOrNull { it.size } ?: 0L
        val heights = formats.filter { it.video && it.height > 0 }.map { it.height }.distinct().sortedDescending()
        val options = heights.map { h ->
            val videos = formats.filter { it.video && it.height == h }
            val videoSize = videos.maxOfOrNull { it.size } ?: 0L
            val muxed = videos.any { it.audio }
            val total = if (videoSize > 0) videoSize + (if (muxed) 0L else bestAudio) else 0L
            QualityOption(h, total)
        }
        val best = QualityOption(0, options.firstOrNull()?.sizeBytes ?: 0L)
        return (listOf(best) + options) to bestAudio
    }

    private fun JSONObject.s(name: String): String = if (isNull(name)) "" else optString(name)

    private companion object {
        const val TAG = "PyEngine"
    }
}
