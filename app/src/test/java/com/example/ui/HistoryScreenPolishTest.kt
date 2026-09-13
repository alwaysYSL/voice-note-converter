package com.example.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryScreenPolishTest {

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
    fun `empty history offers a direct start conversion action`() {
        val viewModel = HistoryViewModel(app)
        var started = false

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(viewModel, onStartConversion = { started = true })
            }
        }

        composeRule.onNodeWithText("Mulai konversi").assertExists()
        composeRule.onNodeWithText("Mulai konversi").performClick()
        composeRule.runOnIdle { check(started) }
    }

    @Test
    fun `history groups entries by relative date`() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        insertHistory(today)
        insertHistory(yesterday)

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithText("Hari ini").assertExists()
        composeRule.onNodeWithText("Kemarin").assertExists()
    }

    @Test
    fun `history header clears the system status area`() {
        insertHistory(System.currentTimeMillis())

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app), statusBarInset = 24.dp)
            }
        }

        val headerTop = composeRule.onNodeWithText("Riwayat")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("history header should not start inside the status area", headerTop >= 36f)
    }

    @Test
    fun `unsent history uses an actionable status and an overflow menu`() {
        insertHistory(System.currentTimeMillis())

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithText("Siap dikirim").assertExists()
        composeRule.onNodeWithContentDescription("Opsi lainnya").assertExists()
        composeRule.onNodeWithContentDescription("Opsi lainnya").performClick()
        composeRule.onNodeWithTag("history_inline_actions").assertExists()
        composeRule.onNodeWithText("Hapus").performClick()
        composeRule.onNodeWithTag("history_delete_confirmation").assertExists()
    }

    @Test
    fun `sent history explains that telegram was opened rather than claiming delivery`() {
        insertHistory(System.currentTimeMillis(), sentTo = "Telegram")

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNodeWithText("Dibuka di Telegram").assertExists()
    }

    @Test
    fun `today metadata uses a compact relative date and middle dot separators`() {
        insertHistory(System.currentTimeMillis())

        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(HistoryViewModel(app))
            }
        }

        composeRule.onNode(hasText("· 01:24 · 312 KB", substring = true)).assertExists()
    }

    private fun insertHistory(createdAt: Long, sentTo: String? = null) = runBlocking {
        AppDatabase.getDatabase(app).conversionHistoryDao().insert(
            ConversionHistory(
                originalFileName = "meeting.m4a",
                outputFileName = "VN_test_${createdAt}.ogg",
                outputFilePath = "content://voice-note/${createdAt}.ogg",
                durationSeconds = 84,
                fileSizeBytes = 312 * 1024L,
                waveform = "[8,12,10]",
                bitrateKbps = 32,
                createdAt = createdAt,
                sentTo = sentTo,
                sentAt = sentTo?.let { createdAt }
            )
        )
    }

    private fun clearHistory() = runBlocking {
        val dao = AppDatabase.getDatabase(app).conversionHistoryDao()
        dao.getAllHistory().first().forEach { dao.deleteById(it.id) }
    }
}
