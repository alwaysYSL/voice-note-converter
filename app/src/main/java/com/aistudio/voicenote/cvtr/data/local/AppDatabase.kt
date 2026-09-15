package com.aistudio.voicenote.cvtr.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversionHistory::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(DeliveryStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversionHistoryDao(): ConversionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `pitchSemitones` REAL"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `conversion_history_new` (
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
                        `deliveryStatus` TEXT NOT NULL DEFAULT 'READY',
                        `deliveryTarget` TEXT,
                        `shareOpenedAt` INTEGER,
                        `confirmedSentAt` INTEGER,
                        `pitchSemitones` REAL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `conversion_history_new` (
                        `id`,
                        `originalFileName`,
                        `outputFileName`,
                        `outputFilePath`,
                        `durationSeconds`,
                        `fileSizeBytes`,
                        `waveform`,
                        `bitrateKbps`,
                        `trimStartMs`,
                        `trimEndMs`,
                        `createdAt`,
                        `deliveryStatus`,
                        `deliveryTarget`,
                        `shareOpenedAt`,
                        `confirmedSentAt`,
                        `pitchSemitones`
                    )
                    SELECT
                        `id`,
                        `originalFileName`,
                        `outputFileName`,
                        `outputFilePath`,
                        `durationSeconds`,
                        `fileSizeBytes`,
                        `waveform`,
                        `bitrateKbps`,
                        `trimStartMs`,
                        `trimEndMs`,
                        `createdAt`,
                        CASE WHEN `sentTo` IS NULL
                            THEN 'READY'
                            ELSE 'SHARE_OPENED'
                        END,
                        `sentTo`,
                        `sentAt`,
                        NULL,
                        `pitchSemitones`
                    FROM `conversion_history`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `conversion_history`")
                db.execSQL("ALTER TABLE `conversion_history_new` RENAME TO `conversion_history`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_createdAt` " +
                        "ON `conversion_history` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_shareOpenedAt` " +
                        "ON `conversion_history` (`shareOpenedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_deliveryStatus` " +
                        "ON `conversion_history` (`deliveryStatus`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_originalFileName` " +
                        "ON `conversion_history` (`originalFileName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_outputFileName` " +
                        "ON `conversion_history` (`outputFileName`)"
                )
                db.execSQL("DROP TABLE IF EXISTS `recent_contacts`")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `editorSourceHistoryId` INTEGER"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_createdAt` " +
                        "ON `conversion_history` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_shareOpenedAt` " +
                        "ON `conversion_history` (`shareOpenedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_deliveryStatus` " +
                        "ON `conversion_history` (`deliveryStatus`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_originalFileName` " +
                        "ON `conversion_history` (`originalFileName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversion_history_outputFileName` " +
                        "ON `conversion_history` (`outputFileName`)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_note_converter.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
