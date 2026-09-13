package com.aistudio.voicenote.cvtr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.audio.OggOpusWriter
import com.aistudio.voicenote.cvtr.audio.AudioPreviewPlayer
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.ui.MainUiState
import com.aistudio.voicenote.cvtr.ui.HistoryViewModel
import com.aistudio.voicenote.cvtr.data.local.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `database migration drops unused contacts without destructive reset`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val databaseName = "migration_test.db"
    context.deleteDatabase(databaseName)
    val path = context.getDatabasePath(databaseName)
    path.parentFile?.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
      database.execSQL(
        """
        CREATE TABLE `recent_contacts` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `chatId` INTEGER NOT NULL,
          `name` TEXT NOT NULL,
          `username` TEXT,
          `phone` TEXT,
          `avatarColorHex` TEXT NOT NULL,
          `lastUsedAt` INTEGER NOT NULL
        )
        """.trimIndent()
      )
      database.execSQL(
        "INSERT INTO recent_contacts " +
          "(chatId, name, username, phone, avatarColorHex, lastUsedAt) " +
          "VALUES (42, 'Alice', NULL, NULL, '#0284C7', 1)"
      )
      database.version = 1
    }

    val migrated = androidx.room.Room.databaseBuilder(
      context,
      com.aistudio.voicenote.cvtr.data.local.AppDatabase::class.java,
      databaseName
    ).addMigrations(*com.aistudio.voicenote.cvtr.data.local.AppDatabase.ALL_MIGRATIONS).build()
    try {
      val tables = migrated.openHelper.readableDatabase.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'recent_contacts'"
      ).use { it.count }
      assertEquals(0, tables)
      assertNull(runBlocking { migrated.conversionHistoryDao().getById(1) })
    } finally {
      migrated.close()
      context.deleteDatabase(databaseName)
    }
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Voice Note Converter", appName)
  }

  @Test
  fun `verify ogg opus header generation`() {
    val outputStream = ByteArrayOutputStream()
    val writer = OggOpusWriter(outputStream)
    writer.writeHeader(sampleRate = 48000, channels = 1)
    writer.writeAudioPacket(
      packetData = ByteArray(60) { 0x78.toByte() },
      samplesInPacket = 960L,
      isLast = true
    )
    writer.close()

    val bytes = outputStream.toByteArray()
    assertTrue("Ogg container must start with OggS magic", bytes.size > 28)
    assertEquals('O'.code.toByte(), bytes[0])
    assertEquals('g'.code.toByte(), bytes[1])
    assertEquals('g'.code.toByte(), bytes[2])
    assertEquals('S'.code.toByte(), bytes[3])
  }

  @Test
  fun `ogg writer batches adjacent audio packets into one page`() {
    val outputStream = ByteArrayOutputStream()
    val writer = OggOpusWriter(outputStream)
    writer.writeHeader()
    writer.writeAudioPacket(ByteArray(60), samplesInPacket = 960L)
    writer.writeAudioPacket(ByteArray(60), samplesInPacket = 960L, isLast = true)
    writer.close()

    val pageCount = outputStream.toByteArray()
      .toList()
      .windowed(4)
      .count { it == listOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()) }
    assertEquals("two headers and one batched audio page", 3, pageCount)
  }

  @Test
  fun `ogg writer always marks the final page as end of stream`() {
    val outputStream = ByteArrayOutputStream()
    val writer = OggOpusWriter(outputStream)
    writer.writeHeader()
    writer.writeAudioPacket(ByteArray(4000), samplesInPacket = 960L)
    writer.close()

    val bytes = outputStream.toByteArray()
    val pageOffsets = bytes.indices.filter { index ->
      index + 5 < bytes.size && bytes[index] == 'O'.code.toByte() &&
        bytes[index + 1] == 'g'.code.toByte() && bytes[index + 2] == 'g'.code.toByte() &&
        bytes[index + 3] == 'S'.code.toByte()
    }
    assertTrue(pageOffsets.isNotEmpty())
    assertEquals(0x04, bytes[pageOffsets.last() + 5].toInt() and 0x04)
  }

  @Test
  fun `ogg writer splits pages before exceeding 255 lacing segments`() {
    val outputStream = ByteArrayOutputStream()
    val writer = OggOpusWriter(outputStream)
    writer.writeHeader()
    repeat(300) {
      writer.writeAudioPacket(byteArrayOf(0), samplesInPacket = 120L)
    }
    writer.close()

    val bytes = outputStream.toByteArray()
    val pageOffsets = bytes.indices.filter { index ->
      index + 26 < bytes.size && bytes[index] == 'O'.code.toByte() &&
        bytes[index + 1] == 'g'.code.toByte() && bytes[index + 2] == 'g'.code.toByte() &&
        bytes[index + 3] == 'S'.code.toByte()
    }
    assertEquals("two header pages plus two audio pages", 4, pageOffsets.size)
    assertEquals(255, bytes[pageOffsets[2] + 26].toInt() and 0xFF)
    assertEquals(45, bytes[pageOffsets[3] + 26].toInt() and 0xFF)
  }

  @Test
  fun `ogg writer stores encoder preskip and delay adjusted final granule`() {
    val outputStream = ByteArrayOutputStream()
    val writer = OggOpusWriter(outputStream)
    writer.writeHeader(preSkipSamples = 312)
    writer.writeAudioPacket(ByteArray(60), samplesInPacket = 960L)
    writer.close(finalGranulePosition = 812L)

    val bytes = outputStream.toByteArray()
    val opusHeadOffset = 28
    val preSkip = ByteBuffer.wrap(bytes, opusHeadOffset + 10, 2)
      .order(ByteOrder.LITTLE_ENDIAN)
      .short
      .toInt() and 0xFFFF
    val finalPage = bytes.indices.last { index ->
      index + 13 < bytes.size && bytes[index] == 'O'.code.toByte() &&
        bytes[index + 1] == 'g'.code.toByte() && bytes[index + 2] == 'g'.code.toByte() &&
        bytes[index + 3] == 'S'.code.toByte()
    }
    val finalGranule = ByteBuffer.wrap(bytes, finalPage + 6, 8)
      .order(ByteOrder.LITTLE_ENDIAN)
      .long

    assertEquals(312, preSkip)
    assertEquals(812L, finalGranule)
  }

  @Test
  fun `opus preskip is read from Android codec nanosecond CSD`() {
    val delayNs = 6_500_000L
    val delayBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .putLong(delayNs)
      .apply { flip() }
    val format = android.media.MediaFormat().apply {
      setByteBuffer("csd-1", delayBuffer)
    }

    assertEquals(312, com.aistudio.voicenote.cvtr.audio.opusPreSkipSamples(format))
  }

  @Test
  fun `telegram sender detects missing file gracefully`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sender = com.aistudio.voicenote.cvtr.telegram.TelegramSender()
    val nonExistentFile = java.io.File(context.cacheDir, "non_existent.ogg")
    val result = sender.sendVoiceNoteViaTelegramApp(context, nonExistentFile)
    assertTrue(result is com.aistudio.voicenote.cvtr.telegram.SendResult.Failure)
  }

  @Test
  fun `telegram sender detects a supported alternate Telegram client`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageName = "org.telegram.messenger.web"
    shadowOf(context.packageManager).installPackage(
      PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
      }
    )

    assertTrue(com.aistudio.voicenote.cvtr.telegram.TelegramSender().isTelegramInstalled(context))
  }

  @Test
  fun `telegram sender returns every installed supported client for chooser`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    listOf("org.telegram.messenger", "org.telegram.messenger.web").forEach { packageName ->
      shadowOf(context.packageManager).installPackage(
        PackageInfo().apply {
          this.packageName = packageName
          applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        }
      )
    }
    val actual = runCatching {
      com.aistudio.voicenote.cvtr.telegram.TelegramSender::class.java
        .getDeclaredMethod("findInstalledTelegramPackages", Context::class.java)
        .invoke(com.aistudio.voicenote.cvtr.telegram.TelegramSender(), context) as List<*>
    }.getOrNull()

    assertEquals(listOf("org.telegram.messenger", "org.telegram.messenger.web"), actual)
  }

  @Test
  fun `history share records opening separately from manual delivery confirmation`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val packageName = "org.telegram.messenger"
    shadowOf(context.packageManager).installPackage(
      PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
      }
    )
    val item = ConversionHistory(
      originalFileName = "meeting.m4a",
      outputFileName = "VN_share.ogg",
      outputFilePath = "content://com.aistudio.voicenote.cvtr.voice-note/share.ogg",
      durationSeconds = 12,
      fileSizeBytes = 256,
      waveform = "[8,12,10]",
      bitrateKbps = 32,
      createdAt = 1L
    )
    val database = com.aistudio.voicenote.cvtr.data.local.AppDatabase.getDatabase(context)
    val id = runBlocking { database.conversionHistoryDao().insert(item) }

    val viewModel = HistoryViewModel(context)
    viewModel.shareItem(item.copy(id = id))
    shadowOf(Looper.getMainLooper()).idle()

    val opened = runBlocking {
      withTimeout(5_000) {
        var current: ConversionHistory?
        do {
          shadowOf(Looper.getMainLooper()).idle()
          current = database.conversionHistoryDao().getById(id)
          if (current?.deliveryStatus != DeliveryStatus.SHARE_OPENED) {
            kotlinx.coroutines.delay(25)
          }
        } while (current?.deliveryStatus != DeliveryStatus.SHARE_OPENED)
        current
      }
    }
    assertEquals(DeliveryStatus.SHARE_OPENED, opened?.deliveryStatus)

    viewModel.confirmSent(item.copy(id = id))
    shadowOf(Looper.getMainLooper()).idle()
    val confirmed = runBlocking {
      withTimeout(5_000) {
        var current: ConversionHistory?
        do {
          shadowOf(Looper.getMainLooper()).idle()
          current = database.conversionHistoryDao().getById(id)
          if (current?.deliveryStatus != DeliveryStatus.CONFIRMED_SENT) {
            kotlinx.coroutines.delay(25)
          }
        } while (current?.deliveryStatus != DeliveryStatus.CONFIRMED_SENT)
        current
      }
    }
    assertEquals(DeliveryStatus.CONFIRMED_SENT, confirmed?.deliveryStatus)
    viewModel.clearMessage()
  }

  @Test
  fun `history deletion removes a legacy file path`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val file = java.io.File(context.filesDir, "legacy_voice_note.ogg")
    file.writeBytes(byteArrayOf(1, 2, 3))

    val deleted = VoiceNoteStorage.deleteFromStorage(context, file.absolutePath)

    assertTrue(deleted)
    assertTrue(!file.exists())
  }

  @Test
  fun `preview source prefers converted audio over original input`() {
    val original = android.net.Uri.parse("content://media/original")
    val converted = android.net.Uri.parse("content://media/converted")
    val state = MainUiState(selectedFileUri = original, convertedUri = converted)
    val actual = runCatching {
      MainUiState::class.java.getDeclaredMethod("previewUri")
        .invoke(state) as android.net.Uri
    }.getOrNull()

    assertEquals(converted, actual)
  }

  @Test
  fun `stopping playback clears a temporary trim clip`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val player = AudioPreviewPlayer(
      context,
      CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )
    try {
      player.setClipping(1_200L, 3_000L)
      player.stop()

      val startField = AudioPreviewPlayer::class.java.getDeclaredField("clipStart").apply {
        isAccessible = true
      }
      val endField = AudioPreviewPlayer::class.java.getDeclaredField("clipEnd").apply {
        isAccessible = true
      }

      assertEquals(0L, startField.getLong(player))
      assertEquals(Long.MAX_VALUE, endField.getLong(player))
    } finally {
      player.release()
    }
  }

  @Test
  fun `file uri is converted to a FileProvider content uri before sharing`() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val packageName = "org.telegram.messenger"
    shadowOf(context.packageManager).installPackage(
      PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
      }
    )
    val file = java.io.File(context.filesDir, "legacy_share.ogg").apply { writeBytes(byteArrayOf(1)) }

    val result = com.aistudio.voicenote.cvtr.telegram.TelegramSender { _, _ ->
      android.net.Uri.parse("content://com.aistudio.voicenote.cvtr.fileprovider/shared/legacy_share.ogg")
    }.sendVoiceNoteViaTelegramApp(
      context,
      android.net.Uri.fromFile(file)
    )
    assertTrue("unexpected result: $result", result is com.aistudio.voicenote.cvtr.telegram.SendResult.IntentLaunched)
    val launched = shadowOf(context).nextStartedActivity
    val sharedUri = launched.getParcelableExtra(
      android.content.Intent.EXTRA_STREAM,
      android.net.Uri::class.java
    )

    assertEquals("content", sharedUri?.scheme)
  }
}
