package com.aistudio.voicenote.cvtr.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftClipEntity
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftDao
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftEntity
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftTrackEntity

@Database(
    entities = [
        ConversionHistory::class,
        EditorDraftEntity::class,
        EditorDraftTrackEntity::class,
        EditorDraftClipEntity::class,
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(DeliveryStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversionHistoryDao(): ConversionHistoryDao
    abstract fun editorDraftDao(): EditorDraftDao

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
                    "ALTER TABLE `conversion_history` ADD COLUMN `editorExportAttemptId` TEXT"
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
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversion_history_editorExportAttemptId` " +
                        "ON `conversion_history` (`editorExportAttemptId`)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `editor_drafts` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `exportPreset` TEXT NOT NULL,
                        `selectedClipId` TEXT,
                        `playheadMs` INTEGER NOT NULL,
                        `sourceVersion` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `editor_draft_tracks` (
                        `draftId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `volume` REAL NOT NULL,
                        `muted` INTEGER NOT NULL,
                        PRIMARY KEY(`draftId`, `trackId`),
                        FOREIGN KEY(`draftId`) REFERENCES `editor_drafts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `editor_draft_clips` (
                        `draftId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `clipId` TEXT NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `originalSourceUri` TEXT NOT NULL,
                        `sourceFileName` TEXT NOT NULL,
                        `sourceDurationMs` INTEGER NOT NULL,
                        `sourceStartMs` INTEGER NOT NULL,
                        `sourceEndMs` INTEGER NOT NULL,
                        `timelineStartMs` INTEGER NOT NULL,
                        `fadeInMs` INTEGER NOT NULL,
                        `fadeOutMs` INTEGER NOT NULL,
                        `gain` REAL NOT NULL,
                        `pitchSemitones` REAL NOT NULL,
                        `speed` REAL NOT NULL,
                        `processedCacheKey` TEXT,
                        `cleanupStrength` TEXT,
                        `cleanupNormalized` INTEGER NOT NULL,
                        `cleanupAlgorithmVersion` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`draftId`, `clipId`),
                        FOREIGN KEY(`draftId`) REFERENCES `editor_drafts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`draftId`, `trackId`) REFERENCES `editor_draft_tracks`(`draftId`, `trackId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editor_draft_tracks_draftId` " +
                        "ON `editor_draft_tracks` (`draftId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editor_draft_tracks_draftId_sortOrder` " +
                        "ON `editor_draft_tracks` (`draftId`, `sortOrder`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editor_draft_clips_draftId` " +
                        "ON `editor_draft_clips` (`draftId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_editor_draft_clips_draftId_trackId` " +
                        "ON `editor_draft_clips` (`draftId`, `trackId`)"
                )
                // A hand-built v5 fixture may omit indices that Room's v5 schema exports. Keep
                // the final schema valid for those databases as well as normal app upgrades.
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
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversion_history_editorExportAttemptId` " +
                        "ON `conversion_history` (`editorExportAttemptId`)"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
        )

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
