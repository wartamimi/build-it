package dev.buildit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Index row for a project. The project files on disk are the source of truth; this table is a
 * cache for fast listing/sorting and can be rebuilt from a workspace scan.
 */
@Entity(tableName = "projects", indices = [Index("last_opened_at"), Index(value = ["directory"], unique = true)])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    val directory: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
)
