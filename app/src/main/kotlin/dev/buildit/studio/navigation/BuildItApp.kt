package dev.buildit.studio.navigation

import dev.buildit.feature.libraries.LibrariesScreen
import dev.buildit.feature.libraries.LibrariesViewModel
import dev.buildit.feature.libraries.R as LibrariesR
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
import androidx.compose.ui.platform.LocalContext
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
import dev.buildit.feature.blocks.ComponentsScreen
import dev.buildit.feature.blocks.EventsScreen
import dev.buildit.feature.blocks.BlocksViewModel
import dev.buildit.feature.blocks.R as BlocksR
import dev.buildit.feature.code.CodeScreen
import dev.buildit.feature.code.CodeViewModel
import dev.buildit.feature.code.R as CodeR
import dev.buildit.feature.build.BuildScreen
import dev.buildit.feature.ai.AiScreen
import dev.buildit.feature.ai.AiViewModel
import dev.buildit.feature.console.ConsoleLog
import dev.buildit.feature.console.ConsoleScreen
import dev.buildit.feature.console.ConsoleViewModel
import dev.buildit.feature.build.BuildViewModel
import dev.buildit.feature.build.R as BuildR
import dev.buildit.feature.ai.R as AiR
import dev.buildit.feature.console.R as ConsoleR
import dev.buildit.studio.ApkActions
import dev.buildit.feature.designer.DesignerScreen
import dev.buildit.feature.designer.DesignerViewModel
import dev.buildit.feature.designer.R as DesignerR
import dev.buildit.feature.workspace.WorkspaceExtraTab
import dev.buildit.feature.workspace.AssetsScreen
import dev.buildit.feature.workspace.AssetsViewModel
import dev.buildit.feature.workspace.assetsIcon
import dev.buildit.feature.workspace.assetsTitle
import dev.buildit.engine.project.AssetKind
import dev.buildit.feature.workspace.WorkspaceScreen
import dev.buildit.feature.workspace.WorkspaceViewModel
import dev.buildit.studio.AppContainer
import dev.buildit.studio.R
import kotlin.reflect.KClass

