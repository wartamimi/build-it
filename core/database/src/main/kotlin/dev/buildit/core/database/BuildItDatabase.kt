package dev.buildit.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class BuildItDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        const val NAME = "buildit.db"

        fun create(context: Context): BuildItDatabase =
            Room.databaseBuilder(context, BuildItDatabase::class.java, NAME)
                // Future schema changes must add explicit Migration objects; the exported schemas
                // in core/database/schemas are the reference for writing and testing them.
                .build()
    }
}
