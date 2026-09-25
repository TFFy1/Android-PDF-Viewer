package io.github.tffy1.pdfviewer.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import io.github.tffy1.pdfviewer.R
import io.github.tffy1.pdfviewer.ui.library.LibraryScreen
import io.github.tffy1.pdfviewer.ui.settings.SettingsScreen
import io.github.tffy1.pdfviewer.ui.tools.ToolsScreen
import io.github.tffy1.pdfviewer.ui.viewer.ViewerScreen
import kotlin.reflect.KClass

private data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val icon: ImageVector,
    val labelRes: Int,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(LibraryRoute, LibraryRoute::class, Icons.Outlined.LibraryBooks, R.string.nav_library),
    TopLevelDestination(ToolsRoute, ToolsRoute::class, Icons.Outlined.Build, R.string.nav_tools),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, Icons.Outlined.Settings, R.string.nav_settings),
)

fun NavHostController.openDocument(uri: Uri, initialPage: Int = -1) {
    navigate(ViewerRoute(uri.toString(), initialPage)) { launchSingleTop = true }
}

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { top -> destination?.hasRoute(top.routeClass) == true }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(top.icon, contentDescription = null) },
                            label = { Text(stringResource(top.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LibraryRoute,
        ) {
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenDocument = { uri -> navController.openDocument(uri) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable<ToolsRoute> {
                ToolsScreen(
                    onOpenDocument = { uri -> navController.openDocument(uri) },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(modifier = Modifier.padding(innerPadding))
            }
            composable<ViewerRoute> { entry ->
                val route = entry.toRoute<ViewerRoute>()
                ViewerScreen(
                    uri = Uri.parse(route.uri),
                    initialPage = route.initialPage,
                    onBack = { navController.popBackStack() },
                    onOpenDocument = { uri -> navController.openDocument(uri) },
                )
            }
        }
    }
}
