package com.aistudio.voicenote.cvtr.telegram

import com.aistudio.voicenote.cvtr.audio.AudioConversionResult
import java.io.File

internal data class TelegramCompatibility(
    val summary: String,
    val warning: String? = null,
    val isShareable: Boolean = true
)

internal object TelegramCompatibilityValidator {
    fun validate(result: AudioConversionResult): TelegramCompatibility {
        val warnings = mutableListOf<String>()
        val hasOpusHead = result.outputFile.isOpusContainer()
        if (!hasOpusHead) {
            return TelegramCompatibility(
                summary = summary(result),
                warning = "Container output bukan OGG Opus yang dikenali Telegram.",
                isShareable = false
            )
        }
        if (result.sampleRate != 48_000) {
            warnings += "sample rate ${result.sampleRate} Hz; Telegram biasanya memakai 48 kHz."
        }
        if (result.channels != 1) {
            warnings += "output memiliki ${result.channels} channel; voice note Telegram biasanya mono."
        }
        if (result.bitrateKbps != 32) {
            warnings += "bitrate ${result.bitrateKbps} kbps; target aplikasi adalah 32 kbps."
        }
        if (result.durationSeconds <= 0) {
            warnings += "durasi output tidak valid."
        }
        return TelegramCompatibility(
            summary = summary(result),
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
            isShareable = result.durationSeconds > 0
        )
    }

    private fun summary(result: AudioConversionResult): String =
        "OGG Opus · ${result.channels} channel · ${result.sampleRate / 1_000} kHz · " +
            "${result.bitrateKbps} kbps · ${result.durationSeconds}s"

    private fun File.isOpusContainer(): Boolean {
        if (!isFile || length() < 64L) return false
        return runCatching {
            inputStream().use { input ->
                val bytes = ByteArray(4_096)
                val count = input.read(bytes)
                if (count <= 0) return@use false
                val header = String(bytes, 0, count, Charsets.ISO_8859_1)
                header.startsWith("OggS") && header.contains("OpusHead")
            }
        }.getOrDefault(false)
    }
}
