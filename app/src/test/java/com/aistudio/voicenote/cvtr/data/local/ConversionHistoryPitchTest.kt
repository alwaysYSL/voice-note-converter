package com.aistudio.voicenote.cvtr.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversionHistoryPitchTest {
    @Test
    fun `history entity stores optional pitch semitones`() {
        val field = ConversionHistory::class.java.getDeclaredField("pitchSemitones")
        assertNotNull(field)
        assertEquals(Float::class.javaObjectType, field.type)
    }

    @Test
    fun `database schema advances for pitch history`() {
        val database = AppDatabase.getDatabase(
            ApplicationProvider.getApplicationContext<Application>()
        )
        val columnNames = mutableListOf<String>()
        database.openHelper.writableDatabase
            .query("PRAGMA table_info(`conversion_history`)")
            .use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columnNames += cursor.getString(nameIndex)
            }

        assertTrue(columnNames.contains("pitchSemitones"))
    }
}
