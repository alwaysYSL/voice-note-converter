package com.aistudio.voicenote.cvtr.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

object MediaInputCache {
    private const val DIRECTORY_NAME = "conversion_inputs"

    fun copyToPersistent(context: Context, uri: Uri): Uri {
        val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        check(directory.isDirectory) { "Gagal membuat cache input konversi" }
        val extension = extensionOf(uri)
        val target = File(directory, "input_${UUID.randomUUID()}$extension")
        try {
            when (uri.scheme) {
                "file" -> FileInputStream(uri.path?.let(::File) ?: error("URI file tidak valid"))
                    .use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                else -> context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("Input media tidak dapat dibaca")
            }
            check(target.length() > 0L) { "Input media kosong" }
            return Uri.fromFile(target)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun delete(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val file = uri.path?.let(::File) ?: return false
        val directory = File(context.filesDir, DIRECTORY_NAME).canonicalFile
        return file.canonicalFile.parentFile == directory &&
            (!file.exists() || file.delete())
    }

    fun cleanup(context: Context, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long): Int {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && nowMs - it.lastModified() >= maxAgeMs }
            .count { it.delete() }
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                index.takeIf { it >= 0 }?.let(cursor::getString)
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun extensionOf(uri: Uri): String {
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        return extension?.let { ".$it" } ?: ".media"
    }
}
