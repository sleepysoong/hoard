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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubble
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
    Tab("chat", "채팅", Icons.Rounded.ChatBubble, Icons.Rounded.ChatBubble),
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
            HoardTheme(darkTheme = dark) {
                val nav = rememberNavController()
                val vm: ChatViewModel = viewModel()
                var selected by rememberSaveable { mutableIntStateOf(0) }
                var pressed by remember { mutableStateOf(-1) }
                val backdrop = LocalGlassBackdrop.current
                // Foldables / tablets: side-by-side sessions + chat, state stays in the VM.
                val windowSizeClass = calculateWindowSizeClass(this)
                val twoPane = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

                // iOS-style: tab bar yields to the keyboard, content rides the IME.
                val imeVisible = WindowInsets.isImeVisible
                val bottomReserve by animateDpAsState(
                    targetValue = if (imeVisible) 12.dp else 108.dp,
                    animationSpec = tween(220),
                    label = "ime-bottom-reserve"
                )

                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = nav,
                        startDestination = "chat",
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
                                    Box(Modifier.weight(1f).fillMaxHeight()) { ChatScreen(vm) }
                                }
                            } else {
                                ChatScreen(vm)
                            }
                        }
                        composable("sessions") {
                            SessionsScreen(vm, onOpenChat = {
                                selected = 0
                                nav.navigate("chat") { launchSingleTop = true }
                            })
                        }
                        composable("tools") { ToolsScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                    // Interactive liquid bottom tabs — hidden while the keyboard is up.
                    AnimatedVisibility(
                        visible = !imeVisible,
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
