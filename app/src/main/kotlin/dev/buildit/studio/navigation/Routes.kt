package dev.buildit.studio.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data class ProjectsRoute(val action: String = "none")
@Serializable data object SettingsRoute
@Serializable data class WorkspaceRoute(val projectId: String)
