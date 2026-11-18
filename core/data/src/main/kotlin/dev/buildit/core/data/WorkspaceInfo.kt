package dev.buildit.core.data

import dev.buildit.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

data class StorageUsage(val workspaceBytes: Long, val freeBytes: Long, val workspacePath: String)

interface WorkspaceInfo {
    suspend fun usage(): StorageUsage
}

class FileWorkspaceInfo(private val root: File, private val dispatchers: DispatcherProvider) : WorkspaceInfo {
    override suspend fun usage(): StorageUsage = withContext(dispatchers.io) {
        val size = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        StorageUsage(size, root.usableSpace, root.absolutePath)
    }
}
