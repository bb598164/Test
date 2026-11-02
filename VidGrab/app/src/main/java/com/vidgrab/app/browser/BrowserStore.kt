package com.vidgrab.app.browser

import android.util.Log
import com.vidgrab.app.core.Bookmark
import com.vidgrab.app.core.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bookmarks, history and the open tabs, kept in one small JSON file (written atomically, debounced). */
class BrowserStore(private val file: File, private val scope: CoroutineScope) {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private var tabUrls: List<String> = emptyList()
    private var activeTab = 0
    private var persistJob: Job? = null

    init {
        load()
    }

    // ------------------------------------------------------------------ bookmarks

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { isSameUrl(it.url, url) }

    /** @return true when the page is a bookmark after the call */
    fun toggleBookmark(url: String, title: String): Boolean {
        val list = _bookmarks.value
        val existing = list.firstOrNull { isSameUrl(it.url, url) }
        return if (existing != null) {
            _bookmarks.value = list - existing
            persistSoon()
            false
        } else {
            val name = title.ifBlank { displayUrl(url) }
            _bookmarks.value = listOf(Bookmark(url, name, System.currentTimeMillis())) + list
            persistSoon()
            true
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        _bookmarks.value = _bookmarks.value - bookmark
        persistSoon()
    }

    // ------------------------------------------------------------------ history

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val list = _history.value
        val first = list.firstOrNull()
        _history.value = if (first != null && isSameUrl(first.url, url) && now - first.visitedAt < 30 * 60_000L) {
            listOf(HistoryEntry(url, title.ifBlank { first.title }, now)) + list.drop(1)
        } else {
            (listOf(HistoryEntry(url, title.ifBlank { displayUrl(url) }, now)) + list).take(MAX_HISTORY)
        }
        persistSoon()
    }

    fun removeHistory(entry: HistoryEntry) {
        _history.value = _history.value - entry
        persistSoon()
    }

    fun clearHistory() {
        _history.value = emptyList()
        persistSoon()
    }

    // ------------------------------------------------------------------ open tabs

    /** Open tabs (empty address = start page) and the index of the active one. */
    fun saveTabs(urls: List<String>, active: Int) {
        tabUrls = urls
        activeTab = active
        persistSoon()
    }

    fun savedTabs(): Pair<List<String>, Int> = tabUrls to activeTab

    // ------------------------------------------------------------------ persistence

    @Synchronized
    private fun persistSoon() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            write()
        }
    }

    @Synchronized
    private fun write() {
        try {
            val root = JSONObject()
            root.put("bookmarks", JSONArray().also { arr ->
                _bookmarks.value.forEach {
                    arr.put(JSONObject().put("url", it.url).put("title", it.title).put("addedAt", it.addedAt))
                }
            })
            root.put("history", JSONArray().also { arr ->
                _history.value.forEach {
                    arr.put(JSONObject().put("url", it.url).put("title", it.title).put("visitedAt", it.visitedAt))
                }
            })
            root.put("tabs", JSONObject().put("active", activeTab).put("list", JSONArray().also { arr ->
                tabUrls.forEach { arr.put(it) }
            }))
            val tmp = File(file.path + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${file.name}", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONArray("bookmarks")?.let { arr ->
                _bookmarks.value = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        val url = o.optString("url")
                        if (url.isEmpty()) null else Bookmark(url, o.optString("title", url), o.optLong("addedAt"))
                    }
                }
            }
            root.optJSONArray("history")?.let { arr ->
                _history.value = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        val url = o.optString("url")
                        if (url.isEmpty()) null else HistoryEntry(url, o.optString("title", url), o.optLong("visitedAt"))
                    }
                }.take(MAX_HISTORY)
            }
            root.optJSONObject("tabs")?.let { t ->
                activeTab = t.optInt("active")
                tabUrls = t.optJSONArray("list")?.let { arr -> (0 until arr.length()).map { i -> arr.optString(i) } }
                    ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.name}", e)
        }
    }

    private companion object {
        const val TAG = "BrowserStore"
        const val MAX_HISTORY = 800
    }
}
