package com.vidgrab.app.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.vidgrab.app.core.DownloadItem
import com.vidgrab.app.core.mimeForExtension
import java.io.File

/** Open / share / delete the file of a finished download. */
object FileActions {

    fun resolveUri(context: Context, stored: String): Uri =
        if (stored.startsWith("content://")) Uri.parse(stored)
        else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(stored))

    private fun mimeOf(item: DownloadItem): String =
        mimeForExtension(item.outputName?.substringAfterLast('.', "") ?: "mp4")

    fun open(context: Context, item: DownloadItem): Boolean {
        val stored = item.outputUri ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(resolveUri(context, stored), mimeOf(item))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun share(context: Context, item: DownloadItem): Boolean {
        val stored = item.outputUri ?: return false
        return try {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeOf(item))
                .putExtra(Intent.EXTRA_STREAM, resolveUri(context, stored))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, null)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(context: Context, stored: String): Boolean = try {
        if (stored.startsWith("content://")) {
            val uri = Uri.parse(stored)
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } else {
            File(stored).delete()
        }
    } catch (e: Exception) {
        false
    }
}