private const val AI_TAB = "ai"
private const val BUILD_TAB = "build"
private const val DESIGN_TAB = "design"
private const val EVENTS_TAB = "events"
private const val COMPONENTS_TAB = "components"

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
                        initializer { DesignerViewModel(id, container.projectRepository, container.settingsRepository, container.dispatchers, container.collection) }
                    })
                    val blocks: BlocksViewModel = viewModel(key = "bk-$id", factory = viewModelFactory {
                        initializer { BlocksViewModel(id, container.projectRepository, container.settingsRepository, container.dispatchers, collection = container.collection) }
                    })
                    val code: CodeViewModel = viewModel(key = "cd-$id", factory = viewModelFactory {
                        initializer { CodeViewModel(id, container.projectRepository, container.settingsRepository, container.dispatchers) }
                    })
                    val build: BuildViewModel = viewModel(key = "bd-$id", factory = viewModelFactory {
                        initializer { BuildViewModel(id, container.projectRepository, container.toolchain, container.debugKeystore, container.dispatchers, console = container.consoleHost, maven = container.maven, kotlin = container.kotlinCompiler, keyring = container.releaseKeys) }
                    })
                    val libraries: LibrariesViewModel = viewModel(key = "lb-$id", factory = viewModelFactory {
                        initializer { LibrariesViewModel(id, container.projectRepository, container.maven, container.dispatchers) }
                    })
                    val console: ConsoleViewModel = viewModel(key = "cn-$id", factory = viewModelFactory {
                        initializer { ConsoleViewModel(id, container.projectRepository, ConsoleLog.shared, container.dispatchers) }
                    })
                    val ai: AiViewModel = viewModel(key = "ai-$id", factory = viewModelFactory {
                        initializer {
                            AiViewModel(
                                id, container.projectRepository, container.aiSettings, container.dispatchers,
                                buildProblems = { build.state.value.diagnostics.map { it.toString() } },
                                onFilesWritten = { paths -> paths.forEach(code::externalChange) },
                            )
                        }
                    })
                    val assets: AssetsViewModel = viewModel(key = "as-$id", factory = viewModelFactory {
                        initializer { AssetsViewModel(id, container.projectRepository, container.dispatchers, container.collection) }
                    })
                    val context = LocalContext.current
                    val install = { apk: java.io.File -> ApkActions.install(context, apk) { m -> context.getString(BuildR.string.bd_install_failed, m) } }
                    val share = { apk: java.io.File -> ApkActions.share(context, apk) }
                    val tabs = remember(designer, blocks, code, build, console, ai, libraries, assets) {
                        listOf(
                            // Main editors
                            WorkspaceExtraTab(title = { stringResource(DesignerR.string.ds_tab) }, content = { scope -> DesignerScreen(designer, onExit = scope.leave) }, icon = BiIcons.Widgets, key = DESIGN_TAB),
                            WorkspaceExtraTab(title = { stringResource(BlocksR.string.bk_events_tab) }, content = { scope -> EventsScreen(blocks, onExit = scope.leave) }, icon = BiIcons.Event, key = EVENTS_TAB),
                            WorkspaceExtraTab(title = { stringResource(BlocksR.string.bk_components_tab) }, content = { ComponentsScreen(blocks) }, icon = BiIcons.Component, key = COMPONENTS_TAB),
                            // Project tools (drawer)
                            WorkspaceExtraTab(
                                title = { stringResource(CodeR.string.cd_tab) },
                                content = { scope -> CodeScreen(code, onExit = scope.leave) },
                                opens = { path -> CodeViewModel.canOpen(path).also { if (it) code.open(path) } },
                                icon = BiIcons.Code, tool = true,
                            ),
                            WorkspaceExtraTab(title = { stringResource(LibrariesR.string.lb_tab) }, content = { LibrariesScreen(libraries) }, icon = BiIcons.Library, tool = true),
                            WorkspaceExtraTab(title = { assetsTitle(AssetKind.Image) }, content = { AssetsScreen(assets, AssetKind.Image) }, icon = assetsIcon(AssetKind.Image), tool = true),
                            WorkspaceExtraTab(title = { assetsTitle(AssetKind.Sound) }, content = { AssetsScreen(assets, AssetKind.Sound) }, icon = assetsIcon(AssetKind.Sound), tool = true),
                            WorkspaceExtraTab(title = { assetsTitle(AssetKind.Font) }, content = { AssetsScreen(assets, AssetKind.Font) }, icon = assetsIcon(AssetKind.Font), tool = true),
                            WorkspaceExtraTab(
                                title = { stringResource(BuildR.string.bd_tab) },
                                content = { scope ->
                                    BuildScreen(
                                        build,
                                        onOpenFile = { path, line -> if (CodeViewModel.canOpen(path)) { code.reveal(path, line); scope.openFile(path) } },
                                        onInstall = install,
                                        onShare = share,
                                    )
                                },
                                icon = BiIcons.Build, tool = true, key = BUILD_TAB,
                            ),
                            WorkspaceExtraTab(
                                title = { stringResource(ConsoleR.string.cn_tab) },
                                content = { scope ->
                                    val askTemplate = stringResource(AiR.string.ai_ask_crash)
                                    ConsoleScreen(
                                        console,
                                        onOpenSource = { path, line -> if (CodeViewModel.canOpen(path)) { code.reveal(path, line); scope.openFile(path) } },
                                        onAskAi = { text, path -> ai.prefill(askTemplate.format(text), listOfNotNull(path)); scope.showTab(AI_TAB) },
                                    )
                                },
                                icon = BiIcons.Terminal, tool = true,
                            ),
                            WorkspaceExtraTab(title = { stringResource(AiR.string.ai_tab) }, content = { AiScreen(ai) }, key = AI_TAB, icon = BiIcons.Assistant, tool = true),
                        )
                    }
                    WorkspaceScreen(
                        vm, onBack = { nav.popBackStack() }, extraTabs = tabs,
                        bottomBar = { scope -> RunBar(blocks, designer, build, scope, BUILD_TAB, onInstall = install, onShare = share) },
                    )
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
