package com.aistudio.voicenote.cvtr.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration_v3_test.db"
    private val databaseV4Name = "migration_v4_test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase(databaseV4Name)
    }

    @Test
    fun `version three sent rows migrate to opened status without claiming delivery`() {
        context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { database ->
            database.execSQL(
                """
                CREATE TABLE `conversion_history` (
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
                    `sentAt` INTEGER,
                    `pitchSemitones` REAL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO conversion_history (
                    id, originalFileName, outputFileName, outputFilePath,
                    durationSeconds, fileSizeBytes, waveform, bitrateKbps,
                    trimStartMs, trimEndMs, createdAt, sentTo, sentAt, pitchSemitones
                ) VALUES (
                    7, 'meeting.m4a', 'VN_meeting.ogg', 'content://voice-note/7.ogg',
                    12, 512, '[8,12]', 32, NULL, NULL, 1234, 'Telegram', 5678, 2.0
                )
                """.trimIndent()
            )
            database.version = 3
        }

        val migrated = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseName
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val row = runBlocking { migrated.conversionHistoryDao().getById(7) }

            assertEquals(DeliveryStatus.SHARE_OPENED, row?.deliveryStatus)
            assertEquals("Telegram", row?.deliveryTarget)
            assertEquals(5678L, row?.shareOpenedAt)
            assertNull(row?.confirmedSentAt)
            assertEquals(2.0f, row?.pitchSemitones)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `version four rows gain nullable editor source history`() {
        context.deleteDatabase(databaseV4Name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseV4Name), null).use { database ->
            database.execSQL(
                """
                CREATE TABLE `conversion_history` (
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
                    `deliveryStatus` TEXT NOT NULL,
                    `deliveryTarget` TEXT,
                    `shareOpenedAt` INTEGER,
                    `confirmedSentAt` INTEGER,
                    `pitchSemitones` REAL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO conversion_history (
                    id, originalFileName, outputFileName, outputFilePath,
                    durationSeconds, fileSizeBytes, waveform, bitrateKbps, createdAt,
                    deliveryStatus
                ) VALUES (9, 'source.ogg', 'edited.ogg', 'file:///edited.ogg', 1, 10, '[]', 64, 9, 'READY')
                """.trimIndent()
            )
            database.version = 4
        }

        val migrated = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            databaseV4Name
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        try {
            val row = runBlocking { migrated.conversionHistoryDao().getById(9) }
            assertNull(row?.editorSourceHistoryId)
        } finally {
            migrated.close()
        }
    }
}
