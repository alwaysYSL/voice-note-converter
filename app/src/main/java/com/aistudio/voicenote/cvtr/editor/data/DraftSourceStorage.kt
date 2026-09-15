package com.aistudio.voicenote.cvtr.editor.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class DraftSourceInput(
    val uri: String,
    val durationMs: Long,
    val displayName: String = "source",
)

internal class DraftSourceStage internal constructor(
    val draftId: String,
    val version: String,
    internal val stagingDirectory: File,
    internal val stagedFiles: Map<String, File>,
) {
    internal var committedDirectory: File? = null

    fun sourcePath(uri: String): String {
        val committed = requireNotNull(committedDirectory) { "Source stage has not been committed" }
        val staged = requireNotNull(stagedFiles[uri]) { "Source was not staged: $uri" }
        return File(committed, staged.name).canonicalPath
    }
}

/**
 * Durable source copies for explicit drafts. This storage is deliberately separate from the
 * rebuildable processed-audio cache and has no eviction path.
 */
internal class DraftSourceStorage private constructor(
    private val resolver: ((String) -> InputStream?)?,
    private val sourceSizer: ((String) -> Long?)?,
    root: File,
    private val freeSpace: (File) -> Long,
    private val safetyBytes: Long,
) {
    constructor(
        root: File,
        sourceOpener: ((String) -> InputStream?)? = null,
        freeSpace: (File) -> Long = { it.usableSpace },
        safetyBytes: Long = MIN_FREE_BYTES,
    ) : this(sourceOpener, null, root, freeSpace, safetyBytes)

    internal constructor(
        root: File,
        sourceOpener: ((String) -> InputStream?)?,
        sourceSizer: ((String) -> Long?)?,
        freeSpace: (File) -> Long,
        safetyBytes: Long,
    ) : this(sourceOpener, sourceSizer, root, freeSpace, safetyBytes)

    constructor(context: Context) : this(
        resolver = { value ->
            context.contentResolver.openInputStream(Uri.parse(value))
        },
        sourceSizer = { value ->
            context.contentResolver.query(
                Uri.parse(value),
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        },
        root = File(context.filesDir, ROOT_DIRECTORY),
        freeSpace = { it.usableSpace },
        safetyBytes = MIN_FREE_BYTES,
    )

    private val root: File = root.canonicalFile
    internal val canonicalRootPath: String get() = root.path
    private val rootState = roots.computeIfAbsent(root.path) { StorageRootState() }

    /** Test seam used to verify rollback after a later distinct source fails. */
    internal var failAfterCopies: Int? = null

    /** Test seam for holding the committed directory before Room publication. */
    internal var afterCommitStage: (() -> Unit)? = null

    init {
        require(safetyBytes >= 0L) { "safetyBytes must not be negative" }
        require(root.exists() || root.mkdirs()) { "Unable to create draft source directory" }
        require(root.isDirectory) { "Draft source root is not a directory" }
    }

    fun stageSources(
        draftId: String,
        sources: Collection<DraftSourceInput>,
        version: String = UUID.randomUUID().toString(),
    ): DraftSourceStage {
        validateDraftId(draftId)
        validateVersion(version)
        val distinct = sources.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        if (distinct.size > MAX_DISTINCT_SOURCES) {
            throw IOException("Draft contains too many distinct sources")
        }
        val estimatedSizes = distinct.associate { source ->
            source.uri to sourceSize(source.uri).coerceAtLeast(0L)
        }
        val estimatedBytes = estimatedSizes.values.fold(0L) { total, size ->
            checkedAdd(total, size, MAX_TOTAL_SOURCE_BYTES)
        }
        checkAvailable(estimatedBytes)
        val staging = File(root, ".${draftId}.staging-${UUID.randomUUID()}").canonicalFile
        require(isDirectChild(staging, root)) { "Invalid draft staging path" }
        synchronized(rootState.lock) {
            require(staging.mkdirs()) { "Unable to create draft staging directory" }
            rootState.activeStaging += staging.path
        }
        val staged = LinkedHashMap<String, File>()
        var copiedBytes = 0L
        try {
            distinct.forEachIndexed { index, source ->
                failAfterCopies?.let { limit ->
                    if (index >= limit) throw IOException("Injected source copy failure")
                }
                val extension = fileExtension(source.displayName.ifBlank { source.uri })
                val target = File(staging, sha256(source.uri) + extension).canonicalFile
                require(isDirectChild(target, staging)) { "Invalid staged source path" }
                val expectedSize = sourceSize(source.uri)
                val copied = copyAndValidate(
                    source,
                    target,
                    expectedSize,
                    estimatedBytes,
                    copiedBytes,
                )
                copiedBytes = checkedAdd(copiedBytes, copied, MAX_TOTAL_SOURCE_BYTES)
                staged[source.uri] = target
            }
            return DraftSourceStage(draftId, version, staging, staged)
        } catch (error: Throwable) {
            synchronized(rootState.lock) { rootState.activeStaging -= staging.path }
            staging.deleteRecursively()
            throw error
        }
    }

    fun stageSources(
        draftId: String,
        sourceUris: List<String>,
        version: String = UUID.randomUUID().toString(),
    ): DraftSourceStage = stageSources(
        draftId = draftId,
        sources = sourceUris.map { DraftSourceInput(it, Long.MAX_VALUE) },
        version = version,
    )

    /** Publishes the complete source version with one same-filesystem directory rename. */
    fun commitStage(stage: DraftSourceStage): File {
        require(stage.stagingDirectory.canonicalFile == stage.stagingDirectory)
        require(isDirectChild(stage.stagingDirectory, root)) { "Stage is outside draft root" }
        require(stage.stagingDirectory.name.startsWith(".${stage.draftId}.staging-")) {
            "Stage does not belong to draft"
        }
        validateDraftId(stage.draftId)
        validateVersion(stage.version)
        val draftDirectory = File(root, stage.draftId).canonicalFile
        require(isDirectChild(draftDirectory, root)) { "Invalid draft directory" }
        require(draftDirectory.exists() || draftDirectory.mkdirs()) {
            "Unable to create draft version directory"
        }
        val committed = File(draftDirectory, "v${stage.version}").canonicalFile
        require(isDirectChild(committed, draftDirectory)) { "Invalid draft version path" }
        require(!committed.exists()) { "Draft source version already exists" }
        if (!stage.stagingDirectory.renameTo(committed)) {
            synchronized(rootState.lock) { rootState.activeStaging -= stage.stagingDirectory.path }
            throw IOException("Unable to atomically publish draft sources")
        }
        synchronized(rootState.lock) { rootState.activeStaging -= stage.stagingDirectory.path }
        afterCommitStage?.invoke()
        stage.committedDirectory = committed
        try {
            stagedFilesReadable(stage)
        } catch (error: Throwable) {
            runCatching { committed.deleteRecursively() }
            stage.committedDirectory = null
            throw error
        }
        return committed
    }

    /** Removes one committed version and nothing outside its exact draft directory. */
    fun deleteVersion(draftId: String, version: String) {
        validateDraftId(draftId)
        validateVersion(version)
        val draftDirectory = File(root, draftId).canonicalFile
        val versionDirectory = File(draftDirectory, "v$version").canonicalFile
        require(isDirectChild(draftDirectory, root) && isDirectChild(versionDirectory, draftDirectory)) {
            "Draft source deletion is outside the managed root"
        }
        if (versionDirectory.exists()) require(versionDirectory.deleteRecursively()) {
            "Unable to delete draft source version"
        }
        if (draftDirectory.isDirectory && draftDirectory.listFiles().isNullOrEmpty()) {
            require(draftDirectory.delete()) { "Unable to delete empty draft directory" }
        }
    }

    /** Removes all versions for one draft after its database rows have been deleted. */
    fun deleteDraftSources(draftId: String) {
        validateDraftId(draftId)
        val draftDirectory = File(root, draftId).canonicalFile
        require(isDirectChild(draftDirectory, root)) {
            "Draft source deletion is outside the managed root"
        }
        if (draftDirectory.exists()) require(draftDirectory.deleteRecursively()) {
            "Unable to delete draft source directory"
        }
    }

    /** Reconciles committed versions against Room truth; live stages are protected in-process. */
    fun reconcileDraftSources(referencedVersions: Map<String, String>): DraftSourceReconciliationResult {
        val activeStages = synchronized(rootState.lock) { rootState.activeStaging.toSet() }
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        root.listFiles()?.forEach { child ->
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.parentFile != root) return@forEach
            if (canonical.name.startsWith(".") && canonical.name.contains(".staging-")) {
                if (canonical.path !in activeStages) {
                    if (canonical.deleteRecursively() || !canonical.exists()) deleted += canonical.path
                    else failed += canonical.path
                }
                return@forEach
            }
            if (!canonical.isDirectory || !isValidDraftId(canonical.name)) return@forEach
            val expected = referencedVersions[canonical.name]
            if (expected == null) {
                if (canonical.deleteRecursively() || !canonical.exists()) deleted += canonical.path
                else failed += canonical.path
                return@forEach
            }
            val expectedName = "v$expected"
            canonical.listFiles()?.forEach { version ->
                val versionCanonical = runCatching { version.canonicalFile }.getOrNull() ?: return@forEach
                if (versionCanonical.parentFile != canonical) return@forEach
                if (versionCanonical.name.startsWith("v") && versionCanonical.name != expectedName) {
                    if (versionCanonical.deleteRecursively() || !versionCanonical.exists()) {
                        deleted += versionCanonical.path
                    } else {
                        failed += versionCanonical.path
                    }
                }
            }
        }
        return DraftSourceReconciliationResult(deleted, failed)
    }

    internal fun activeStagingPaths(): Set<String> = synchronized(rootState.lock) {
        rootState.activeStaging.toSet()
    }

    internal fun isReadable(draftId: String, version: String, path: String): Boolean {
        return isContained(draftId, version, path) && isReadable(path)
    }

    internal fun isContained(draftId: String, version: String, path: String): Boolean =
        runCatching {
            validateDraftId(draftId)
            validateVersion(version)
            val versionDirectory = File(File(root, draftId), "v$version").canonicalFile
            File(path).canonicalFile.parentFile == versionDirectory
        }.getOrDefault(false)

    fun isReadable(path: String): Boolean {
        val file = File(path).canonicalFile
        return file.isFile && file.canRead() && file.length() in 1L..MAX_SOURCE_BYTES &&
            file.parentFile?.parentFile?.parentFile?.canonicalFile == root
    }

    private fun copyAndValidate(
        source: DraftSourceInput,
        target: File,
        expectedSize: Long,
        estimatedTotalBytes: Long,
        copiedBeforeStage: Long,
    ): Long {
        val input = open(source.uri) ?: throw IOException("Unable to open source ${source.uri}")
        var copied = 0L
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    if (copied > MAX_SOURCE_BYTES) {
                        throw IOException("Draft source exceeds maximum size")
                    }
                    val copiedTotal = checkedAdd(copiedBeforeStage, copied, MAX_TOTAL_SOURCE_BYTES)
                    val additionalReserve = (copiedTotal - estimatedTotalBytes).coerceAtLeast(0L)
                    checkAvailableForChunk(additionalReserve)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        } finally {
            input.close()
        }
        if (!target.isFile || !target.canRead() || target.length() != copied || copied == 0L) {
            throw IOException("Draft source copy failed validation")
        }
        if (expectedSize > 0L && expectedSize != copied) {
            throw IOException("Draft source changed while it was copied")
        }
        return copied
    }

    private fun stagedFilesReadable(stage: DraftSourceStage) {
        stage.stagedFiles.values.forEach { staged ->
            val committed = File(requireNotNull(stage.committedDirectory), staged.name)
            if (!committed.isFile || !committed.canRead() || committed.length() <= 0L) {
                throw IOException("Committed draft source failed validation")
            }
        }
    }

    private fun open(uri: String): InputStream? {
        val file = fileForUri(uri)
        if (file != null) return FileInputStream(file)
        return resolver?.invoke(uri)
    }

    private fun sourceSize(uri: String): Long {
        fileForUri(uri)?.let { return it.length() }
        // ContentResolver metadata is intentionally best effort; the bounded copy remains the
        // final guard when providers omit OpenableColumns.SIZE.
        return sourceSizer?.invoke(uri)?.takeIf { it >= 0L } ?: 0L
    }

    private fun checkAvailable(estimatedBytes: Long) {
        if (estimatedBytes > MAX_SOURCE_BYTES * MAX_DISTINCT_SOURCES.toLong()) {
            throw IOException("Draft source set is too large")
        }
        val required = requiredBytes(estimatedBytes.coerceAtLeast(1L))
        if (freeSpace(root) < required) throw IOException("Insufficient storage for draft sources")
    }

    private fun checkAvailableForChunk(reservedBytes: Long) {
        if (freeSpace(root) <= requiredBytes(reservedBytes)) {
            throw IOException("Insufficient storage while copying draft source")
        }
    }

    private fun requiredBytes(reservedBytes: Long): Long =
        if (reservedBytes > Long.MAX_VALUE - safetyBytes) Long.MAX_VALUE
        else reservedBytes + safetyBytes

    private fun checkedAdd(total: Long, value: Long, limit: Long): Long {
        if (value < 0L || total < 0L || value > limit - total) {
            throw IOException("Draft source set exceeds maximum size")
        }
        return total + value
    }

    private fun fileForUri(value: String): File? {
        val path = if (value.startsWith("file:", ignoreCase = true)) {
            runCatching { Uri.parse(value).path }.getOrNull()
        } else if (!value.contains("://") && !value.startsWith("content:", ignoreCase = true)) {
            value
        } else {
            null
        }
        return path?.let(::File)?.takeIf { it.isFile }
    }

    private fun validateDraftId(value: String) {
        require(isValidDraftId(value)) {
            "Invalid draft id"
        }
    }

    private fun isValidDraftId(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == File.separatorChar } &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun validateVersion(value: String) {
        require(value.isNotBlank() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Invalid draft source version"
        }
    }

    private fun isDirectChild(child: File, parent: File): Boolean =
        child.canonicalFile.parentFile == parent.canonicalFile

    private fun fileExtension(value: String): String {
        val clean = value.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (extension.length in 1..8 && extension.all { it.isLetterOrDigit() }) ".${extension}" else ".bin"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val ROOT_DIRECTORY = "editor-drafts"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_SOURCE_BYTES = 512L * 1024L * 1024L
        const val MAX_DISTINCT_SOURCES = 64
        const val MAX_TOTAL_SOURCE_BYTES = MAX_SOURCE_BYTES * MAX_DISTINCT_SOURCES
        const val MIN_FREE_BYTES = 1L * 1024L * 1024L
        private val roots = ConcurrentHashMap<String, StorageRootState>()
    }

    private class StorageRootState {
        val lock = Any()
        val activeStaging = mutableSetOf<String>()
    }
}

internal data class DraftSourceReconciliationResult(
    val deletedPaths: List<String>,
    val failedPaths: List<String>,
)
