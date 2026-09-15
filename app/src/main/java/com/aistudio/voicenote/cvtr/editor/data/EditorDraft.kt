package com.aistudio.voicenote.cvtr.editor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset

/** Metadata for one explicitly saved editor session. Ordinary sessions never create this row. */
@Entity(tableName = "editor_drafts")
data class EditorDraftEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val exportPreset: String,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    /** Immutable source directory version committed before this row is published. */
    val sourceVersion: String,
)

@Entity(
    tableName = "editor_draft_tracks",
    primaryKeys = ["draftId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = EditorDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["draftId"]), Index(value = ["draftId", "sortOrder"])],
)
data class EditorDraftTrackEntity(
    val draftId: String,
    val trackId: String,
    val sortOrder: Int,
    val name: String,
    val volume: Float,
    val muted: Boolean,
)

@Entity(
    tableName = "editor_draft_clips",
    primaryKeys = ["draftId", "clipId"],
    foreignKeys = [
        ForeignKey(
            entity = EditorDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EditorDraftTrackEntity::class,
            parentColumns = ["draftId", "trackId"],
            childColumns = ["draftId", "trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["draftId"]), Index(value = ["draftId", "trackId"])],
)
data class EditorDraftClipEntity(
    val draftId: String,
    val trackId: String,
    val clipId: String,
    /** Canonical path inside files/editor-drafts/<draftId>/v<sourceVersion>. */
    val sourcePath: String,
    /** Original URI retained for source replacement diagnostics and metadata. */
    val originalSourceUri: String,
    val sourceFileName: String,
    val sourceDurationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val gain: Float,
    val pitchSemitones: Float,
    val speed: Float,
    val processedCacheKey: String?,
    val cleanupStrength: String? = null,
    val cleanupNormalized: Boolean = false,
    val cleanupAlgorithmVersion: String? = null,
    val sortOrder: Int,
)

/** Stable summary exposed to history without loading all timeline rows. */
internal data class EditorDraft(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val exportPreset: ExportPreset,
) {
    internal companion object {
        fun from(entity: EditorDraftEntity): EditorDraft = EditorDraft(
            id = entity.id,
            name = entity.name,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            exportPreset = entity.exportPreset.toExportPreset(),
        )
    }
}

data class EditorDraftSnapshot(
    val draft: EditorDraftEntity,
    val tracks: List<EditorDraftTrackEntity>,
    val clips: List<EditorDraftClipEntity>,
)

internal data class EditorDraftLoad(
    val session: EditorSession,
    val missingPrivateSources: List<String>,
)

private fun String.toExportPreset(): ExportPreset =
    runCatching { ExportPreset.valueOf(this) }.getOrDefault(ExportPreset.VOICE_NOTE_32)
