package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RecentContact::class, ConversionHistory::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentContactDao(): RecentContactDao
    abstract fun conversionHistoryDao(): ConversionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_note_converter.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `originalFileName` TEXT NOT NULL,
                        `outputFileName` TEXT NOT NULL,
                        `outputFilePath` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `waveform` TEXT NOT NULL,
                        `bitrateKbps` INTEGER NOT NULL,
                        `trimStartMs` INTEGER,
                        `trimEndMs` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `sentTo` TEXT,
                        `sentAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `pitchSemitones` REAL"
                )
            }
        }
    }
}
