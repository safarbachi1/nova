package com.example.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.i18n.LocalAppStrings
import com.example.ui.components.NovaAtmosphericBackground
import com.example.ui.screens.AppInfoScreen
import com.example.ui.screens.ConfigManagerScreen
import com.example.ui.screens.ConnectionDetailsScreen
import com.example.ui.screens.ConnectionHistoryScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.DiscoveryScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.ServerExplorerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpeedTestScreen
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel
import kotlinx.serialization.Serializable

// 5 Primary Bottom Navigation Destinations
@Serializable object HomeRoute
@Serializable object ServersRoute
@Serializable object DiscoveryRoute
@Serializable object ConfigsRoute
@Serializable object SettingsRoute

// 6 Secondary Destinations
@Serializable object SpeedTestRoute
@Serializable object ConnectionDetailsRoute
@Serializable object FavoritesRoute
@Serializable object ConnectionHistoryRoute
@Serializable object DiagnosticsRoute
@Serializable object AppInfoRoute

private data class PrimaryNavItem(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun NovaVpnApp(viewModel: NovaVpnViewModel) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val pendingConsent by viewModel.pendingVpnConsentIntent.collectAsStateWithLifecycle()
    val pendingUntrusted by viewModel.pendingUntrustedApproval.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    // Official Android VpnService.prepare() consent launcher
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    // Android 13+ runtime notification permission for persistent VPN foreground notification
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(pendingConsent) {
        val intent = pendingConsent ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        vpnConsentLauncher.launch(intent)
    }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissBanner()
    }

    val navItems = listOf(
        PrimaryNavItem(HomeRoute, strings.tabHome, Icons.Rounded.Home, Icons.Outlined.Home, "nav_home"),
        PrimaryNavItem(ServersRoute, strings.tabServers, Icons.Rounded.Public, Icons.Outlined.Public, "nav_servers"),
        PrimaryNavItem(DiscoveryRoute, strings.tabDiscovery, Icons.Rounded.Explore, Icons.Outlined.Explore, "nav_discovery"),
        PrimaryNavItem(ConfigsRoute, strings.tabConfigs, Icons.Rounded.Tune, Icons.Outlined.Tune, "nav_configs"),
        PrimaryNavItem(SettingsRoute, strings.tabSettings, Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings")
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val isPrimaryScreen = navItems.any { item -> currentDest?.hasRoute(item.route::class) == true } || currentDest == null

    fun navigateToPrimary(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    NovaAtmosphericBackground {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!useNavRail && isPrimaryScreen) {
                        NavigationBar(
                            containerColor = nova.glassSurface.copy(alpha = 0.94f),
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            navItems.forEach { item ->
                                val selected = currentDest?.hasRoute(item.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToPrimary(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.testTag(item.testTag)
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
                    if (useNavRail && isPrimaryScreen) {
                        NavigationRail(
                            containerColor = nova.glassSurface.copy(alpha = 0.92f),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(Modifier.height(12.dp))
                            navItems.forEach { item ->
                                val selected = currentDest?.hasRoute(item.route::class) == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToPrimary(item.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label, maxLines = 1) },
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeRoute
                        ) {
                            composable<HomeRoute> {
                                HomeDashboardScreen(
                                    viewModel = viewModel,
                                    onOpenServers = { navigateToPrimary(ServersRoute) },
                                    onOpenSpeedTest = { navController.navigate(SpeedTestRoute) },
                                    onOpenConnectionDetails = { navController.navigate(ConnectionDetailsRoute) },
                                    onOpenFavorites = { navController.navigate(FavoritesRoute) },
                                    onOpenHistory = { navController.navigate(ConnectionHistoryRoute) },
                                    onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) }
                                )
                            }
                            composable<ServersRoute> {
                                ServerExplorerScreen(
                                    viewModel = viewModel,
                                    favoritesOnlyInitial = false,
                                    onNavigateToHome = { navigateToPrimary(HomeRoute) },
                                    onNavigateToDiscovery = { navigateToPrimary(DiscoveryRoute) },
                                    onNavigateToConfigs = { navigateToPrimary(ConfigsRoute) }
                                )
                            }
                            composable<DiscoveryRoute> {
                                DiscoveryScreen(viewModel = viewModel)
                            }
                            composable<ConfigsRoute> {
                                ConfigManagerScreen(
                                    viewModel = viewModel,
                                    onConnectSelected = { navigateToPrimary(HomeRoute) }
                                )
                            }
                            composable<SettingsRoute> {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onOpenSpeedTest = { navController.navigate(SpeedTestRoute) },
                                    onOpenHistory = { navController.navigate(ConnectionHistoryRoute) },
                                    onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) },
                                    onOpenAppInfo = { navController.navigate(AppInfoRoute) }
                                )
                            }
                            composable<SpeedTestRoute> {
                                SpeedTestScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                            }
                            composable<ConnectionDetailsRoute> {
                                ConnectionDetailsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                            }
                            composable<FavoritesRoute> {
                                ServerExplorerScreen(
                                    viewModel = viewModel,
                                    favoritesOnlyInitial = true,
                                    onNavigateToHome = { navigateToPrimary(HomeRoute) },
                                    onNavigateToDiscovery = { navigateToPrimary(DiscoveryRoute) },
                                    onNavigateToConfigs = { navigateToPrimary(ConfigsRoute) }
                                )
                            }
                            composable<ConnectionHistoryRoute> {
                                ConnectionHistoryScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                            }
                            composable<DiagnosticsRoute> {
                                DiagnosticsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                            }
                            composable<AppInfoRoute> {
                                AppInfoScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    // Explicit User Approval Modal for Untrusted Public Configurations
    pendingUntrusted?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelUntrustedApproval() },
            icon = {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = nova.statusWarning)
            },
            title = {
                Text(strings.untrustedWarningTitle, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(strings.untrustedWarningBody, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${target.effectiveTitle}\n${target.server.hostname}:${target.server.port} (${target.server.protocol.displayName})",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = nova.accentCyan
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.approveUntrustedAndConnect() },
                    modifier = Modifier.testTag("approve_untrusted_confirm_button")
                ) {
                    Text(strings.approveAndConnect)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelUntrustedApproval() }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}
