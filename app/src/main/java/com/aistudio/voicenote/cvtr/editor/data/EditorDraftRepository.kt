package com.aistudio.voicenote.cvtr.editor.data

import androidx.room.withTransaction
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class MissingDraftSourcesException(
    val draftId: String,
    val missingPaths: List<String>,
) : IOException("Draft $draftId has missing private sources: ${missingPaths.joinToString()}")

/** Room snapshot plus private source versions. A save is the only operation that creates a row. */
internal class EditorDraftRepository(
    private val database: AppDatabase,
    private val sourceStorage: DraftSourceStorage,
    private val processedAudioCache: ProcessedAudioCache? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val dao: EditorDraftDao = database.editorDraftDao()

    fun observeAll(): Flow<List<EditorDraft>> = dao.observeDrafts().map { rows ->
        rows.map(EditorDraft::from)
    }

    suspend fun save(session: EditorSession, name: String = session.id): String {
        require(session.id.isNotBlank()) { "Draft id must not be blank" }
        val draftId = session.id
        val old = dao.loadSnapshot(draftId)
        val version = "${now()}-${UUID.randomUUID().toString().take(8)}"
        val sourceInputs = session.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .map { clip ->
                DraftSourceInput(
                    uri = clip.source.uri,
                    durationMs = clip.source.durationMs,
                    displayName = sourceName(clip.source.uri),
                )
            }
            .distinctBy { it.uri }
            .toList()
        val stage = sourceStorage.stageSources(draftId, sourceInputs, version)
        val committedDirectory = try {
            sourceStorage.commitStage(stage)
        } catch (error: Throwable) {
            runCatching { stage.stagingDirectory.deleteRecursively() }
            throw error
        }
        val createdAt = old?.draft?.createdAt ?: now()
        val draftEntity = EditorDraftEntity(
            id = draftId,
            name = name.ifBlank { draftId },
            createdAt = createdAt,
            updatedAt = now(),
            exportPreset = session.exportPreset.name,
            selectedClipId = session.selectedClipId,
            playheadMs = session.playheadMs.coerceAtLeast(0L),
            sourceVersion = version,
        )
        val tracks = session.tracks.mapIndexed { index, track ->
            EditorDraftTrackEntity(
                draftId = draftId,
                trackId = track.id,
                sortOrder = index,
                name = track.name,
                volume = track.volume,
                muted = track.muted,
            )
        }
        val clips = session.tracks.flatMap { track ->
            track.clips.mapIndexed { index, clip ->
                val previous = old?.clips?.firstOrNull { it.clipId == clip.id }
                val unchangedSource = previous?.sourcePath == clip.source.uri
                EditorDraftClipEntity(
                    draftId = draftId,
                    trackId = track.id,
                    clipId = clip.id,
                    sourcePath = stage.sourcePath(clip.source.uri),
                    originalSourceUri = if (unchangedSource) {
                        previous!!.originalSourceUri
                    } else {
                        clip.source.uri
                    },
                    sourceFileName = if (unchangedSource) {
                        previous!!.sourceFileName
                    } else {
                        sourceName(clip.source.uri)
                    },
                    sourceDurationMs = clip.source.durationMs,
                    sourceStartMs = clip.sourceStartMs,
                    sourceEndMs = clip.sourceEndMs,
                    timelineStartMs = clip.timelineStartMs,
                    fadeInMs = clip.effects.fadeInMs,
                    fadeOutMs = clip.effects.fadeOutMs,
                    gain = clip.effects.gain,
                    pitchSemitones = clip.effects.pitchSemitones,
                    speed = clip.effects.speed,
                    processedCacheKey = clip.effects.processedCacheKey,
                    sortOrder = index,
                )
            }
        }
        try {
            // The source version is already durable before this transaction can publish its path.
            database.withTransaction {
                dao.replaceSnapshot(draftEntity, tracks, clips)
            }
        } catch (error: Throwable) {
            // The old row and old version remain authoritative when Room rejects the update.
            runCatching { sourceStorage.deleteVersion(draftId, version) }
            throw error
        }

        val newLease = cacheLease(draftId)
        val cacheReferences = clips.mapNotNull { it.processedCacheKey }
            .groupingBy { it }
            .eachCount()
            .filterKeys(::isCacheFilename)
        // This call replaces the old draft lease only after the Room commit succeeds. If a cache
        // filesystem failure occurs, the committed draft remains loadable and the old lease is
        // intentionally left in place for safety.
        processedAudioCache?.let { cache ->
            runCatching { cache.acquireLease(newLease, cacheReferences) }
        }
        old?.draft?.sourceVersion?.takeIf { it != version }?.let { previousVersion ->
            runCatching { sourceStorage.deleteVersion(draftId, previousVersion) }
        }
        return draftId
    }

    suspend fun load(draftId: String): EditorSession? {
        val loaded = loadResult(draftId) ?: return null
        if (loaded.missingPrivateSources.isNotEmpty()) {
            throw MissingDraftSourcesException(draftId, loaded.missingPrivateSources)
        }
        return loaded.session
    }

    suspend fun loadResult(draftId: String): EditorDraftLoad? {
        val snapshot = dao.loadSnapshot(draftId) ?: return null
        val clipsByTrack = snapshot.clips.groupBy { it.trackId }
        val missing = snapshot.clips.asSequence()
            .map { it.sourcePath }
            .filter { path -> !File(path).isFile || !File(path).canRead() }
            .distinct()
            .toList()
        val tracks = snapshot.tracks.sortedBy { it.sortOrder }.map { track ->
            EditorTrack(
                id = track.trackId,
                name = track.name,
                volume = track.volume,
                muted = track.muted,
                clips = clipsByTrack[track.trackId].orEmpty().sortedBy { it.sortOrder }.map(::toClip),
            )
        }
        return EditorDraftLoad(
            session = EditorSession(
                id = snapshot.draft.id,
                tracks = tracks,
                selectedClipId = snapshot.draft.selectedClipId,
                playheadMs = snapshot.draft.playheadMs,
                exportPreset = snapshot.draft.exportPreset.toExportPreset(),
                dirty = false,
            ),
            missingPrivateSources = missing,
        )
    }

    suspend fun delete(draftId: String): Boolean {
        require(draftId.isNotBlank()) { "Draft id must not be blank" }
        val snapshot = dao.loadSnapshot(draftId) ?: return false
        val deleted = database.withTransaction { dao.deleteSnapshot(draftId) }
        if (!deleted) return false
        processedAudioCache?.releaseLease(cacheLease(draftId))
        // Database rows are gone before this exact canonical directory is removed. No path from
        // a row is ever used as a deletion target.
        sourceStorage.deleteDraftSources(snapshot.draft.id)
        return true
    }

    private fun toClip(entity: EditorDraftClipEntity): AudioClip = AudioClip(
        id = entity.clipId,
        source = AudioSourceRef(entity.sourcePath, entity.sourceDurationMs),
        sourceStartMs = entity.sourceStartMs,
        sourceEndMs = entity.sourceEndMs,
        timelineStartMs = entity.timelineStartMs,
        effects = ClipEffects(
            fadeInMs = entity.fadeInMs,
            fadeOutMs = entity.fadeOutMs,
            gain = entity.gain,
            pitchSemitones = entity.pitchSemitones,
            speed = entity.speed,
            processedCacheKey = entity.processedCacheKey,
        ),
    )

    private fun sourceName(uri: String): String = uri.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').substringAfterLast('\\').ifBlank { "source" }

    private fun cacheLease(draftId: String): String = "editor-draft:$draftId"

    private fun isCacheFilename(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

private fun String.toExportPreset(): ExportPreset =
    runCatching { ExportPreset.valueOf(this) }.getOrDefault(ExportPreset.VOICE_NOTE_32)
