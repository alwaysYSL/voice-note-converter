package com.example.ui

import android.app.Application
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryScreenHistoryListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearHistory()
    }

    @After
    fun tearDown() {
        clearHistory()
    }

    @Test
    fun `history exposes search filter and sort controls`() {
        insertHistory(id = 201, originalFileName = "Meeting.m4a")

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNode(hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("Semua").assertExists()
        composeRule.onNodeWithText("Belum dikirim").assertExists()
        composeRule.onNodeWithText("Sudah dikirim").assertExists()
        composeRule.onNodeWithContentDescription("Urutkan riwayat").assertExists()
    }

    @Test
    fun `search query hides nonmatching rows`() {
        insertHistory(id = 202, originalFileName = "Meeting.m4a")
        insertHistory(id = 203, originalFileName = "Ide.m4a")

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithText("Meeting.m4a").assertExists()
        composeRule.onNodeWithText("Ide.m4a").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextInput("meeting")

        composeRule.onNodeWithText("Meeting.m4a").assertExists()
        composeRule.onNodeWithText("Ide.m4a").assertDoesNotExist()
    }

    @Test
    fun `selection mode shows selected count and bulk delete action`() {
        insertHistory(id = 204, originalFileName = "Meeting.m4a")

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithContentDescription("Pilih file").performClick()
        composeRule.onNodeWithTag("history_item_204").performClick()

        composeRule.onNodeWithText("Ketuk file untuk memilih").assertExists()
        composeRule.onNodeWithText("Hapus terpilih").assertExists()
    }

    @Test
    fun `raw row taps keep multiple history items selected`() {
        insertHistory(id = 207, originalFileName = "First.m4a")
        insertHistory(id = 208, originalFileName = "Second.m4a")

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithContentDescription("Pilih file").performClick()
        composeRule.onNodeWithContentDescription("Pilih First.m4a").assertExists()
        composeRule.onNodeWithTag("history_item_207").performTouchInput { click() }
        composeRule.onNodeWithTag("history_item_208").performTouchInput { click() }

        composeRule.onNode(hasText("2 dipilih") and hasTestTag("history_selection_count"))
            .assertExists()
    }

    @Test
    fun `collapsed history section hides its rows`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        insertHistory(id = 205, originalFileName = "HariIni.m4a", createdAt = now)
        insertHistory(id = 206, originalFileName = "Kemarin.m4a", createdAt = yesterday)

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithTag("history_list")
            .performScrollToNode(hasText("Kemarin.m4a"))
        composeRule.onNodeWithText("Kemarin.m4a").assertExists()
        composeRule.onNodeWithTag("history_list")
            .performScrollToNode(hasText("Kemarin"))
        composeRule.onNodeWithText("Kemarin").performClick()
        composeRule.onNodeWithText("Kemarin.m4a").assertDoesNotExist()
    }

    @Test
    fun `history list can reach the tail with many rows`() {
        repeat(160) { index ->
            insertHistory(
                id = 300L + index,
                originalFileName = "Bulk-$index.m4a",
                createdAt = System.currentTimeMillis() - index * 60_000L
            )
        }

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithTag("history_list")
            .performScrollToNode(hasText("Bulk-159.m4a"))
        composeRule.onNodeWithText("Bulk-159.m4a").assertExists()
    }

    private fun insertHistory(
        id: Long,
        originalFileName: String,
        createdAt: Long = System.currentTimeMillis()
    ) = runBlocking {
        AppDatabase.getDatabase(app).conversionHistoryDao().insert(
            ConversionHistory(
                id = id,
                originalFileName = originalFileName,
                outputFileName = "VN_$id.ogg",
                outputFilePath = "content://voice-note/$id.ogg",
                durationSeconds = 30,
                fileSizeBytes = 256,
                waveform = "[8,12,10]",
                bitrateKbps = 32,
                createdAt = createdAt
            )
        )
    }

    private fun clearHistory() = runBlocking {
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        dao.getAllHistory().first().forEach { dao.deleteById(it.id) }
    }
}
