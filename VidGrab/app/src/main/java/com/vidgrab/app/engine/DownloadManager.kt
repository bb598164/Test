package com.vidgrab.app.engine

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.DownloadStatus
import com.vidgrab.app.core.MediaKind
import com.vidgrab.app.core.NewDownload
import com.vidgrab.app.data.DownloadStore
import com.vidgrab.app.data.SettingsRepository
import com.vidgrab.app.service.DownloadService
import com.vidgrab.app.service.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The download queue.
 *
 *  - keeps the list of downloads (persisted) as a StateFlow for the UI
 *  - starts at most `maxConcurrent` downloads at the same time
 *  - PAUSE  = ask yt-dlp to stop; the partial files stay in the temp folder
 *  - RESUME = start again with the same temp folder, yt-dlp continues the partial files
 */
class DownloadManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val engine: PyEngine,
    private val cookies: CookieProvider,
) {
    data class Summary(
        val active: Int = 0,
        val queued: Int = 0,
        val progress: Float = 0f,
        val speedBps: Long = 0,
    )

    private enum class StopIntent { PAUSE, CANCEL }

    private val store = DownloadStore(File(app.filesDir, "downloads.json"))
    private val saver = FileSaver(app, settings)
    private val tmpRoot = File(app.getExternalFilesDir(null) ?: app.filesDir, "tmp").apply { mkdirs() }

    private val idLock = Any()
    private var nextId = 1L

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    val summary: StateFlow<Summary> = _items
        .map { computeSummary(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, Summary())

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val intents = ConcurrentHashMap<Long, StopIntent>()
    private val removeAfter = ConcurrentHashMap.newKeySet<Long>()
    private val scheduleMutex = Mutex()
    private var persistJob: Job? = null
    private val network = NetworkMonitor(app) { schedule() }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val loaded = store.load().map {
            // the process died while it was downloading: let the user decide when to continue
            if (it.isActive) it.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSec = -1) else it
        }
        synchronized(idLock) { nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1 }
        _items.value = loaded
        network.register()
        scope.launch {
            engine.state.collect { if (it is PyEngine.State.Ready) schedule() }
        }
        scope.launch {
            settings.state.map { it.maxConcurrent to it.wifiOnly }.distinctUntilChanged().collect { schedule() }
        }
    }

    fun hasActive(): Boolean = jobs.isNotEmpty()

    // ------------------------------------------------------------------ commands

    fun enqueue(list: List<NewDownload>) {
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        val created = list.mapIndexed { index, n ->
            val id = synchronized(idLock) { nextId++ }
            DownloadItem(
                id = id,
                url = n.url,
                title = n.title,
                uploader = n.uploader,
                thumbnail = n.thumbnail,
                durationSec = n.durationSec,
                kind = n.kind,
                maxHeight = n.maxHeight,
                audioFormat = n.audioFormat,
                audioBitrate = n.audioBitrate,
                qualityLabel = n.qualityLabel,
                expectedBytes = n.expectedBytes,
                referer = n.referer,
                userAgent = n.userAgent,
                createdAt = now + index,
            )
        }
        _items.update { it + created }
        persistNow()
        schedule()
    }

    fun pause(id: Long) {
        val item = get(id) ?: return
        when (item.status) {
            DownloadStatus.DOWNLOADING -> {
                intents[id] = StopIntent.PAUSE
                mutate(id, true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSec = -1) }
                stopPython(id)
            }
            DownloadStatus.QUEUED -> mutate(id, true) { it.copy(status = DownloadStatus.PAUSED) }
            else -> Unit
        }
    }

    /** Resume a paused download or retry a failed / cancelled one. */
    fun resume(id: Long) {
        val item = get(id) ?: return
        if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.FAILED &&
            item.status != DownloadStatus.CANCELLED
        ) return
        intents.remove(id)
        mutate(id, true) {
            it.copy(
                status = DownloadStatus.QUEUED,
                error = null,
                speedBps = 0,
                etaSec = -1,
                progress = if (it.status == DownloadStatus.CANCELLED) 0f else it.progress,
            )
        }
        schedule()
    }

    fun cancel(id: Long) {
        val item = get(id) ?: return
        if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.CANCELLED) return
        val running = jobs.containsKey(id)
        if (running) intents[id] = StopIntent.CANCEL
        mutate(id, true) { it.copy(status = DownloadStatus.CANCELLED, speedBps = 0, etaSec = -1, error = null) }
        if (running) stopPython(id) else scope.launch(Dispatchers.IO) { cleanTmp(id) }
    }

    fun remove(id: Long, deleteFile: Boolean) {
        val item = get(id) ?: return
        if (jobs.containsKey(id)) {
            removeAfter.add(id)
            cancel(id)
            return
        }
        doRemove(item, deleteFile)
    }

    fun pauseAll() {
        _items.value.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
            .forEach { pause(it.id) }
    }

    fun resumeAll() {
        _items.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) }
    }

    fun clearCompleted() {
        _items.update { list -> list.filterNot { it.status == DownloadStatus.COMPLETED } }
        persistNow()
    }

    // ------------------------------------------------------------------ scheduling

    fun schedule() {
        scope.launch(Dispatchers.Default) {
            scheduleMutex.withLock {
                if (engine.state.value !is PyEngine.State.Ready) return@withLock
                val s = settings.current
                if (s.wifiOnly && !network.isUnmetered()) return@withLock
                var running = jobs.size
                val queued = _items.value
                    .filter { it.status == DownloadStatus.QUEUED && !jobs.containsKey(it.id) }
                    .sortedBy { it.createdAt }
                for (item in queued) {
                    if (running >= s.maxConcurrent) break
                    startJob(item.id)
                    running++
                }
            }
        }
    }

    private fun startJob(id: Long) {
        // status first: the foreground service stops itself when nothing is "active"
        mutate(id, true) { it.copy(status = DownloadStatus.DOWNLOADING, error = null, speedBps = 0, etaSec = -1) }
        ensureService()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                runTask(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "task $id crashed", t)
                fail(id, t.message ?: t.javaClass.simpleName)
            } finally {
                jobs.remove(id)
                intents.remove(id)
                engine.forget(id)
                finishRemoval(id)
                schedule()
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun runTask(id: Long) {
        val item = get(id) ?: return
        val early = intents.remove(id)
        if (early != null) {
            finishStopped(id, early)
            return
        }
        val tmpDir = File(tmpRoot, id.toString()).apply { mkdirs() }
        val options = buildOptions(item, tmpDir)
        val cookieKey = "dl$id"

        val ticker = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                engine.poll(id)?.let { applyLive(id, it) }
            }
        }
        val result = try {
            engine.download(id, item.url, options)
        } finally {
            ticker.cancel()
            cookies.cleanup(cookieKey)
        }
        engine.poll(id)?.let { applyLive(id, it) }

        val intent = intents.remove(id)
        when {
            intent == StopIntent.CANCEL -> finishStopped(id, intent)
            result.ok -> complete(id, item, result.path, tmpDir)
            result.stopped -> finishStopped(id, intent)
            else -> fail(id, result.error ?: "Unknown error")
        }
    }

    private fun buildOptions(item: DownloadItem, tmpDir: File): JSONObject {
        val s = settings.current
        val cookieFile = cookies.cookieFileFor(listOf(item.url, item.referer ?: ""), "dl${item.id}")
        return JSONObject().apply {
            put("tmp_dir", tmpDir.absolutePath)
            put("kind", if (item.kind == MediaKind.AUDIO) "audio" else "video")
            put("max_height", item.maxHeight)
            put("audio_format", item.audioFormat)
            put("audio_bitrate", item.audioBitrate)
            put("expected_bytes", item.expectedBytes)
            put("concurrent", s.connections)
            put("rate_limit", s.speedLimitKb.toLong() * 1024L)
            put("embed_meta", s.embedMetadata)
            put("embed_thumb", s.embedThumbnail)
            put("embed_subs", s.embedSubs)
            put("sub_langs", s.subLangs)
            put("cookies", cookieFile ?: "")
            if (!item.referer.isNullOrEmpty()) put("headers", JSONObject().put("Referer", item.referer))
            if (!item.userAgent.isNullOrEmpty()) put("user_agent", item.userAgent)
        }
    }

    private fun applyLive(id: Long, json: String) {
        val st = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        if (st.length() == 0) return
        val processing = st.optString("phase") == "processing"
        val pct = st.optDouble("pct", 0.0).toFloat().coerceIn(0f, 1f)
        val done = st.optLong("done")
        val total = st.optLong("total")
        val speed = st.optLong("speed")
        val eta = st.optLong("eta", -1)
        mutate(id) { cur ->
            if (!cur.isActive) cur
            else cur.copy(
                status = if (processing) DownloadStatus.PROCESSING else DownloadStatus.DOWNLOADING,
                progress = maxOf(cur.progress, pct),
                downloadedBytes = if (done > 0) done else cur.downloadedBytes,
                totalBytes = if (total > 0) total else cur.totalBytes,
                speedBps = speed,
                etaSec = eta,
            )
        }
        persistSoon()
    }

    // ------------------------------------------------------------------ outcomes

    private fun complete(id: Long, item: DownloadItem, path: String?, tmpDir: File) {
        if (path == null) {
            fail(id, "The engine reported success but returned no file")
            return
        }
        mutate(id) { it.copy(status = DownloadStatus.PROCESSING, progress = 0.995f, speedBps = 0, etaSec = -1) }
        try {
            val saved = saver.save(File(path), item.title)
            mutate(id, true) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    outputUri = saved.uri,
                    outputName = saved.name,
                    outputSize = saved.size,
                    downloadedBytes = saved.size,
                    totalBytes = saved.size,
                    speedBps = 0,
                    etaSec = -1,
                    error = null,
                    finishedAt = System.currentTimeMillis(),
                )
            }
            tmpDir.deleteRecursively()
            get(id)?.let { Notifications.completed(app, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "saving failed", t)
            fail(id, "Could not save the file: ${t.message}")
        }
    }

    private fun fail(id: Long, message: String) {
        mutate(id, true) {
            it.copy(status = DownloadStatus.FAILED, error = message.take(500), speedBps = 0, etaSec = -1)
        }
        get(id)?.let { Notifications.failed(app, it) }
    }

    private fun finishStopped(id: Long, intent: StopIntent?) {
        if (intent == StopIntent.CANCEL) {
            cleanTmp(id)
            mutate(id, true) { it.copy(status = DownloadStatus.CANCELLED, speedBps = 0, etaSec = -1) }
        } else {
            mutate(id, true) { cur ->
                if (cur.isActive) cur.copy(status = DownloadStatus.PAUSED, speedBps = 0, etaSec = -1)
                else cur.copy(speedBps = 0, etaSec = -1) // already PAUSED / QUEUED (resumed again)
            }
        }
    }

    private fun finishRemoval(id: Long) {
        if (removeAfter.remove(id)) get(id)?.let { doRemove(it, false) }
    }

    private fun doRemove(item: DownloadItem, deleteFile: Boolean) {
        _items.update { list -> list.filterNot { it.id == item.id } }
        persistNow()
        scope.launch(Dispatchers.IO) {
            if (deleteFile) item.outputUri?.let { FileActions.delete(app, it) }
            cleanTmp(item.id)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun get(id: Long): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun mutate(id: Long, persist: Boolean = false, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }
        if (persist) persistNow()
    }

    private fun cleanTmp(id: Long) {
        File(tmpRoot, id.toString()).deleteRecursively()
    }

    private fun stopPython(id: Long) {
        scope.launch(Dispatchers.IO) { engine.requestStop(id) }
    }

    private fun ensureService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the foreground service", t)
        }
    }

    private fun computeSummary(list: List<DownloadItem>): Summary {
        val active = list.filter { it.isActive }
        val progress = if (active.isEmpty()) 0f else active.map { it.progress }.average().toFloat()
        return Summary(
            active = active.size,
            queued = list.count { it.status == DownloadStatus.QUEUED },
            progress = progress,
            speedBps = active.sumOf { it.speedBps },
        )
    }

    @Synchronized
    private fun persistSoon() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            store.save(_items.value)
        }
    }

    private fun persistNow() {
        scope.launch(Dispatchers.IO) { store.save(_items.value) }
    }

    companion object {
        const val COOKIES_FILE = "cookies.txt"
        private const val TAG = "DownloadManager"
        private const val POLL_MS = 600L
    }
}
