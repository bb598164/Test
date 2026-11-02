@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.R
import com.vidgrab.app.browser.SEARCH_ENGINES
import com.vidgrab.app.core.ThemeMode
import com.vidgrab.app.engine.PyEngine
import kotlin.math.roundToInt

private enum class Dlg { THEME, LANGUAGE, FOLDER, CONCURRENT, CONNECTIONS, SPEED, SUBLANGS, CHANNEL, SEARCH }

@Composable
fun SettingsScreen(vm: MainViewModel, bottomPadding: Dp) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val hasCookies by vm.cookiesPresent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dlg?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setCustomFolder(uri)
    }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    val engineInfo = (engine as? PyEngine.State.Ready)?.info

    // ---- labels
    val themeLabel = stringResource(
        when (s.theme) {
            ThemeMode.SYSTEM -> R.string.theme_system
            ThemeMode.LIGHT -> R.string.theme_light
            ThemeMode.DARK -> R.string.theme_dark
        }
    )
    val languageLabel = stringResource(
        when (s.language) {
            "ar" -> R.string.lang_arabic
            "en" -> R.string.lang_english
            else -> R.string.lang_system
        }
    )
    val unlimited = stringResource(R.string.unlimited)
    val speedLabel = if (s.speedLimitKb == 0) unlimited else speedText(s.speedLimitKb)
    val folderLabel = s.customFolderUri?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) }
        ?: stringResource(R.string.folder_default)
    val channelLabel = stringResource(if (s.updateChannel == "nightly") R.string.channel_nightly else R.string.channel_stable)
    val searchLabel = SEARCH_ENGINES.firstOrNull { it.id == s.searchEngine }?.name ?: s.searchEngine

    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings), fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle(stringResource(R.string.section_appearance))
            SettingItem(Icons.Filled.DarkMode, stringResource(R.string.theme), themeLabel) { dialog = Dlg.THEME }
            SettingItem(Icons.Filled.Translate, stringResource(R.string.language), languageLabel) { dialog = Dlg.LANGUAGE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchItem(
                    Icons.Filled.Palette,
                    stringResource(R.string.dynamic_color),
                    stringResource(R.string.dynamic_color_sub),
                    s.dynamicColor,
                ) { v -> vm.updateSettings { it.copy(dynamicColor = v) } }
            }

            SectionTitle(stringResource(R.string.section_downloads))
            SettingItem(Icons.Filled.Folder, stringResource(R.string.save_location), folderLabel) { dialog = Dlg.FOLDER }
            SettingItem(
                Icons.Filled.Storage,
                stringResource(R.string.concurrent_downloads),
                "${s.maxConcurrent}",
            ) { dialog = Dlg.CONCURRENT }
            SettingItem(
                Icons.Filled.Tune,
                stringResource(R.string.connections),
                stringResource(R.string.connections_sub, s.connections),
            ) { dialog = Dlg.CONNECTIONS }
            SettingItem(Icons.Filled.Speed, stringResource(R.string.speed_limit), speedLabel) { dialog = Dlg.SPEED }
            SwitchItem(
                Icons.Filled.Wifi,
                stringResource(R.string.wifi_only),
                stringResource(R.string.wifi_only_sub),
                s.wifiOnly,
            ) { v -> vm.updateSettings { it.copy(wifiOnly = v) } }

            SectionTitle(stringResource(R.string.section_browser))
            SettingItem(Icons.Filled.Search, stringResource(R.string.search_engine), searchLabel) { dialog = Dlg.SEARCH }
            SwitchItem(
                Icons.Filled.Shield,
                stringResource(R.string.block_ads),
                stringResource(R.string.block_ads_sub),
                s.blockAds,
            ) { v -> vm.updateSettings { it.copy(blockAds = v) } }
            SliderItem(
                icon = Icons.Filled.TextFields,
                title = stringResource(R.string.page_zoom),
                value = s.pageTextZoom,
                range = 50..300,
                step = 25,
            ) { v -> vm.updateSettings { it.copy(pageTextZoom = v) } }

            SectionTitle(stringResource(R.string.section_media))
            SwitchItem(
                Icons.Filled.Info,
                stringResource(R.string.embed_metadata),
                null,
                s.embedMetadata,
            ) { v -> vm.updateSettings { it.copy(embedMetadata = v) } }
            SwitchItem(
                Icons.Filled.Image,
                stringResource(R.string.embed_thumbnail),
                stringResource(R.string.embed_thumbnail_sub),
                s.embedThumbnail,
            ) { v -> vm.updateSettings { it.copy(embedThumbnail = v) } }
            SwitchItem(
                Icons.Filled.Subtitles,
                stringResource(R.string.embed_subs),
                stringResource(R.string.embed_subs_sub),
                s.embedSubs,
            ) { v -> vm.updateSettings { it.copy(embedSubs = v) } }
            if (s.embedSubs) {
                SettingItem(Icons.Filled.Language, stringResource(R.string.sub_langs), s.subLangs) { dialog = Dlg.SUBLANGS }
            }

            SectionTitle(stringResource(R.string.section_engine))
            SettingItem(
                Icons.Filled.Memory,
                stringResource(R.string.ytdlp_version),
                engineInfo?.version ?: stringResource(R.string.engine_not_ready),
            )
            SettingItem(
                icon = Icons.Filled.Update,
                title = stringResource(R.string.update_now),
                subtitle = stringResource(R.string.update_now_sub),
                onClick = { vm.updateEngine() },
                trailing = {
                    if (updating) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                },
            )
            SwitchItem(
                Icons.Filled.Autorenew,
                stringResource(R.string.auto_update),
                stringResource(R.string.auto_update_sub),
                s.autoUpdate,
            ) { v -> vm.updateSettings { it.copy(autoUpdate = v) } }
            SettingItem(Icons.Filled.Tune, stringResource(R.string.update_channel), channelLabel) { dialog = Dlg.CHANNEL }
            SettingItem(
                Icons.Filled.Code,
                stringResource(R.string.js_runtime),
                engineInfo?.jsRuntime?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.js_runtime_missing),
            )
            SettingItem(
                Icons.Filled.VideoLibrary,
                stringResource(R.string.ffmpeg),
                if (engineInfo?.ffmpeg == true) stringResource(R.string.available) else stringResource(R.string.not_available),
            )

            SectionTitle(stringResource(R.string.section_accounts))
            SettingItem(
                Icons.Filled.VpnKey,
                stringResource(R.string.cookies),
                if (hasCookies) stringResource(R.string.cookies_loaded) else stringResource(R.string.cookies_none),
            ) { cookiePicker.launch(arrayOf("*/*")) }
            if (hasCookies) {
                SwitchItem(
                    Icons.Filled.Lock,
                    stringResource(R.string.use_cookies),
                    null,
                    s.useCookies,
                ) { v -> vm.updateSettings { it.copy(useCookies = v) } }
                SettingItem(Icons.Filled.Delete, stringResource(R.string.cookies_remove)) { vm.clearCookies() }
            }
            SwitchItem(
                Icons.Filled.Language,
                stringResource(R.string.browser_cookies),
                stringResource(R.string.browser_cookies_sub),
                s.useBrowserCookies,
            ) { v -> vm.updateSettings { it.copy(useBrowserCookies = v) } }
            SettingItem(Icons.Filled.DeleteSweep, stringResource(R.string.menu_clear_data)) { vm.clearBrowsingData() }
            Text(
                stringResource(R.string.cookies_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SectionTitle(stringResource(R.string.section_about))
            SettingItem(Icons.Filled.Info, stringResource(R.string.app_name), "v$versionName")
            Text(
                stringResource(R.string.legal_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- dialogs
    when (dialog) {
        Dlg.THEME -> ChoiceDialog(
            title = stringResource(R.string.theme),
            options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                ThemeMode.LIGHT to stringResource(R.string.theme_light),
                ThemeMode.DARK to stringResource(R.string.theme_dark),
            ),
            selected = s.theme,
            onSelect = { vm.setTheme(it) },
            onDismiss = { dialog = null },
        )
        Dlg.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.language),
            options = listOf(
                "system" to stringResource(R.string.lang_system),
                "ar" to stringResource(R.string.lang_arabic),
                "en" to stringResource(R.string.lang_english),
            ),
            selected = s.language,
            onSelect = { vm.setLanguage(it) },
            onDismiss = { dialog = null },
        )
        Dlg.FOLDER -> ChoiceDialog(
            title = stringResource(R.string.save_location),
            options = listOf(
                "default" to stringResource(R.string.folder_default),
                "custom" to stringResource(R.string.folder_choose),
            ),
            selected = if (s.customFolderUri == null) "default" else "custom",
            onSelect = { choice ->
                if (choice == "default") vm.setCustomFolder(null) else folderPicker.launch(null)
            },
            onDismiss = { dialog = null },
        )
        Dlg.CONCURRENT -> ChoiceDialog(
            title = stringResource(R.string.concurrent_downloads),
            options = (1..5).map { it to "$it" },
            selected = s.maxConcurrent,
            onSelect = { v -> vm.updateSettings { it.copy(maxConcurrent = v) } },
            onDismiss = { dialog = null },
        )
        Dlg.CONNECTIONS -> ChoiceDialog(
            title = stringResource(R.string.connections),
            options = listOf(1, 2, 4, 8, 16).map { it to "$it" },
            selected = s.connections,
            onSelect = { v -> vm.updateSettings { it.copy(connections = v) } },
            onDismiss = { dialog = null },
        )
        Dlg.SPEED -> ChoiceDialog(
            title = stringResource(R.string.speed_limit),
            options = listOf(0, 256, 512, 1024, 2048, 5120).map { kb ->
                kb to (if (kb == 0) unlimited else speedText(kb))
            },
            selected = s.speedLimitKb,
            onSelect = { v -> vm.updateSettings { it.copy(speedLimitKb = v) } },
            onDismiss = { dialog = null },
        )
        Dlg.SUBLANGS -> TextInputDialog(
            title = stringResource(R.string.sub_langs),
            initial = s.subLangs,
            hint = stringResource(R.string.sub_langs_hint),
            onConfirm = { v -> vm.updateSettings { it.copy(subLangs = v.ifEmpty { "en" }) } },
            onDismiss = { dialog = null },
        )
        Dlg.CHANNEL -> ChoiceDialog(
            title = stringResource(R.string.update_channel),
            options = listOf(
                "stable" to stringResource(R.string.channel_stable),
                "nightly" to stringResource(R.string.channel_nightly),
            ),
            selected = s.updateChannel,
            onSelect = { v -> vm.updateSettings { it.copy(updateChannel = v) } },
            onDismiss = { dialog = null },
        )
        Dlg.SEARCH -> ChoiceDialog(
            title = stringResource(R.string.search_engine),
            options = SEARCH_ENGINES.map { it.id to it.name },
            selected = s.searchEngine,
            onSelect = { v -> vm.updateSettings { it.copy(searchEngine = v) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun speedText(kb: Int): String = if (kb >= 1024) "${kb / 1024} MB/s" else "$kb KB/s"

@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    if (subtitle != null) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = rowModifier,
        )
    } else {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = rowModifier,
        )
    }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SettingItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onChecked) },
        onClick = { onChecked(!checked) },
    )
}

/** A setting with a slider; the value is saved when the finger is lifted, not on every pixel. */
@Composable
private fun SliderItem(
    icon: ImageVector,
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onCommit: (Int) -> Unit,
) {
    var live by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        SettingItem(icon = icon, title = title, subtitle = "${live.roundToInt()}%")
        Slider(
            value = live,
            onValueChange = { live = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            onValueChangeFinished = { onCommit(live.roundToInt()) },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}
