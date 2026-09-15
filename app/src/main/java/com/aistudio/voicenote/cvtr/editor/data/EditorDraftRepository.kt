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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MissingDraftSourcesException(
    val draftId: String,
    val missingPaths: List<String>,
) : IOException("Draft $draftId has missing private sources: ${missingPaths.joinToString()}")

class InvalidDraftSourceException(
    val draftId: String,
    val invalidPaths: List<String>,
) : IOException("Draft $draftId contains invalid private sources: ${invalidPaths.joinToString()}")

internal class CacheLeaseTransitionException(
    val draftId: String,
    cause: Throwable,
) : IOException("Draft $draftId was committed with a pending cache lease repair", cause)

/** Room snapshot plus private source versions. A save is the only operation that creates a row. */
internal class EditorDraftRepository(
    private val database: AppDatabase,
    private val sourceStorage: DraftSourceStorage,
    private val processedAudioCache: ProcessedAudioCache? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val dao: EditorDraftDao = database.editorDraftDao()
    private val reconciliationMutex = Mutex()
    @Volatile private var reconciled = false

    fun observeAll(): Flow<List<EditorDraft>> = flow {
        ensureReconciled()
        emitAll(dao.observeDrafts().map { rows -> rows.map(EditorDraft::from) })
    }

    suspend fun save(session: EditorSession, name: String = session.id): String =
        withDraftLock(session.id) {
            ensureReconciled()
            saveLocked(session, name)
        }

    private suspend fun saveLocked(session: EditorSession, name: String): String {
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
        try {
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
        val cacheReferences = clips.mapNotNull { it.processedCacheKey }
            .groupingBy { it }
            .eachCount()
            .filterKeys(::isCacheFilename)
        val provisionalLease = "${cacheLease(draftId)}:pending:$version"
        var provisionalOwned = false
        try {
            processedAudioCache?.let {
                it.acquireLease(provisionalLease, cacheReferences)
                provisionalOwned = true
            }
            // The source version is already durable before this transaction can publish its path.
            database.withTransaction {
                dao.replaceSnapshot(draftEntity, tracks, clips)
            }
        } catch (error: Throwable) {
            // The old row and old version remain authoritative when Room rejects the update.
            if (provisionalOwned) processedAudioCache?.releaseLease(provisionalLease)
            runCatching { sourceStorage.deleteVersion(draftId, version) }
            throw error
        }

        try {
            processedAudioCache?.let { cache ->
                // The provisional lease protects the new cache keys across the Room commit. The
                // canonical lease replaces old keys only after the snapshot is durable.
                cache.acquireLease(cacheLease(draftId), cacheReferences)
                if (provisionalOwned) cache.releaseLease(provisionalLease)
            }
        } catch (error: Throwable) {
            // Keep the provisional lease and surface repair-needed state; the new snapshot is
            // never left without a durable cache protection lease.
            reconciled = false
            throw CacheLeaseTransitionException(draftId, error)
        }
        old?.draft?.sourceVersion?.takeIf { it != version }?.let { previousVersion ->
            runCatching { sourceStorage.deleteVersion(draftId, previousVersion) }
        }
        return draftId
    }

    suspend fun load(draftId: String): EditorSession? = withDraftLock(draftId) {
        ensureReconciled()
        val loaded = loadResultLocked(draftId) ?: return@withDraftLock null
        if (loaded.missingPrivateSources.isNotEmpty()) {
            throw MissingDraftSourcesException(draftId, loaded.missingPrivateSources)
        }
        loaded.session
    }

    suspend fun loadResult(draftId: String): EditorDraftLoad? = withDraftLock(draftId) {
        ensureReconciled()
        loadResultLocked(draftId)
    }

    private suspend fun loadResultLocked(draftId: String): EditorDraftLoad? {
        val snapshot = dao.loadSnapshot(draftId) ?: return null
        val clipsByTrack = snapshot.clips.groupBy { it.trackId }
        val invalid = snapshot.clips.asSequence()
            .map { it.sourcePath }
            .filterNot { sourceStorage.isContained(draftId, snapshot.draft.sourceVersion, it) }
            .distinct()
            .toList()
        if (invalid.isNotEmpty()) throw InvalidDraftSourceException(draftId, invalid)
        val missing = snapshot.clips.asSequence()
            .map { it.sourcePath }
            .filter { path -> !sourceStorage.isReadable(draftId, snapshot.draft.sourceVersion, path) }
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

    suspend fun delete(draftId: String): Boolean = withDraftLock(draftId) {
        ensureReconciled()
        deleteLocked(draftId)
    }

    private suspend fun deleteLocked(draftId: String): Boolean {
        require(draftId.isNotBlank()) { "Draft id must not be blank" }
        val snapshot = dao.loadSnapshot(draftId) ?: return false
        val deleted = database.withTransaction { dao.deleteSnapshot(draftId) }
        if (!deleted) return false
        processedAudioCache?.releaseLease(cacheLease(draftId))
        // Database rows are gone before this exact canonical directory is removed. No path from
        // a row is ever used as a deletion target.
        sourceStorage.deleteDraftSources(snapshot.draft.id)
        reconciled = false
        return true
    }

    suspend fun reconcileDraftStorage(): List<String> = reconciliationMutex.withLock {
        reconcileFromRoomTruth().also { reconciled = true }
    }

    private suspend fun ensureReconciled() {
        if (reconciled) return
        reconciliationMutex.withLock {
            if (!reconciled) {
                reconcileFromRoomTruth()
                reconciled = true
            }
        }
    }

    private suspend fun reconcileFromRoomTruth(): List<String> {
        val drafts = dao.getAllDrafts()
        val versions = drafts.associate { it.id to it.sourceVersion }
        val deleted = sourceStorage.reconcileDraftSources(versions).toMutableList()
        processedAudioCache?.reconcileDraftLeases(
            drafts.associate { draft ->
                draft.id to dao.getClips(draft.id)
                    .mapNotNull { it.processedCacheKey }
                    .groupingBy { it }
                    .eachCount()
                    .filterKeys(::isCacheFilename)
            }
        )
        return deleted
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

private data class DraftLockEntry(val mutex: Mutex, var users: Int)

private val draftLocks = ConcurrentHashMap<String, DraftLockEntry>()
private val draftLocksGuard = Any()

private suspend fun <T> withDraftLock(draftId: String, block: suspend () -> T): T {
    require(draftId.isNotBlank()) { "Draft id must not be blank" }
    val entry = synchronized(draftLocksGuard) {
        draftLocks[draftId]?.also { it.users++ } ?: DraftLockEntry(Mutex(), 1).also {
            draftLocks[draftId] = it
        }
    }
    return try {
        entry.mutex.withLock { block() }
    } finally {
        synchronized(draftLocksGuard) {
            entry.users--
            if (entry.users == 0) draftLocks.remove(draftId, entry)
        }
    }
}

private fun String.toExportPreset(): ExportPreset =
    runCatching { ExportPreset.valueOf(this) }.getOrDefault(ExportPreset.VOICE_NOTE_32)
