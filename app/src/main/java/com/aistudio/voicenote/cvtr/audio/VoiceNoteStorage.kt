package com.aistudio.voicenote.cvtr.audio

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object VoiceNoteStorage {
    fun generateFileName(): String =
        "VN_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}_" +
            "${UUID.randomUUID().toString().take(8)}.ogg"

    fun saveToPublicStorage(context: Context, cacheFile: File, fileName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VoiceNoteConverter")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Gagal membuat file MediaStore")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(cacheFile).use { input -> input.copyTo(output) }
                } ?: error("Gagal menulis file MediaStore")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "Gagal mempublikasikan file MediaStore"
                }
                return uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "VoiceNoteConverter"
        )
        check(directory.exists() || directory.mkdirs()) { "Gagal membuat folder penyimpanan" }
        val file = File(directory, fileName)
        return try {
            FileInputStream(cacheFile).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("audio/ogg"),
                null
            )
            Uri.fromFile(file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    fun getStorageFolder(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "VoiceNoteConverter"
    )

    fun deleteFromStorage(context: Context, path: String): Boolean = try {
        val uri = path.toUri()
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            File(if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path.orEmpty() else path).delete()
        }
    } catch (_: Exception) {
        false
    }

    fun fileExists(context: Context, uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                } catch (_: SecurityException) {
                    true
                }
            } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val path = uri.path ?: return false
                File(path).exists()
            } else {
                File(uriString).exists()
            }
        } catch (_: Exception) {
            false
        }
    }
}
