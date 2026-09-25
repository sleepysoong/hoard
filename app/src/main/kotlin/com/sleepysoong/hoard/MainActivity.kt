package com.sleepysoong.hoard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    Tab("chat", "채팅", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    Tab("sessions", "세션", Icons.Filled.Forum, Icons.Outlined.Forum),
    Tab("tools", "도구", Icons.Filled.Build, Icons.Outlined.Build),
    Tab("settings", "설정", Icons.Filled.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {
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
                var selected by remember { mutableIntStateOf(0) }
                var pressed by remember { mutableStateOf(-1) }
                val backdrop = LocalGlassBackdrop.current

                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = nav,
                        startDestination = "chat",
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 108.dp, top = 8.dp)
                    ) {
                        composable("chat") { ChatScreen(vm) }
                        composable("sessions") {
                            SessionsScreen(vm, onOpenChat = {
                                selected = 0
                                nav.navigate("chat") { launchSingleTop = true }
                            })
                        }
                        composable("tools") { ToolsScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                    // Interactive liquid bottom tabs floating above the system bar.
                    GlassBottomBar(
                        selectedTabIndex = selected,
                        tabsCount = TABS.size,
                        backdrop = backdrop,
                        pressedTabIndex = pressed,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        TABS.forEachIndexed { i, tab ->
                            NavigationBarItem(
                                selected = selected == i,
                                onClick = {
                                    pressed = i
                                    selected = i
                                    nav.navigate(tab.route) { launchSingleTop = true }
                                    pressed = -1
                                },
                                icon = {
                                    Icon(
                                        if (selected == i) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.title
                                    )
                                },
                                label = { Text(tab.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
