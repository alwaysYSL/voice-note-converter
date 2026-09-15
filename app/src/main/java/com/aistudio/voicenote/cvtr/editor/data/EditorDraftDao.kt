package com.aistudio.voicenote.cvtr.editor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EditorDraftDao {
    @Query("SELECT * FROM editor_drafts ORDER BY updatedAt DESC, id DESC")
    abstract fun observeDrafts(): Flow<List<EditorDraftEntity>>

    @Query("SELECT * FROM editor_drafts WHERE id = :draftId LIMIT 1")
    abstract suspend fun getDraft(draftId: String): EditorDraftEntity?

    @Query("SELECT * FROM editor_drafts")
    abstract suspend fun getAllDrafts(): List<EditorDraftEntity>

    @Query("SELECT * FROM editor_draft_tracks WHERE draftId = :draftId ORDER BY sortOrder ASC")
    abstract suspend fun getTracks(draftId: String): List<EditorDraftTrackEntity>

    @Query(
        "SELECT * FROM editor_draft_clips WHERE draftId = :draftId " +
            "ORDER BY trackId ASC, sortOrder ASC"
    )
    abstract suspend fun getClips(draftId: String): List<EditorDraftClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDraftEntity(draft: EditorDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTrackEntities(tracks: List<EditorDraftTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertClipEntities(clips: List<EditorDraftClipEntity>)

    @Query("DELETE FROM editor_draft_clips WHERE draftId = :draftId")
    protected abstract suspend fun deleteClips(draftId: String)

    @Query("DELETE FROM editor_draft_tracks WHERE draftId = :draftId")
    protected abstract suspend fun deleteTracks(draftId: String)

    @Query("DELETE FROM editor_drafts WHERE id = :draftId")
    abstract suspend fun deleteDraft(draftId: String): Int

    @Transaction
    open suspend fun replaceSnapshot(
        draft: EditorDraftEntity,
        tracks: List<EditorDraftTrackEntity>,
        clips: List<EditorDraftClipEntity>,
    ) {
        deleteClips(draft.id)
        deleteTracks(draft.id)
        insertDraftEntity(draft)
        if (tracks.isNotEmpty()) insertTrackEntities(tracks)
        if (clips.isNotEmpty()) insertClipEntities(clips)
    }

    @Transaction
    open suspend fun loadSnapshot(draftId: String): EditorDraftSnapshot? {
        val draft = getDraft(draftId) ?: return null
        return EditorDraftSnapshot(draft, getTracks(draftId), getClips(draftId))
    }

    @Transaction
    open suspend fun deleteSnapshot(draftId: String): Boolean {
        val exists = getDraft(draftId) != null
        if (!exists) return false
        deleteClips(draftId)
        deleteTracks(draftId)
        deleteDraft(draftId)
        return true
    }
}
