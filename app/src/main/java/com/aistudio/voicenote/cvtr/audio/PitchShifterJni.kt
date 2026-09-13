package com.aistudio.voicenote.cvtr.audio

/** JNI bridge to the Signalsmith Stretch native pitch-shifting engine. */
internal object PitchShifterJni {
    init {
        System.loadLibrary("pitchshifter")
    }

    external fun create(sampleRate: Int, channels: Int): Long

    external fun setTranspose(handle: Long, semitones: Float, tonalityLimit: Float)

    external fun process(handle: Long, input: ShortArray, inputFrames: Int): ShortArray

    external fun flush(handle: Long): ShortArray

    external fun destroy(handle: Long)
}
