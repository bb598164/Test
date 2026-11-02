package com.vidgrab.app.data

import android.util.Log
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.DownloadStatus
import com.vidgrab.app.core.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the download list as a small JSON file (written atomically). */
class DownloadStore(private val file: File) {

    @Synchronized
    fun load(): List<DownloadItem> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                try {
                    fromJson(array.getJSONObject(index))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read ${file.name}", e)
            emptyList()
        }
    }

    @Synchronized
    fun save(items: List<DownloadItem>) {
        try {
            val array = JSONArray()
            items.forEach { array.put(toJson(it)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write ${file.name}", e)
        }
    }

    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).ifEmpty { null }

    private fun toJson(i: DownloadItem) = JSONObject().apply {
        put("id", i.id)
        put("url", i.url)
        put("title", i.title)
        put("uploader", i.uploader ?: "")
        put("thumbnail", i.thumbnail ?: "")
        put("durationSec", i.durationSec)
        put("kind", i.kind.name)
        put("maxHeight", i.maxHeight)
        put("audioFormat", i.audioFormat)
        put("audioBitrate", i.audioBitrate)
        put("qualityLabel", i.qualityLabel)
        put("expectedBytes", i.expectedBytes)
        put("status", i.status.name)
        put("progress", i.progress.toDouble())
        put("downloadedBytes", i.downloadedBytes)
        put("totalBytes", i.totalBytes)
        put("outputUri", i.outputUri ?: "")
        put("outputName", i.outputName ?: "")
        put("outputSize", i.outputSize)
        put("referer", i.referer ?: "")
        put("userAgent", i.userAgent ?: "")
        put("error", i.error ?: "")
        put("createdAt", i.createdAt)
        put("finishedAt", i.finishedAt)
    }

    private fun fromJson(o: JSONObject) = DownloadItem(
        id = o.getLong("id"),
        url = o.getString("url"),
        title = o.optString("title"),
        uploader = o.str("uploader"),
        thumbnail = o.str("thumbnail"),
        durationSec = o.optLong("durationSec"),
        kind = runCatching { MediaKind.valueOf(o.optString("kind")) }.getOrDefault(MediaKind.VIDEO),
        maxHeight = o.optInt("maxHeight"),
        audioFormat = o.optString("audioFormat", "mp3"),
        audioBitrate = o.optInt("audioBitrate", 192),
        qualityLabel = o.optString("qualityLabel", "best"),
        expectedBytes = o.optLong("expectedBytes"),
        status = runCatching { DownloadStatus.valueOf(o.optString("status")) }.getOrDefault(DownloadStatus.PAUSED),
        progress = o.optDouble("progress", 0.0).toFloat(),
        downloadedBytes = o.optLong("downloadedBytes"),
        totalBytes = o.optLong("totalBytes"),
        outputUri = o.str("outputUri"),
        outputName = o.str("outputName"),
        outputSize = o.optLong("outputSize"),
        referer = o.str("referer"),
        userAgent = o.str("userAgent"),
        error = o.str("error"),
        createdAt = o.optLong("createdAt"),
        finishedAt = o.optLong("finishedAt"),
    )

    private companion object {
        const val TAG = "DownloadStore"
    }
}
