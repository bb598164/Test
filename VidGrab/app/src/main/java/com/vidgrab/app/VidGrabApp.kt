package com.vidgrab.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.vidgrab.app.browser.BrowserController
import com.vidgrab.app.browser.BrowserStore
import com.vidgrab.app.core.ThemeMode
import com.vidgrab.app.data.SettingsRepository
import com.vidgrab.app.engine.CookieProvider
import com.vidgrab.app.engine.DownloadManager
import com.vidgrab.app.engine.PyEngine
import com.vidgrab.app.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class VidGrabApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: PyEngine
        private set
    lateinit var downloads: DownloadManager
        private set
    lateinit var cookies: CookieProvider
        private set

    /** The in-app browser; its WebView is only created when the Browser tab is first opened. */
    val browser: BrowserController by lazy {
        BrowserController(this, settings, BrowserStore(File(filesDir, "browser.json"), appScope))
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        applyTheme(settings.current.theme)
        Notifications.ensureChannels(this)

        engine = PyEngine(this, appScope)
        cookies = CookieProvider(this, settings)
        downloads = DownloadManager(this, appScope, settings, engine, cookies)
        engine.start()
        downloads.start()

        // Keep yt-dlp fresh: sites change all the time. Checked at most every 12 hours.
        appScope.launch {
            engine.state.first { it is PyEngine.State.Ready || it is PyEngine.State.Failed }
            if (engine.state.value !is PyEngine.State.Ready) return@launch
            val s = settings.current
            val now = System.currentTimeMillis()
            if (s.autoUpdate && now - s.lastUpdateCheck > UPDATE_INTERVAL_MS) {
                settings.update { it.copy(lastUpdateCheck = now) }
                try {
                    val result = engine.update(s.updateChannel)
                    if (result.status == "updated" && !downloads.hasActive()) engine.reload()
                } catch (e: Exception) {
                    // offline or GitHub unreachable: try again next time
                }
            }
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L

        fun applyTheme(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }

        fun applyLanguage(code: String) {
            AppCompatDelegate.setApplicationLocales(
                if (code == "system") LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(code)
            )
        }
    }
}
