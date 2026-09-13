package com.aistudio.voicenote.cvtr.telegram

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

sealed class SendResult {
    data class IntentLaunched(val details: String) : SendResult()
    data class Failure(val errorMessage: String, val canRetry: Boolean = true) : SendResult()
}

class TelegramSender(
    private val fileUriFactory: (Context, File) -> Uri = ::defaultShareableUri
) {
    companion object {
        private const val TAG = "TelegramSender"
        private val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.telegram.plus"
        )
    }

    fun findInstalledTelegramPackages(context: Context): List<String> = TELEGRAM_PACKAGES.filter { packageName ->
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun findInstalledTelegramPackage(context: Context): String? =
        findInstalledTelegramPackages(context).firstOrNull()

    fun isTelegramInstalled(context: Context): Boolean = findInstalledTelegramPackage(context) != null

    fun sendVoiceNoteViaTelegramApp(
        context: Context,
        oggFile: File,
        contactNameHint: String? = null
    ): SendResult {
        if (!oggFile.exists() || oggFile.length() == 0L) {
            return SendResult.Failure("File hasil konversi tidak ditemukan atau kosong.", canRetry = false)
        }
        return try {
            val uri = fileUriFactory(context, oggFile)
            sendVoiceNoteViaTelegramApp(context, uri, contactNameHint)
        } catch (error: Exception) {
            failure(error)
        }
    }

    fun sendVoiceNoteViaTelegramApp(
        context: Context,
        oggUri: Uri,
        contactNameHint: String? = null
    ): SendResult {
        if (oggUri.scheme == "file") {
            val path = oggUri.path
                ?: return SendResult.Failure("Path voice note tidak valid.", canRetry = false)
            return sendVoiceNoteViaTelegramApp(context, File(path), contactNameHint)
        }
        val installedPackages = findInstalledTelegramPackages(context)
        if (installedPackages.isEmpty()) {
            return SendResult.Failure(
                "Telegram tidak ditemukan. Silakan instal Telegram terlebih dahulu.",
                canRetry = false
            )
        }

        return try {
            val intents = installedPackages.map { packageName ->
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/ogg"
                    putExtra(Intent.EXTRA_STREAM, oggUri)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val launchIntent = if (intents.size == 1) {
                intents.first()
            } else {
                Intent.createChooser(intents.first(), "Pilih aplikasi Telegram").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("", oggUri)
                }
            }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            context.startActivity(launchIntent)
            val hint = contactNameHint?.let { " Pilih chat \"$it\" di Telegram." }.orEmpty()
            SendResult.IntentLaunched(
                "Telegram terbuka dengan voice note terlampir.$hint Selesaikan pengiriman di Telegram."
            )
        } catch (error: Exception) {
            failure(error)
        }
    }

    private fun failure(error: Exception): SendResult.Failure {
        Log.e(TAG, "Gagal membuka Telegram", error)
        val detail = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "kesalahan tidak diketahui"
        return SendResult.Failure(
            "Telegram ditemukan, tetapi voice note gagal dibagikan: $detail",
            canRetry = true
        )
    }
}

private fun defaultShareableUri(context: Context, source: File): Uri {
    val authority = "${context.packageName}.fileprovider"
    return try {
        FileProvider.getUriForFile(context, authority, source)
    } catch (_: IllegalArgumentException) {
        val shareDirectory = File(context.cacheDir, "shared_voice_notes").apply { mkdirs() }
        val cachedCopy = File(shareDirectory, source.name)
        source.copyTo(cachedCopy, overwrite = true)
        FileProvider.getUriForFile(context, authority, cachedCopy)
    }
}
