package dev.buildit.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("UPDATE projects SET last_opened_at = :time WHERE id = :id")
    suspend fun markOpened(id: String, time: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM projects")
    suspend fun clear()

    @Query("SELECT * FROM projects")
    suspend fun all(): List<ProjectEntity>

    /** Replaces the whole index with [entities] while keeping existing last-opened times. */
    @Transaction
    suspend fun replaceAll(entities: List<ProjectEntity>) {
        val opened = all().associate { it.id to it.lastOpenedAt }
        clear()
        entities.forEach { upsert(it.copy(lastOpenedAt = it.lastOpenedAt ?: opened[it.id])) }
    }
}
