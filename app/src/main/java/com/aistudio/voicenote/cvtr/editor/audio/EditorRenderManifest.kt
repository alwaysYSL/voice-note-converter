package com.aistudio.voicenote.cvtr.editor.audio

import android.content.Context
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import com.aistudio.voicenote.cvtr.editor.model.MAX_TIMELINE_MS
import com.aistudio.voicenote.cvtr.editor.model.TimelineOperations
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable, worker-safe snapshot of the audio portion of an editor session.
 * Selection, playhead and dirty state are deliberately not serialized.
 */
internal data class EditorRenderManifest(
    val sessionId: String,
    val tracks: List<EditorTrack>,
    val timelineDurationFrames: Long,
    val preset: ExportPreset,
    val sourceHistoryId: Long? = null,
    val sourceFileName: String = "voice_note",
) {
    val renderSession: EditorSession = EditorSession(
        id = sessionId,
        tracks = tracks,
        selectedClipId = null,
        playheadMs = 0L,
        exportPreset = preset,
        dirty = false,
    )

    /** Alias kept concise for renderer/worker call sites. */
    val session: EditorSession
        get() = renderSession

    init {
        require(sessionId.isNotBlank()) { "Manifest session id must not be blank" }
        require(timelineDurationFrames >= 0L) { "Manifest duration must not be negative" }
        require(tracks.size <= 5) { "Manifest contains too many tracks" }
    }

    fun writeTo(target: File) {
        require(target.isFile.not() || target.canWrite()) { "Manifest target is not writable" }
        target.parentFile?.let { parent -> require(parent.exists() || parent.mkdirs()) { "Cannot create manifest directory" } }
        val temporary = File(target.parentFile ?: File("."), ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(toJson().toString(), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                require(temporary.renameTo(target)) { "Cannot publish editor manifest" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_VERSION, FORMAT_VERSION)
        put(KEY_SESSION_ID, sessionId)
        put(KEY_PRESET, preset.name)
        put(KEY_DURATION_FRAMES, timelineDurationFrames)
        put(KEY_SOURCE_HISTORY_ID, sourceHistoryId ?: JSONObject.NULL)
        put(KEY_SOURCE_FILE_NAME, sourceFileName)
        put(KEY_TRACKS, JSONArray().also { tracksArray ->
            tracks.forEach { track ->
                tracksArray.put(JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("volume", track.volume.toDouble())
                    put("muted", track.muted)
                    put("clips", JSONArray().also { clipsArray ->
                        track.clips.forEach { clip -> clipsArray.put(clip.toJson()) }
                    })
                })
            }
        })
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_PRESET = "preset"
        private const val KEY_DURATION_FRAMES = "timelineDurationFrames"
        private const val KEY_SOURCE_HISTORY_ID = "sourceHistoryId"
        private const val KEY_SOURCE_FILE_NAME = "sourceFileName"
        private const val KEY_TRACKS = "tracks"
        private const val SAMPLE_RATE = 48_000L

        fun fromSession(
            session: EditorSession,
            sourceHistoryId: Long? = null,
            preset: ExportPreset = session.exportPreset,
        ): EditorRenderManifest {
            val audioSession = session.copy(
                selectedClipId = null,
                playheadMs = 0L,
                exportPreset = preset,
                dirty = false,
            )
            val canonical = when (val result = TimelineOperations.validate(audioSession)) {
                is TimelineResult.Accepted -> result.value
                is TimelineResult.Rejected -> error("Cannot export invalid timeline: ${result.reason}")
            }
            val durationFrames = canonical.tracks.asSequence()
                .flatMap { it.clips.asSequence() }
                .map { timelineMsToFrames(it.timelineEndMs) }
                .maxOrNull() ?: 0L
            return EditorRenderManifest(
                sessionId = canonical.id,
                tracks = immutableTracks(canonical.tracks),
                timelineDurationFrames = durationFrames,
                preset = preset,
                sourceHistoryId = sourceHistoryId,
                sourceFileName = canonical.tracks.asSequence()
                    .map { it.name.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: "voice_note",
            )
        }

        fun readValidated(file: File): EditorRenderManifest {
            require(file.isFile && file.canRead()) { "Editor manifest is unavailable" }
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            require(json.optInt(KEY_VERSION, -1) == FORMAT_VERSION) { "Unsupported editor manifest version" }
            val sessionId = json.requiredString(KEY_SESSION_ID)
            val preset = runCatching { ExportPreset.valueOf(json.requiredString(KEY_PRESET)) }
                .getOrElse { error("Unsupported editor export preset") }
            val tracksJson = json.optJSONArray(KEY_TRACKS) ?: error("Manifest tracks are missing")
            val tracks = ArrayList<EditorTrack>(tracksJson.length())
            for (index in 0 until tracksJson.length()) {
                val trackJson = tracksJson.optJSONObject(index) ?: error("Invalid track at index $index")
                val clipsJson = trackJson.optJSONArray("clips") ?: error("Track clips are missing")
                val clips = ArrayList<AudioClip>(clipsJson.length())
                for (clipIndex in 0 until clipsJson.length()) {
                    clips += clipsJson.optJSONObject(clipIndex)?.toClip()
                        ?: error("Invalid clip at index $clipIndex")
                }
                tracks += EditorTrack(
                    id = trackJson.requiredString("id"),
                    name = trackJson.optString("name", "Track ${index + 1}"),
                    volume = trackJson.optDouble("volume", 1.0).toFloat(),
                    muted = trackJson.optBoolean("muted", false),
                    clips = clips,
                )
            }
            val sourceHistoryId = if (json.isNull(KEY_SOURCE_HISTORY_ID)) {
                null
            } else {
                json.optLong(KEY_SOURCE_HISTORY_ID).takeIf { it > 0L }
            }
            val requestedDuration = json.optLong(KEY_DURATION_FRAMES, -1L)
            require(requestedDuration >= 0L) { "Manifest duration is invalid" }
            val sourceFileName = json.optString(KEY_SOURCE_FILE_NAME, "").trim()
            val manifest = fromSession(
                EditorSession(sessionId, tracks, exportPreset = preset),
                sourceHistoryId = sourceHistoryId,
                preset = preset,
            ).copy(sourceFileName = sourceFileName.ifBlank { "voice_note" })
            require(manifest.timelineDurationFrames == requestedDuration) {
                "Manifest duration does not match its clips"
            }
            return manifest
        }

        fun writePrivate(
            context: Context,
            session: EditorSession,
            sourceHistoryId: Long? = null,
            preset: ExportPreset = session.exportPreset,
        ): File {
            val directory = File(context.filesDir, "editor/manifests")
            require(directory.exists() || directory.mkdirs()) { "Cannot create editor manifest directory" }
            val target = File(directory, "${session.id}-${UUID.randomUUID()}.json")
            fromSession(session, sourceHistoryId, preset).writeTo(target)
            return target
        }

        private fun timelineMsToFrames(ms: Long): Long =
            (ms.coerceIn(0L, MAX_TIMELINE_MS) * SAMPLE_RATE) / 1_000L

        private fun immutableTracks(value: List<EditorTrack>): List<EditorTrack> =
            Collections.unmodifiableList(value.toList())

        private fun JSONObject.requiredString(key: String): String =
            optString(key, "").takeIf { it.isNotBlank() } ?: error("Manifest field $key is missing")

        private fun AudioClip.toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("sourceUri", source.uri)
            put("sourceDurationMs", source.durationMs)
            put("sourceStartMs", sourceStartMs)
            put("sourceEndMs", sourceEndMs)
            put("timelineStartMs", timelineStartMs)
            put("fadeInMs", effects.fadeInMs)
            put("fadeOutMs", effects.fadeOutMs)
            put("gain", effects.gain.toDouble())
            put("pitchSemitones", effects.pitchSemitones.toDouble())
            put("speed", effects.speed.toDouble())
        }

        private fun JSONObject.toClip(): AudioClip = AudioClip(
            id = requiredString("id"),
            source = AudioSourceRef(
                uri = requiredString("sourceUri"),
                durationMs = optLong("sourceDurationMs", Long.MAX_VALUE),
            ),
            sourceStartMs = optLong("sourceStartMs", -1L),
            sourceEndMs = optLong("sourceEndMs", -1L),
            timelineStartMs = optLong("timelineStartMs", -1L),
            effects = ClipEffects(
                fadeInMs = optLong("fadeInMs", 0L),
                fadeOutMs = optLong("fadeOutMs", 0L),
                gain = optDouble("gain", 1.0).toFloat(),
                pitchSemitones = optDouble("pitchSemitones", 0.0).toFloat(),
                speed = optDouble("speed", 1.0).toFloat(),
            ),
        )
    }
}
