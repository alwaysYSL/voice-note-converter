package com.aistudio.voicenote.cvtr.editor.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorDraftRepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val databaseName = "editor_draft_repository_test.db"
    private val root = File(app.cacheDir, "draft-repository-test")

    @After
    fun tearDown() {
        runCatching { app.deleteDatabase(databaseName) }
        root.deleteRecursively()
    }

    @Test
    fun `saved draft uses private source after external source disappears and failed update preserves old snapshot`() = runBlocking {
        val externalA = File(root, "external-a.ogg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val externalB = File(root, "external-b.ogg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val cache = ProcessedAudioCache(File(root, "processed"))
        val storage = DraftSourceStorage(root = File(root, "files"))
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = EditorDraftRepository(db, storage, cache)
        try {
            val original = session(externalA, cacheKey("cleanup-a").toFilename())
            val id = repository.save(original)
            externalA.delete()
            val restored = requireNotNull(repository.load(id))
            assertTrue(File(UriPath(restored.tracks.single().clips.single().source.uri)).isFile)

            val changed = session(externalB, cacheKey("cleanup-b").toFilename()).copy(id = id)
            storage.failAfterCopies = 0
            runCatching { repository.save(changed) }.onSuccess {
                error("expected source copy failure")
            }
            assertEquals(restored, repository.load(id))
            assertTrue(cache.isRetained(cacheKey("cleanup-a")))
        } finally {
            db.close()
        }
    }

    @Test
    fun `delete rejects traversal and removes only canonical draft directory`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val storage = DraftSourceStorage(root = File(root, "files"))
        val repository = EditorDraftRepository(db, storage)
        try {
            val outside = File(root, "outside").apply { writeText("keep") }
            runCatching { storage.deleteDraftSources("../outside") }.onSuccess {
                error("expected path traversal rejection")
            }
            assertTrue(outside.isFile)
        } finally {
            db.close()
        }
    }

    private fun session(source: File, key: String): EditorSession = EditorSession(
        id = "draft-1",
        tracks = listOf(
            EditorTrack(
                id = "track-1",
                name = "Track",
                clips = listOf(
                    AudioClip(
                        id = "clip-1",
                        source = AudioSourceRef(source.toURI().toString(), 3_000L),
                        sourceStartMs = 0L,
                        sourceEndMs = 3_000L,
                        timelineStartMs = 0L,
                        effects = com.aistudio.voicenote.cvtr.editor.model.ClipEffects(
                            processedCacheKey = key,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun UriPath(path: String): String = path

    private fun cacheKey(seed: String): ProcessedAudioKey = ProcessedAudioKey(
        sourceFingerprint = seed,
        sourceStartMs = 0L,
        sourceEndMs = 3_000L,
        cleanup = CleanupStrength.OFF,
        normalized = false,
    )
}
