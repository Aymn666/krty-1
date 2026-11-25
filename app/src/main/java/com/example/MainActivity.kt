package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InAppScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.KartiTheme
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper

const val ROUTE_HOME = "home"
const val ROUTE_HISTORY = "history"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_SCANNER = "scanner"

private data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var openScannerTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(MidnightBg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(DeepNavySurface.toArgb())
        )
        if (intent?.getBooleanExtra(EXTRA_OPEN_IN_APP_SCANNER, false) == true) {
            openScannerTrigger++
            intent.removeExtra(EXTRA_OPEN_IN_APP_SCANNER)
        }

        setContent {
            KartiTheme {
                KartiAppRoot(
                    viewModel = viewModel,
                    openScannerTrigger = openScannerTrigger
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_IN_APP_SCANNER, false)) {
            openScannerTrigger++
            intent.removeExtra(EXTRA_OPEN_IN_APP_SCANNER)
        }
    }

    companion object {
        const val EXTRA_OPEN_IN_APP_SCANNER = "extra_open_in_app_scanner"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KartiAppRoot(
    viewModel: MainViewModel,
    openScannerTrigger: Int
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_HOME
    var pendingScannerAfterCameraGrant by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted && pendingScannerAfterCameraGrant) {
            pendingScannerAfterCameraGrant = false
            navController.navigate(ROUTE_SCANNER) {
                launchSingleTop = true
            }
        } else if (!granted) {
            pendingScannerAfterCameraGrant = false
            Toast.makeText(context, "صلاحية الكاميرا مطلوبة لمسح الكروت", Toast.LENGTH_SHORT).show()
            PermissionHelper.openAppDetailsSettings(context)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (!granted) {
            PermissionHelper.openAppDetailsSettings(context)
        }
    }

    LaunchedEffect(openScannerTrigger) {
        if (openScannerTrigger > 0) {
            if (PermissionHelper.isCameraGranted(context)) {
                navController.navigate(ROUTE_SCANNER) {
                    launchSingleTop = true
                }
            } else {
                pendingScannerAfterCameraGrant = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    val tabs = listOf(
        NavTabItem(ROUTE_HOME, "الرئيسية", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
        NavTabItem(ROUTE_HISTORY, "السجل", Icons.Filled.History, Icons.Outlined.History, "nav_history"),
        NavTabItem(ROUTE_SETTINGS, "الإعدادات", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
    )

    val showChrome = currentRoute != ROUTE_SCANNER

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MidnightBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showChrome) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = when (currentRoute) {
                                    ROUTE_HISTORY -> "سجل الكروت الممسوحة"
                                    ROUTE_SETTINGS -> "الإعدادات"
                                    else -> "كرتي"
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MidnightBg,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    )
                }
            },
            bottomBar = {
                if (showChrome && !useRail) {
                    NavigationBar(
                        containerColor = DeepNavySurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MidnightBg,
                                    selectedTextColor = EmeraldPrimary,
                                    indicatorColor = EmeraldPrimary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showChrome && useRail) {
                    NavigationRail(
                        containerColor = DeepNavySurface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag),
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MidnightBg,
                                    selectedTextColor = EmeraldPrimary,
                                    indicatorColor = EmeraldPrimary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            uiState = uiState,
                            onRequestCameraPermission = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenOverlaySettings = {
                                viewModel.markOverlaySettingsAttempted()
                                PermissionHelper.openOverlaySettings(context)
                            },
                            onOpenAppDetailsSettings = {
                                PermissionHelper.openAppDetailsSettings(context)
                            },
                            onOpenNotificationListenerSettings = {
                                PermissionHelper.openNotificationListenerSettings(context)
                            },
                            onOpenAccessibilitySettings = {
                                PermissionHelper.openAccessibilitySettings(context)
                            },
                            onOpenInAppScanner = {
                                navController.navigate(ROUTE_SCANNER)
                            },
                            onOpenPortalWithScanner = {
                                viewModel.openCaptivePortalWithScanner()
                            },
                            onStartOverlayScanner = {
                                val launched = viewModel.launchScannerOverlay()
                                if (!launched) {
                                    navController.navigate(ROUTE_SCANNER)
                                }
                            },
                            onToggleFloatingBubble = {
                                if (uiState.isBubbleRunning) {
                                    viewModel.stopFloatingBubble()
                                } else {
                                    viewModel.markOverlaySettingsAttempted()
                                    if (uiState.permissions.overlayGranted) {
                                        viewModel.startFloatingBubble()
                                    } else {
                                        PermissionHelper.openOverlaySettings(context)
                                    }
                                }
                            },
                            onCopyCode = { code -> viewModel.copyCardCode(code) },
                            onOpenCaptivePortal = { viewModel.openCaptivePortal() }
                        )
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            cards = uiState.cardHistory,
                            onCopyCode = { code -> viewModel.copyCardCode(code) },
                            onReDispatchCode = { code -> viewModel.reDispatchCardCode(code) },
                            onDeleteCard = { id -> viewModel.deleteCard(id) },
                            onClearAll = { viewModel.clearAllHistory() }
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            settings = uiState.settings,
                            onAutoFlashChange = viewModel::setAutoFlash,
                            onVibrationChange = viewModel::setVibration,
                            onAutoFillChange = viewModel::setAutoFill,
                            onAutoClickChange = viewModel::setAutoClick,
                            onSaveHistoryChange = viewModel::setSaveHistory,
                            onFloatingBubbleChange = viewModel::setFloatingBubbleEnabled
                        )
                    }

                    composable(ROUTE_SCANNER) {
                        InAppScannerScreen(
                            initialFlashEnabled = uiState.settings.autoFlashEnabled,
                            vibrationEnabled = uiState.settings.vibrationEnabled,
                            onClose = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
