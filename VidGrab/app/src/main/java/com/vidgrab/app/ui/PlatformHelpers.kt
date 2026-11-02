package com.vidgrab.app.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Rational

/** The Activity behind a Compose LocalContext (which may be wrapped by AppCompat / theme wrappers). */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Shrinks the app into the small picture-in-picture window (used for fullscreen web videos). */
internal fun Activity.enterPictureInPicture() {
    try {
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    } catch (e: Exception) {
        // picture-in-picture is not available on this device
    }
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
}

internal fun shareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
        // no app can share it
    }
}

internal fun openExternally(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: Exception) {
        // no other browser installed
    }
}
