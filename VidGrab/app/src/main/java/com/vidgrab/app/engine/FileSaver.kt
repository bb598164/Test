package com.vidgrab.app.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.vidgrab.app.core.mimeForExtension
import com.vidgrab.app.core.sanitizeFileName
import com.vidgrab.app.data.SettingsRepository
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Moves a finished download from the private temp folder to its final place:
 *  - a folder chosen by the user (Storage Access Framework), or
 *  - Movies/VidGrab and Music/VidGrab (MediaStore on Android 10+, so the gallery sees them), or
 *  - the same public folders directly on Android 8/9.
 */
class FileSaver(private val context: Context, private val settings: SettingsRepository) {

    data class Saved(val uri: String, val name: String, val size: Long)

    fun save(src: File, title: String): Saved {
        val ext = src.extension.lowercase(Locale.ROOT).ifEmpty { "mp4" }
        val name = sanitizeFileName(title) + "." + ext
        val mime = mimeForExtension(ext)
        val custom = settings.current.customFolderUri
        if (custom != null) {
            try {
                return saveToTree(src, custom, name, mime)
            } catch (e: Exception) {
                Log.w(TAG, "custom folder failed, using the default folder", e)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(src, name, mime)
        else saveLegacy(src, name, mime)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(src: File, name: String, mimeIn: String): Saved {
        val resolver = context.contentResolver
        val audio = mimeIn.startsWith("audio/")
        val mime = if (audio || mimeIn.startsWith("video/")) mimeIn else "video/mp4"
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + FOLDER
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore refused to create the file")
        try {
            val out = resolver.openOutputStream(uri) ?: error("Could not open the output file")
            out.use { copy(src, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return Saved(uri.toString(), queryName(uri) ?: name, src.length())
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(src: File, name: String, mime: String): Saved {
        val base = Environment.getExternalStoragePublicDirectory(
            if (mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        )
        val dir = File(base, FOLDER).apply { mkdirs() }
        var dest = File(dir, name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        src.copyTo(dest, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Saved(dest.absolutePath, dest.name, dest.length())
    }

    private fun saveToTree(src: File, treeUri: String, name: String, mime: String): Saved {
        val tree = Uri.parse(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
            ?: error("Could not create the file in the chosen folder")
        try {
            val out = context.contentResolver.openOutputStream(doc, "w") ?: error("Could not open the output file")
            out.use { copy(src, it) }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
            throw t
        }
        return Saved(doc.toString(), queryName(doc) ?: name, src.length())
    }

    private fun copy(src: File, out: OutputStream) {
        src.inputStream().use { it.copyTo(out, 256 * 1024) }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "FileSaver"
        const val FOLDER = "VidGrab"
    }
}
