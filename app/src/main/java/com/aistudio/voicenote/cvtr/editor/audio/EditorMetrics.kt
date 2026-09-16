package com.aistudio.voicenote.cvtr.editor.audio

/**
 * Seam instrumentasi untuk memonitor siklus gesture, invalidasi renderer,
 * decoder seek, dan underrun preview tanpa membocorkan data pengguna.
 */
internal interface EditorMetrics {
    fun gestureStarted(mode: String, clipId: String)
    fun gestureCommitted(mode: String, updateCount: Int, durationMs: Long)
    fun rendererInvalidated(reason: String)
    fun decoderSeek(sourceId: String)
    fun previewUnderrun(queuedFrames: Long)

    object NoOp : EditorMetrics {
        override fun gestureStarted(mode: String, clipId: String) {}
        override fun gestureCommitted(mode: String, updateCount: Int, durationMs: Long) {}
        override fun rendererInvalidated(reason: String) {}
        override fun decoderSeek(sourceId: String) {}
        override fun previewUnderrun(queuedFrames: Long) {}
    }
}
