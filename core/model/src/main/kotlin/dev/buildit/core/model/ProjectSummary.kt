package dev.buildit.core.model

/** Lightweight index entry for a project, as shown on the dashboard and project list. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val packageName: String,
    val directory: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)
