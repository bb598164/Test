package com.vidgrab.app

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.core.readClipboardText
import com.vidgrab.app.ui.AppRoot
import com.vidgrab.app.ui.MainViewModel
import com.vidgrab.app.ui.enterPictureInPicture
import com.vidgrab.app.ui.theme.VidGrabTheme

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* nothing to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            VidGrabTheme(dynamicColor = settings.dynamicColor) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard
        if (hasFocus) vm.onClipboardChecked(readClipboardText(this))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // leaving the app while a web video is fullscreen: keep it playing in a small window
        if (vm.browser.isFullscreen()) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.setInPictureInPicture(isInPictureInPictureMode)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.onIncomingText(it) }
        }
        // a web link opened from another app: show it in the built-in browser (new tab)
        if (intent?.action == Intent.ACTION_VIEW) {
            val link = intent.dataString
            if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
                vm.openInBrowser(link, newTab = true)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
