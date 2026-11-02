package com.vidgrab.app.data

import android.content.Context
import com.vidgrab.app.core.Settings
import com.vidgrab.app.core.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings kept in SharedPreferences and exposed as a StateFlow. */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vidgrab_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())

    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        write(next)
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            theme = ThemeMode.entries.getOrElse(prefs.getInt("theme", d.theme.ordinal)) { d.theme },
            language = prefs.getString("language", d.language) ?: d.language,
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            maxConcurrent = prefs.getInt("maxConcurrent", d.maxConcurrent).coerceIn(1, 5),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            connections = prefs.getInt("connections", d.connections).coerceIn(1, 16),
            speedLimitKb = prefs.getInt("speedLimitKb", d.speedLimitKb),
            embedMetadata = prefs.getBoolean("embedMetadata", d.embedMetadata),
            embedThumbnail = prefs.getBoolean("embedThumbnail", d.embedThumbnail),
            embedSubs = prefs.getBoolean("embedSubs", d.embedSubs),
            subLangs = prefs.getString("subLangs", d.subLangs) ?: d.subLangs,
            autoUpdate = prefs.getBoolean("autoUpdate", d.autoUpdate),
            updateChannel = prefs.getString("updateChannel", d.updateChannel) ?: d.updateChannel,
            useCookies = prefs.getBoolean("useCookies", d.useCookies),
            useBrowserCookies = prefs.getBoolean("useBrowserCookies", d.useBrowserCookies),
            searchEngine = prefs.getString("searchEngine", d.searchEngine) ?: d.searchEngine,
            blockAds = prefs.getBoolean("blockAds", d.blockAds),
            pageTextZoom = prefs.getInt("pageTextZoom", d.pageTextZoom).coerceIn(50, 300),
            customFolderUri = prefs.getString("customFolderUri", null),
            lastUpdateCheck = prefs.getLong("lastUpdateCheck", 0L),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putInt("theme", s.theme.ordinal)
            .putString("language", s.language)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putInt("maxConcurrent", s.maxConcurrent)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putInt("connections", s.connections)
            .putInt("speedLimitKb", s.speedLimitKb)
            .putBoolean("embedMetadata", s.embedMetadata)
            .putBoolean("embedThumbnail", s.embedThumbnail)
            .putBoolean("embedSubs", s.embedSubs)
            .putString("subLangs", s.subLangs)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putString("updateChannel", s.updateChannel)
            .putBoolean("useCookies", s.useCookies)
            .putBoolean("useBrowserCookies", s.useBrowserCookies)
            .putString("searchEngine", s.searchEngine)
            .putBoolean("blockAds", s.blockAds)
            .putInt("pageTextZoom", s.pageTextZoom)
            .putString("customFolderUri", s.customFolderUri)
            .putLong("lastUpdateCheck", s.lastUpdateCheck)
            .apply()
    }
}
