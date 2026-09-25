package com.sleepysoong.hoard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.ui.chat.ChatScreen
import com.sleepysoong.hoard.ui.chat.ChatViewModel
import com.sleepysoong.hoard.ui.glass.GlassBottomBar
import com.sleepysoong.hoard.ui.glass.GlassTabItem
import com.sleepysoong.hoard.ui.glass.LocalGlassBackdrop
import com.sleepysoong.hoard.ui.sessions.SessionsScreen
import com.sleepysoong.hoard.ui.settings.SettingsScreen
import com.sleepysoong.hoard.ui.theme.HoardTheme
import com.sleepysoong.hoard.ui.tools.ToolsScreen

private data class Tab(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val TABS = listOf(
    Tab("sessions", "세션", Icons.Rounded.Forum, Icons.Rounded.Forum),
    Tab("tools", "도구", Icons.Rounded.Build, Icons.Rounded.Build),
    Tab("settings", "설정", Icons.Rounded.Settings, Icons.Rounded.Settings)
)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val ctx = LocalContext.current
            val settings by SettingsStore.flow(ctx).collectAsState(SettingsStore.Settings())
            val dark = when (settings.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // Status/nav bars should mirror the theme, not the wallpaper.
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            HoardTheme(darkTheme = dark) {
                val nav = rememberNavController()
                val vm: ChatViewModel = viewModel()
                // 0=채팅 1=세션 2=도구 3=설정. 앱은 세션 목록에서 시작한다.
                var selected by rememberSaveable { mutableIntStateOf(1) }
                var pressed by remember { mutableStateOf(-1) }
                val backdrop = LocalGlassBackdrop.current
                // Foldables / tablets: side-by-side sessions + chat, state stays in the VM.
                val windowSizeClass = calculateWindowSizeClass(this)
                val twoPane = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

                // Chat and sessions are one flow: while chatting, the bottom bar IS the
                // composer, so the floating tab bar steps aside (also while the IME is up).
                val backStackEntry by nav.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route ?: "sessions"
                val inChat = route == "chat"
                val imeVisible = WindowInsets.isImeVisible
                // Tab bar: Sessions / Tools / Settings only. Chat hides it —
                // the composer takes over the bottom chrome there.
                val showTabBar = !inChat && !imeVisible

                val navBarBottom = with(LocalDensity.current) {
                    WindowInsets.navigationBars.getBottom(this).toDp()
                }
                val bottomReserve by animateDpAsState(
                    targetValue = when {
                        inChat -> navBarBottom + 18.dp
                        imeVisible -> 0.dp
                        else -> navBarBottom + 108.dp
                    },
                    animationSpec = tween(220),
                    label = "bottom-reserve"
                )

                fun openChat() {
                    // Selecting a session opens chat; no tab index change — the
                    // chat screen replaces the tab bar with the composer.
                    nav.navigate("chat") { launchSingleTop = true }
                }

                fun backToSessions() {
                    selected = 0
                    if (!nav.popBackStack()) {
                        nav.navigate("sessions") { launchSingleTop = true }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = nav,
                        startDestination = "sessions",
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .imePadding()
                            .padding(horizontal = if (twoPane) 20.dp else 12.dp)
                            .padding(bottom = bottomReserve, top = 8.dp)
                    ) {
                        composable("chat") {
                            if (twoPane) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.width(360.dp).fillMaxHeight()) {
                                        SessionsScreen(vm, onOpenChat = {})
                                    }
                                    VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        ChatScreen(vm, showBackButton = false)
                                    }
                                }
                            } else {
                                ChatScreen(vm, showBackButton = true, onBack = { backToSessions() })
                            }
                        }
                        composable("sessions") {
                            SessionsScreen(vm, onOpenChat = { openChat() })
                        }
                        composable("tools") { ToolsScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                    // Interactive liquid bottom tabs — only outside the chat composer.
                    AnimatedVisibility(
                        visible = showTabBar,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                        exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        GlassBottomBar(
                            selectedTabIndex = selected,
                            tabsCount = TABS.size,
                            backdrop = backdrop,
                            pressedTabIndex = pressed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            TABS.forEachIndexed { i, tab ->
                                GlassTabItem(
                                    selected = selected == i,
                                    icon = tab.selectedIcon,
                                    title = tab.title,
                                    onPressedChange = { isPressed -> pressed = if (isPressed) i else -1 },
                                    onClick = {
                                        if (selected != i) {
                                            selected = i
                                            nav.navigate(tab.route) { launchSingleTop = true }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
