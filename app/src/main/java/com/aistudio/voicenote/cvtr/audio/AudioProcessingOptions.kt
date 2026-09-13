package com.aistudio.voicenote.cvtr.audio

data class AudioProcessingOptions(
    val normalizeAudio: Boolean = false,
    val trimSilence: Boolean = false
)
