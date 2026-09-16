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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertThrows

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
    fun `legacy multitrack draft flattens to sequence when loaded`() = runBlocking {
        val fileA = File(root, "file-a.ogg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val fileB = File(root, "file-b.ogg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val storage = DraftSourceStorage(root = File(root, "files"))
        val repository = EditorDraftRepository(db, storage)
        try {
            val multitrackSession = EditorSession(
                id = "legacy-draft",
                tracks = listOf(
                    EditorTrack(
                        id = "track-1",
                        name = "Track 1",
                        clips = listOf(
                            AudioClip(
                                id = "clip-1",
                                source = AudioSourceRef(fileA.toURI().toString(), 3_000L),
                                sourceStartMs = 0L,
                                sourceEndMs = 3_000L,
                                timelineStartMs = 0L,
                            )
                        )
                    ),
                    EditorTrack(
                        id = "track-2",
                        name = "Track 2",
                        clips = listOf(
                            AudioClip(
                                id = "clip-2",
                                source = AudioSourceRef(fileB.toURI().toString(), 4_000L),
                                sourceStartMs = 0L,
                                sourceEndMs = 4_000L,
                                timelineStartMs = 2_000L,
                            )
                        )
                    )
                )
            )
            val id = repository.save(multitrackSession)
            val loaded = repository.load(id)
            org.junit.Assert.assertNotNull(loaded)
            assertEquals(1, loaded!!.tracks.size)
            assertEquals(2, loaded.tracks.single().clips.size)
            assertEquals(0L, loaded.tracks.single().clips[0].timelineStartMs)
            assertEquals(3_000L, loaded.tracks.single().clips[1].timelineStartMs)
            assertEquals(7_000L, loaded.tracks.single().clips[1].timelineEndMs)
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

    @Test
    fun `save and delete serialize on one draft and leave no published draft`() = runBlocking {
        val sourceOpened = CompletableDeferred<Unit>()
        val allowSource = CompletableDeferred<Unit>()
        val storage = DraftSourceStorage(
            root = File(root, "files"),
            sourceOpener = {
                sourceOpened.complete(Unit)
                runBlocking { allowSource.await() }
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
        )
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = EditorDraftRepository(db, storage)
        try {
            coroutineScope {
                val saving = async { repository.save(sessionUri("memory://source")) }
                sourceOpened.await()
                val deleting = async { repository.delete("draft-1") }
                allowSource.complete(Unit)
                saving.await()
                assertTrue(deleting.await())
            }
            assertEquals(null, repository.load("draft-1"))
            assertFalse(File(root, "files/draft-1").exists())
        } finally {
            db.close()
        }
    }

    @Test
    fun `reconcile removes orphan versions and stale draft leases while preserving Room truth`() = runBlocking {
        val storage = DraftSourceStorage(root = File(root, "files"))
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val cache = ProcessedAudioCache(File(root, "processed"))
        val repository = EditorDraftRepository(db, storage, cache)
        try {
            val actualId = repository.save(session(File(root, "actual.ogg").apply { writeBytes(byteArrayOf(1)) }, cacheKey("actual").toFilename()))
            val orphanSource = File(root, "orphan.ogg").apply { writeBytes(byteArrayOf(2)) }
            val orphanStage = storage.stageSources("orphan", listOf(orphanSource.toURI().toString()))
            storage.commitStage(orphanStage)
            cache.acquireLease("editor-draft:orphan", mapOf(cacheKey("orphan").toFilename() to 1))
            repository.reconcileDraftStorage()
            assertTrue(File(root, "files/$actualId").isDirectory)
            assertFalse(File(root, "files/orphan").exists())
            assertFalse(cache.isRetained(cacheKey("orphan")))
        } finally {
            db.close()
        }
    }

    @Test
    fun `reconcile waits for publish across repository instances sharing source root`() = runBlocking {
        val source = File(root, "race.ogg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val sourceRoot = File(root, "files")
        val storageOne = DraftSourceStorage(root = sourceRoot)
        val storageTwo = DraftSourceStorage(root = sourceRoot)
        val committed = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        storageOne.afterCommitStage = {
            committed.countDown()
            releaseCommit.await()
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repositoryOne = EditorDraftRepository(db, storageOne)
        val repositoryTwo = EditorDraftRepository(db, storageTwo)
        try {
            val saving = async(Dispatchers.Default) { repositoryOne.save(session(source)) }
            assertTrue(committed.await(5L, TimeUnit.SECONDS))
            val reconciling = async(Dispatchers.Default) { repositoryTwo.reconcileDraftStorage() }
            assertEquals(null, withTimeoutOrNull(200L) { reconciling.await() })
            releaseCommit.countDown()
            saving.await()
            reconciling.await()
            assertTrue(repositoryTwo.load("draft-1") != null)
        } finally {
            releaseCommit.countDown()
            db.close()
        }
    }

    @Test
    fun `source limit rejects before staging and cleans temporary directory`() {
        val storage = DraftSourceStorage(
            root = File(root, "files"),
            sourceOpener = { ByteArrayInputStream(byteArrayOf(1)) },
            safetyBytes = 0L,
        )
        val inputs = (0..DraftSourceStorage.MAX_DISTINCT_SOURCES.toInt()).map {
            DraftSourceInput("memory://$it", 1L)
        }
        assertThrows(IOException::class.java) {
            storage.stageSources("draft-limit", inputs)
        }
        assertTrue(File(root, "files").listFiles().orEmpty().none { it.name.contains("staging") })

        val limitedStorage = DraftSourceStorage(
            root = File(root, "limited-files"),
            sourceOpener = { ByteArrayInputStream(ByteArray(1024)) },
            freeSpace = { 512L },
            safetyBytes = 0L,
        )
        assertThrows(IOException::class.java) {
            limitedStorage.stageSources(
                "draft-space",
                listOf(DraftSourceInput("memory://unknown", 1L)),
            )
        }
        assertTrue(File(root, "limited-files").listFiles().orEmpty().none { it.name.contains("staging") })
    }

    @Test
    fun `checked source totals reject overflow and cumulative known-size overrun`() {
        var overflowOpens = 0
        val overflowStorage = DraftSourceStorage(
            root = File(root, "overflow-files"),
            sourceOpener = {
                overflowOpens++
                ByteArrayInputStream(byteArrayOf(1))
            },
            sourceSizer = { Long.MAX_VALUE },
            freeSpace = { Long.MAX_VALUE },
            safetyBytes = 0L,
        )
        assertThrows(IOException::class.java) {
            overflowStorage.stageSources(
                "draft-overflow",
                listOf(
                    DraftSourceInput("memory://overflow-a", 1L),
                    DraftSourceInput("memory://overflow-b", 1L),
                ),
            )
        }
        assertEquals(0, overflowOpens)

        var metadataCalls = 0
        val knownSizeStorage = DraftSourceStorage(
            root = File(root, "known-size-files"),
            sourceOpener = { ByteArrayInputStream(ByteArray(1024)) },
            sourceSizer = { if (metadataCalls++ < 2) 1L else 1024L },
            freeSpace = { 1_500L },
            safetyBytes = 0L,
        )
        assertThrows(IOException::class.java) {
            knownSizeStorage.stageSources(
                "draft-known-size",
                listOf(
                    DraftSourceInput("memory://known-a", 1L),
                    DraftSourceInput("memory://known-b", 1L),
                ),
            )
        }
        assertTrue(File(root, "known-size-files").listFiles().orEmpty().none { it.name.contains("staging") })
    }

    @Test
    fun `load rejects source path outside managed draft version`() {
        runBlocking {
        val source = File(root, "source.ogg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val storage = DraftSourceStorage(root = File(root, "files"))
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = EditorDraftRepository(db, storage)
        try {
            repository.save(session(source))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE editor_draft_clips SET sourcePath = ? WHERE draftId = ?",
                arrayOf(source.absolutePath, "draft-1"),
            )
            assertThrows(InvalidDraftSourceException::class.java) {
                runBlocking { repository.load("draft-1") }
            }
        } finally {
            db.close()
        }
        }
    }

    private fun session(source: File, key: String = ""): EditorSession = EditorSession(
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

    private fun sessionUri(uri: String): EditorSession = session(
        File("/unused.ogg"),
        key = "",
    ).copy(
        tracks = listOf(
            EditorTrack(
                id = "track-1",
                name = "Track",
                clips = listOf(
                    AudioClip(
                        id = "clip-1",
                        source = AudioSourceRef(uri, 3_000L),
                        sourceStartMs = 0L,
                        sourceEndMs = 3_000L,
                        timelineStartMs = 0L,
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
