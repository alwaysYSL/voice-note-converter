package com.aistudio.voicenote.cvtr.telegram

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramSenderB5Test {
    private val sharedUri = Uri.parse("content://com.aistudio.voicenote.cvtr.voice-note/shared.ogg")

    @Test
    fun `multiple Telegram packages receive chooser URI grant metadata`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        installTelegramPackage(context, "org.telegram.messenger")
        installTelegramPackage(context, "org.telegram.messenger.web")

        val result = TelegramSender { _, _ -> sharedUri }
            .sendVoiceNoteViaTelegramApp(context, sharedUri)

        assertTrue(result is SendResult.IntentLaunched)
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(sharedUri, chooser.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun `single Telegram package still launches direct send intent`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        installTelegramPackage(context, "org.telegram.messenger")

        val result = TelegramSender { _, _ -> sharedUri }
            .sendVoiceNoteViaTelegramApp(context, sharedUri)

        assertTrue(result is SendResult.IntentLaunched)
        val launch = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, launch.action)
        assertEquals("org.telegram.messenger", launch.`package`)
        assertEquals(sharedUri, launch.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(launch.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private fun installTelegramPackage(context: Application, packageName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        )
    }
}
