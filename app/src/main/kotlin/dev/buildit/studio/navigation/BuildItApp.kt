package dev.buildit.studio.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.buildit.core.designsystem.icon.BiIcons
import dev.buildit.feature.home.HomeScreen
import dev.buildit.feature.home.HomeViewModel
import dev.buildit.feature.projects.ProjectsScreen
import dev.buildit.feature.projects.ProjectsStartAction
import dev.buildit.feature.projects.ProjectsViewModel
import dev.buildit.feature.settings.SettingsScreen
import dev.buildit.feature.settings.SettingsViewModel
import dev.buildit.feature.blocks.BlocksScreen
import dev.buildit.feature.blocks.BlocksViewModel
import dev.buildit.feature.blocks.R as BlocksR
import dev.buildit.feature.designer.DesignerScreen
import dev.buildit.feature.designer.DesignerViewModel
import dev.buildit.feature.designer.R as DesignerR
import dev.buildit.feature.workspace.WorkspaceExtraTab
import dev.buildit.feature.workspace.WorkspaceScreen
import dev.buildit.feature.workspace.WorkspaceViewModel
import dev.buildit.studio.AppContainer
import dev.buildit.studio.R
import kotlin.reflect.KClass

private enum class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector) {
    Home(HomeRoute, HomeRoute::class, R.string.nav_home, BiIcons.Home),
    Projects(ProjectsRoute(), ProjectsRoute::class, R.string.nav_projects, BiIcons.Projects),
    Settings(SettingsRoute, SettingsRoute::class, R.string.nav_settings, BiIcons.Settings),
}

/**
 * App shell: bottom navigation on phones, navigation rail on wide screens (>= 600dp). Only
 * destinations that are implemented appear here; editors are added as their phases land.
 */
@Composable
fun BuildItApp(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val current = TopLevel.entries.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val showNav = current != null

    Scaffold(
        bottomBar = {
            if (showNav && !wide) {
                NavigationBar {
                    TopLevel.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == current,
                            onClick = { nav.navigateTopLevel(t) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (showNav && wide) {
                NavigationRail {
                    TopLevel.entries.forEach { t ->
                        NavigationRailItem(
                            selected = t == current,
                            onClick = { nav.navigateTopLevel(t) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
            NavHost(nav, startDestination = HomeRoute, modifier = Modifier.weight(1f)) {
                composable<HomeRoute> {
                    val vm: HomeViewModel = viewModel(factory = viewModelFactory {
                        initializer { HomeViewModel(container.projectRepository, container.workspaceInfo) }
                    })
                    HomeScreen(
                        vm,
                        onNewProject = { nav.navigate(ProjectsRoute("create")) { launchSingleTop = true } },
                        onImportProject = { nav.navigate(ProjectsRoute("import")) { launchSingleTop = true } },
                        onOpenProject = { nav.navigate(WorkspaceRoute(it)) },
                        onAllProjects = { nav.navigateTopLevel(TopLevel.Projects) },
                    )
                }
                composable<ProjectsRoute> { entry ->
                    val route = entry.toRoute<ProjectsRoute>()
                    val vm: ProjectsViewModel = viewModel(factory = viewModelFactory {
                        initializer { ProjectsViewModel(container.projectRepository) }
                    })
                    ProjectsScreen(
                        vm,
                        onOpenProject = { nav.navigate(WorkspaceRoute(it)) },
                        startAction = when (route.action) {
                            "create" -> ProjectsStartAction.Create
                            "import" -> ProjectsStartAction.Import
                            else -> ProjectsStartAction.None
                        },
                    )
                }
                composable<SettingsRoute> {
                    val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                        initializer { SettingsViewModel(container.settingsRepository, container.projectRepository) }
                    })
                    SettingsScreen(vm, container.appVersion, container.workspaceDir.absolutePath)
                }
                composable<WorkspaceRoute> { entry ->
                    val id = entry.toRoute<WorkspaceRoute>().projectId
                    val vm: WorkspaceViewModel = viewModel(key = "ws-$id", factory = viewModelFactory {
                        initializer { WorkspaceViewModel(id, container.projectRepository, container.dispatchers) }
                    })
                    val designer: DesignerViewModel = viewModel(key = "ds-$id", factory = viewModelFactory {
                        initializer { DesignerViewModel(id, container.projectRepository, container.settingsRepository, container.dispatchers) }
                    })
                    val blocks: BlocksViewModel = viewModel(key = "bk-$id", factory = viewModelFactory {
                        initializer { BlocksViewModel(id, container.projectRepository, container.settingsRepository, container.dispatchers) }
                    })
                    val tabs = remember(designer, blocks) {
                        listOf(
                            WorkspaceExtraTab(title = { stringResource(DesignerR.string.ds_tab) }, content = { leave -> DesignerScreen(designer, onExit = leave) }),
                            WorkspaceExtraTab(title = { stringResource(BlocksR.string.bk_tab) }, content = { leave -> BlocksScreen(blocks, onExit = leave) }),
                        )
                    }
                    WorkspaceScreen(vm, onBack = { nav.popBackStack() }, extraTabs = tabs)
                }
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(t: TopLevel) {
    navigate(t.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
