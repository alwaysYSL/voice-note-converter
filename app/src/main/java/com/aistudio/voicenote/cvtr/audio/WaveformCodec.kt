package com.aistudio.voicenote.cvtr.audio

import org.json.JSONArray

internal object WaveformCodec {
    private const val MAX_BARS = 200

    fun encode(values: List<Int>): String = JSONArray().apply {
        values.take(MAX_BARS).forEach { put(it.coerceIn(0, 31)) }
    }.toString()

    fun decode(value: String): List<Int> {
        val encoded = value.trim()
        if (encoded.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            List(minOf(array.length(), MAX_BARS)) { index ->
                array.optInt(index).coerceIn(0, 31)
            }
        }.getOrElse {
            encoded.removeSurrounding("[", "]")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .take(MAX_BARS)
                .map { it.coerceIn(0, 31) }
        }
    }
}
