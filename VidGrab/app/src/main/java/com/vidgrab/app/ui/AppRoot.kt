@file:OptIn(ExperimentalMaterial3Api::class)

package com.vidgrab.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vidgrab.app.R
import com.vidgrab.app.core.DownloadStatus
import com.vidgrab.app.core.resolve

private const val TAB_HOME = 0
private const val TAB_BROWSER = 1
private const val TAB_DOWNLOADS = 2
private const val TAB_SETTINGS = 3

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val fullscreen by vm.browser.fullscreenView.collectAsStateWithLifecycle()
    val pip by vm.inPictureInPicture.collectAsStateWithLifecycle()
    val pending = downloads.count {
        it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.CANCELLED
    }

    val inFullscreen = tab == TAB_BROWSER && fullscreen != null
    // in the small picture-in-picture window only the video is shown
    val hideBars = inFullscreen || pip
    // The Browser tab handles Back itself (fullscreen, page history, popup tabs) and returns Home when
    // there is nothing left to go back to; every other tab goes Home here.
    BackHandler(enabled = tab != TAB_HOME && tab != TAB_BROWSER) { tab = TAB_HOME }

    // Status bar icons: the home screen has a dark header, so its icons must be light there.
    // Fullscreen video hides the system bars completely.
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    SideEffect {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = tab != TAB_HOME && !darkTheme
            if (inFullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> snackbar.showSnackbar(context.resolve(event.text))
                is UiEvent.Queued -> {
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.added_to_queue, event.count),
                        actionLabel = context.getString(R.string.view),
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) tab = TAB_DOWNLOADS
                }
                UiEvent.OpenBrowserTab -> tab = TAB_BROWSER
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!hideBars) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == TAB_HOME,
                        onClick = { tab = TAB_HOME },
                        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_BROWSER,
                        onClick = { tab = TAB_BROWSER },
                        icon = { Icon(Icons.Filled.Language, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_browser)) },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_DOWNLOADS,
                        onClick = { tab = TAB_DOWNLOADS },
                        icon = {
                            BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(R.string.tab_downloads)) },
                    )
                    NavigationBarItem(
                        selected = tab == TAB_SETTINGS,
                        onClick = { tab = TAB_SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            }
        },
    ) { padding ->
        // Only the bottom inset is applied here: every screen draws its own header under the status bar.
        val bottom = padding.calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                TAB_HOME -> HomeScreen(vm, bottom)
                TAB_BROWSER -> BrowserScreen(vm, bottom, onOpenHome = { tab = TAB_HOME })
                TAB_DOWNLOADS -> DownloadsScreen(vm, bottom)
                else -> SettingsScreen(vm, bottom)
            }
        }
    }
}
