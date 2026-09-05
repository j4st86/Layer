package com.layer.app.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.layer.app.R
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.layer.app.di.AppContainer
import com.layer.app.ui.apps.AppsScreen
import com.layer.app.ui.developer.DeveloperScreen
import com.layer.app.ui.diagnostics.DiagnosticsScreen
import com.layer.app.ui.domains.DomainsScreen
import com.layer.app.ui.home.HomeScreen
import com.layer.app.ui.settings.SettingsScreen
import com.layer.core.util.HiddenTapGate
import com.layer.core.util.HiddenTapResult
import kotlinx.coroutines.launch

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer is not provided")
}

object Routes {
    const val Home = "home"
    const val Apps = "apps"
    const val Domains = "domains"
    const val Settings = "settings"
    const val Diagnostics = "diagnostics"
    const val Developer = "developer"
}

private data class Tab(
    val route: String,
    val label: String,
    val selected: ImageVector,
    val unselected: ImageVector,
)

@Composable
private fun rememberTabs(): List<Tab> = listOf(
    Tab(Routes.Home, stringResource(R.string.tab_home), Icons.Rounded.Home, Icons.Outlined.Home),
    Tab(Routes.Apps, stringResource(R.string.tab_apps), Icons.Rounded.Apps, Icons.Outlined.Apps),
    Tab(Routes.Domains, stringResource(R.string.tab_domains), Icons.Rounded.Language, Icons.Outlined.Language),
    Tab(Routes.Settings, stringResource(R.string.tab_more), Icons.Rounded.Settings, Icons.Outlined.Settings),
)

@Composable
fun LayerRoot(container: AppContainer) {
    val navController = rememberNavController()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val naviTaps = remember { HiddenTapGate() }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = currentRoute == Routes.Home
    val tabs = rememberTabs()

    CompositionLocalProvider(LocalAppContainer provides container) {
        Scaffold(
            bottomBar = {
                if (showTabs) {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (tab.route == Routes.Settings) {
                                        when (val result = naviTaps.tap(SystemClock.elapsedRealtime())) {
                                            HiddenTapResult.Unlocked -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                navController.navigate(Routes.Developer) {
                                                    launchSingleTop = true
                                                }
                                            }
                                            is HiddenTapResult.Progress -> if (result.remaining == 1) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.navi_one_layer_left),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    } else {
                                        naviTaps.reset()
                                    }
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                icon = {
                                    Icon(if (selected) tab.selected else tab.unselected, contentDescription = tab.label)
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Home,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            ) {
                composable(Routes.Home) {
                    MainPager(
                        pagerState = pagerState,
                        tabRoutes = tabs.map { it.route },
                    )
                }
                composable(Routes.Diagnostics) {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.Developer) {
                    DeveloperScreen(
                        onBack = { navController.popBackStack() },
                        onDiagnostics = { navController.navigate(Routes.Diagnostics) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainPager(
    pagerState: PagerState,
    tabRoutes: List<String>,
) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        key = { tabRoutes[it] },
    ) { page ->
        when (page) {
            0 -> HomeScreen()
            1 -> AppsScreen()
            2 -> DomainsScreen()
            else -> SettingsScreen()
        }
    }
}
