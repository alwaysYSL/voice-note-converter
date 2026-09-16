<aside>
🎯

**Tujuan akhir**

Mengubah editor dari mini-DAW multitrack yang sulit dikendalikan menjadi **sequence editor** yang deterministik: beberapa file Ogg disusun A → B → C, dapat di-reorder, trim, split, dan diberi efek; preview harus sama dengan export; hasil akhir berupa satu file Ogg/Opus yang tervalidasi.

</aside>

## Status dan hubungan dokumen

Dokumen ini adalah rencana implementasi rinci untuk menutup EDT-01 sampai EDT-14 pada page audit induk. Potongan kode di bawah merupakan rancangan target; nama package dan dependency injection perlu disesuaikan dengan branch implementasi aktual.

## Prinsip implementasi wajib

1. **Target eksplisit:** setiap mutasi membawa `clipId`; selection tidak pernah menentukan target command secara implisit.
2. **Satu gesture = satu transaksi:** pointer update bersifat transient, satu command baru dicatat saat gesture selesai.
3. **Sequence-first:** default adalah susunan berurutan tanpa overlap atau gap tidak disengaja.
4. **Preview = export:** keduanya menggunakan audio graph, pemetaan waktu, efek, dan limiter yang sama.
5. **Fail-closed:** timeline invalid, source offline, cache korup, atau output gagal divalidasi tidak boleh menghasilkan file yang terlihat sukses.
6. **Crash-resilient:** export memakai Reserve → Execute → Validate → Commit; retry tidak menggandakan output.
7. **Test-first:** setiap tahap dimulai dengan regression test yang gagal dan ditutup dengan acceptance gate terukur.

## Urutan dependensi

```
Tahap 0 — Baseline dan regression locks
    ↓
Tahap 1 — Kontrak intent + transaksi gesture
    ↓
Tahap 2 — Sequence model + append/reorder
    ↓
Tahap 3 — Timeline UI dan gesture arbitration
    ↓
Tahap 4 — Preview/audio graph performance
    ↓
Tahap 5 — Export Ogg + validation + recovery
    ↓
Tahap 6 — QA, observability, dan acceptance
```

Tahap tidak boleh dikerjakan terbalik. Redesign UI sebelum memperbaiki kontrak gesture hanya memindahkan bug ke composable baru.

---

## Tahap 0 — Baseline, fixture, dan regression locks

### Sasaran

Membekukan perilaku rusak dalam test agar patch berikutnya dapat dibuktikan dan tidak regresi.

### Perubahan file

- Tambah `editor/ui/EditorGestureIntegrationTest.kt`.
- Tambah `editor/model/SequenceOperationsTest.kt`.
- Tambah fixture `app/src/androidTest/assets/editor/`:
    - `a_2s.ogg`;
    - `b_3s.ogg`;
    - `silence_1s.ogg`;
    - `corrupt.ogg`.
- Tambah seam untuk menghitung invalidation, decoder seek, underrun, dan undo depth.

### Kontrak test pertama

```kotlin
@Test
fun `drag distance is based on immutable origin`() {
    val originMs = 1_000L
    val pxPerMs = 0.10f
    val gesture = MoveGesture(originMs, pxPerMs)

    gesture.addDeltaPx(10f)
    gesture.addDeltaPx(10f)
    gesture.addDeltaPx(10f)

    assertEquals(1_300L, gesture.previewStartMs)
}

@Test
fun `one drag produces one undo entry`() {
    val vm = editorViewModel(sessionWithTwoClips())

    vm.dispatch(EditorIntent.BeginMove("clip-b"))
    repeat(50) { vm.dispatch(EditorIntent.UpdateMove("clip-b", it * 10L)) }
    vm.dispatch(EditorIntent.CommitMove("clip-b"))

    assertEquals(1, vm.debugUndoDepth())
}
```

### Instrumentasi debug minimum

```kotlin
internal interface EditorMetrics {
    fun gestureStarted(mode: String, clipId: String)
    fun gestureCommitted(mode: String, updateCount: Int, durationMs: Long)
    fun rendererInvalidated(reason: String)
    fun decoderSeek(sourceId: String)
    fun previewUnderrun(queuedFrames: Long)
}
```

Gunakan no-op implementation pada release. Jangan mencatat URI, nama file, atau audio pengguna.

### Definition of Done

- [ ]  Test mereproduksi drag over-travel.
- [ ]  Test membuktikan direct-drag pada B tidak boleh mengubah A.
- [ ]  Test membuktikan 50 update harus menghasilkan satu undo.
- [ ]  Fixture Ogg dapat di-decode pada instrumentation test.
- [ ]  Baseline screenshot tersedia pada 320, 360, dan 412 dp.

---

## Tahap 1 — Kontrak intent dan transaksi gesture

### Sasaran

Menutup EDT-01, EDT-02, dan EDT-03 tanpa menunggu redesign visual.

### 1.1 Pisahkan committed session dan transient gesture

Jangan menulis hasil pointer movement langsung ke `CommandHistory`.

```kotlin
internal data class EditorUiState(
    val committedSession: EditorSession = EditorSession.empty(),
    val gesture: GesturePreview? = null,
    val playback: EditorPlaybackState = EditorPlaybackState(),
) {
    val visibleSession: EditorSession
        get() = gesture?.previewSession ?: committedSession
}

internal sealed interface GesturePreview {
    val clipId: String
    val baseline: EditorSession
    val previewSession: EditorSession

    data class Moving(
        override val clipId: String,
        override val baseline: EditorSession,
        override val previewSession: EditorSession,
        val originStartMs: Long,
        val previewStartMs: Long,
    ) : GesturePreview

    data class Trimming(
        override val clipId: String,
        override val baseline: EditorSession,
        override val previewSession: EditorSession,
        val edge: TrimEdge,
        val originStartMs: Long,
        val originEndMs: Long,
        val previewStartMs: Long,
        val previewEndMs: Long,
    ) : GesturePreview
}
```

### 1.2 Gunakan intent dengan ID dan lifecycle lengkap

```kotlin
internal sealed interface EditorIntent {
    data class SelectClip(val clipId: String?) : EditorIntent

    data class BeginMove(val clipId: String) : EditorIntent
    data class UpdateMove(
        val clipId: String,
        val proposedStartMs: Long,
    ) : EditorIntent
    data class CommitMove(val clipId: String) : EditorIntent

    data class BeginTrim(
        val clipId: String,
        val edge: TrimEdge,
    ) : EditorIntent
    data class UpdateTrim(
        val clipId: String,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
    ) : EditorIntent
    data class CommitTrim(val clipId: String) : EditorIntent

    data object CancelGesture : EditorIntent
}

enum class TrimEdge { START, END }
```

### 1.3 ViewModel: preview tanpa history, commit sekali

```kotlin
private fun beginMove(clipId: String) {
    val baseline = state.value.committedSession
    val clip = baseline.requireClip(clipId)

    _state.update {
        it.copy(
            gesture = GesturePreview.Moving(
                clipId = clipId,
                baseline = baseline,
                previewSession = baseline,
                originStartMs = clip.timelineStartMs,
                previewStartMs = clip.timelineStartMs,
            )
        )
    }
}

private fun updateMove(clipId: String, proposedStartMs: Long) {
    val moving = state.value.gesture as? GesturePreview.Moving ?: return
    if (moving.clipId != clipId) return cancelGesture()

    val preview = when (
        val result = TimelineOperations.moveClipPreview(
            moving.baseline,
            clipId,
            proposedStartMs,
        )
    ) {
        is TimelineResult.Accepted -> result.value
        is TimelineResult.Rejected -> moving.previewSession
    }

    _state.update {
        it.copy(
            gesture = moving.copy(
                previewSession = preview,
                previewStartMs = proposedStartMs,
            )
        )
    }
}

private fun commitMove(clipId: String) {
    val moving = state.value.gesture as? GesturePreview.Moving ?: return
    if (moving.clipId != clipId) return cancelGesture()

    val command = MoveClipCommand(clipId, moving.previewStartMs)
    val accepted = commandHistory.executeFromBaseline(moving.baseline, command)
    publishCommitted(accepted)
    _state.update { it.copy(gesture = null) }
}
```

<aside>
⚠️

`UpdateMove` tidak boleh memanggil `previewEngine.updateSession()`, `syncCacheReferences()`, atau menambah undo snapshot. Audio graph baru diperbarui sekali pada commit.

</aside>

### 1.4 CommandHistory harus mendukung atomic commit

```kotlin
@Synchronized
fun executeFromBaseline(
    baseline: EditorSession,
    command: EditorCommand,
): TimelineResult {
    if (!sameRevision(session, baseline)) {
        return TimelineResult.Rejected(TimelineError.STALE_BASELINE)
    }

    return when (val result = command.applyTo(baseline)) {
        is TimelineResult.Rejected -> result
        is TimelineResult.Accepted -> {
            undo.addLast(session)
            trimToCapacity(undo)
            session = withDirtyBaseline(result.value)
            redo.clear()
            TimelineResult.Accepted(session)
        }
    }
}
```

Tambahkan `revision: Long` pada session atau simpan revision di ViewModel agar gesture lama tidak dapat menimpa perubahan baru.

### 1.5 Compose: baseline posisi tidak berubah selama drag

```kotlin
var originStartMs by remember(clip.id) { mutableLongStateOf(0L) }
var accumulatedPx by remember(clip.id) { mutableFloatStateOf(0f) }
var lastPreviewMs by remember(clip.id) { mutableLongStateOf(clip.timelineStartMs) }

Modifier.pointerInput(clip.id, pxPerMs) {
    detectHorizontalDragGestures(
        onDragStart = {
            originStartMs = clip.timelineStartMs
            accumulatedPx = 0f
            lastPreviewMs = originStartMs
            onIntent(EditorIntent.BeginMove(clip.id))
        },
        onHorizontalDrag = { change, deltaPx ->
            change.consume()
            accumulatedPx += deltaPx
            lastPreviewMs = (originStartMs + accumulatedPx / pxPerMs)
                .roundToLong()
                .coerceIn(0L, latestStartMs)
            onIntent(EditorIntent.UpdateMove(clip.id, lastPreviewMs))
        },
        onDragEnd = { onIntent(EditorIntent.CommitMove(clip.id)) },
        onDragCancel = { onIntent(EditorIntent.CancelGesture) },
    )
}
```

### Definition of Done

- [ ]  Direct drag B mengubah B walaupun A sebelumnya terseleksi.
- [ ]  Jarak visual klip mengikuti jari secara linear.
- [ ]  Satu gesture menghasilkan satu undo.
- [ ]  Cancel mengembalikan baseline tanpa dirty state baru.
- [ ]  Revision mismatch ditolak, bukan last-write-wins.
- [ ]  Preview engine di-invalidasi maksimal satu kali per commit.

---

## Tahap 2 — Sequence model, append, dan reorder

### Sasaran

Mengubah default editor menjadi penggabungan audio berurutan dan menutup EDT-04.

### 2.1 Gunakan urutan sebagai source of truth

Untuk mode utama, jangan simpan `timelineStartMs` sebagai nilai bebas yang dapat drift. Derivasikan start dari urutan dan durasi efektif klip.

```kotlin
internal data class SequenceClip(
    val id: String,
    val source: AudioSourceRef,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val effects: ClipEffects = ClipEffects(),
    val gapAfterMs: Long = 0L,
    val crossfadeAfterMs: Long = 0L,
) {
    val sourceDurationMs: Long
        get() = sourceEndMs - sourceStartMs

    val outputDurationMs: Long
        get() = (sourceDurationMs / effects.normalizedSpeed)
            .roundToLong()
            .coerceAtLeast(1L)
}

internal data class SequenceSession(
    val id: String,
    val clips: List<SequenceClip>,
    val selectedClipId: String? = null,
    val revision: Long = 0L,
)
```

### 2.2 Buat projection ke render timeline

Audio renderer saat ini masih dapat memakai `AudioClip`. Tambahkan mapper agar migrasi tidak perlu big-bang rewrite.

```kotlin
internal fun SequenceSession.toRenderSession(): EditorSession {
    var cursorMs = 0L
    val rendered = clips.map { clip ->
        val result = AudioClip(
            id = clip.id,
            source = clip.source,
            sourceStartMs = clip.sourceStartMs,
            sourceEndMs = clip.sourceEndMs,
            timelineStartMs = cursorMs,
            effects = clip.effects,
        )
        cursorMs += clip.outputDurationMs + clip.gapAfterMs - clip.crossfadeAfterMs
        result
    }

    return EditorSession(
        id = id,
        tracks = listOf(
            EditorTrack(
                id = "sequence-main",
                name = "Hasil gabungan",
                clips = rendered,
            )
        ),
        selectedClipId = selectedClipId,
    )
}
```

### 2.3 Append banyak file secara atomik

```kotlin
suspend fun appendSources(uris: List<Uri>) {
    if (uris.isEmpty()) return

    val analyzed = uris.map { uri ->
        val metadata = sourceAnalyzer(uri)
        require(metadata.durationMs > 0L) { "Audio tidak memiliki durasi valid" }
        SequenceClip(
            id = UUID.randomUUID().toString(),
            source = AudioSourceRef(uri.toString(), metadata.durationMs),
            sourceStartMs = 0L,
            sourceEndMs = metadata.durationMs,
        )
    }

    execute(AppendSequenceClipsCommand(analyzed)) // satu undo untuk satu import batch
}
```

Jangan memasukkan sebagian file jika salah satu metadata gagal kecuali UI secara eksplisit menawarkan “lewati file gagal”.

### 2.4 Reorder berbasis indeks, bukan koordinat absolut

```kotlin
internal data class ReorderClipCommand(
    val clipId: String,
    val targetIndex: Int,
) : EditorCommand {
    override fun applyTo(session: SequenceSession): SequenceResult {
        val from = session.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return SequenceResult.Rejected(MISSING_ID)

        val items = session.clips.toMutableList()
        val moved = items.removeAt(from)
        val insertion = targetIndex.coerceIn(0, items.size)
        items.add(insertion, moved)

        return validate(session.copy(
            clips = items,
            revision = session.revision + 1,
        ))
    }
}
```

Reorder sequence harus menampilkan insertion marker. Pengguna tidak perlu mengatur milidetik untuk sekadar menyambungkan dua file.

### 2.5 Aturan crossfade

```kotlin
private fun validateJunction(left: SequenceClip, right: SequenceClip) {
    val maxCrossfade = minOf(
        left.outputDurationMs / 2,
        right.outputDurationMs / 2,
        5_000L,
    )
    require(left.crossfadeAfterMs in 0L..maxCrossfade)
    require(left.gapAfterMs == 0L || left.crossfadeAfterMs == 0L) {
        "Gap dan crossfade tidak boleh aktif bersamaan"
    }
}
```

### Migrasi draft

- Draft lama dengan satu track non-overlap dapat dikonversi otomatis ke sequence.
- Draft multitrack/overlap harus dibuka dalam “Legacy advanced mode” atau ditolak dengan pesan migrasi; jangan flatten diam-diam karena hasil audio berubah.
- Simpan `schemaVersion` dan hasil migrasi secara atomik.

### Definition of Done

- [ ]  Import A, B, C menghasilkan start 0, end A, end B.
- [ ]  Reorder C, A, B menghitung ulang seluruh junction secara deterministik.
- [ ]  Trim atau speed pada A otomatis menggeser B dan C.
- [ ]  Tidak ada overlap/gap kecuali dibuat eksplisit.
- [ ]  Satu import batch dan satu reorder masing-masing satu undo.
- [ ]  Draft lama tidak kehilangan audio atau efek saat migrasi.

---

## Tahap 3 — Timeline UI dan gesture arbitration

### Sasaran

Menutup EDT-05, EDT-06, EDT-09, EDT-10, EDT-11, EDT-12, dan EDT-13.

### 3.1 Layout target

```
Top app bar: Back | Nama proyek | Undo | Redo | ⋮
Transport: Play | 00:12.340 / 01:45.800 | Fit
Timeline/ruler: 65–75% area layar
Selected clip inspector: Trim | Split | Pitch | Speed | Fade | Hapus
Primary action: Gabungkan & simpan
```

- Jangan letakkan tiga tombol teks berbobot dalam satu Row sempit.
- Save draft menjadi autosave atau menu overflow.
- Pada lebar kecil, tool inspector memakai horizontally scrollable icon+label atau modal sheet.

### 3.2 Hit-test dan ownership gesture

Gunakan satu controller per pointer sequence.

```kotlin
sealed interface TimelineGesture {
    data object Idle : TimelineGesture
    data class Pan(val originScrollPx: Float) : TimelineGesture
    data class Pinch(val anchorTimeMs: Long) : TimelineGesture
    data class Move(val clipId: String) : TimelineGesture
    data class Trim(val clipId: String, val edge: TrimEdge) : TimelineGesture
}

fun classifyDown(hit: TimelineHit, pointerCount: Int): TimelineGesture = when {
    pointerCount >= 2 -> TimelineGesture.Pinch(anchorTimeMs = hit.timeMs)
    hit.trimEdge != null -> TimelineGesture.Trim(hit.clipId!!, hit.trimEdge)
    hit.clipId != null -> TimelineGesture.Move(hit.clipId)
    else -> TimelineGesture.Pan(originScrollPx = hit.scrollPx)
}
```

Aturan prioritas: trim handle > clip body > blank canvas. Pinch dua jari dapat mengambil alih pan, tetapi tidak boleh mengubah clip.

### 3.3 Jangan restart recognizer saat zoom berubah

```kotlin
val currentZoom by rememberUpdatedState(zoom)

Modifier.pointerInput(Unit) {
    awaitEachGesture {
        // baca currentZoom pada setiap event;
        // jangan gunakan pointerInput(zoom)
    }
}
```

### 3.4 Zoom mempertahankan anchor

```kotlin
suspend fun applyZoom(
    centroidX: Float,
    zoomChange: Float,
) {
    val oldPxPerMs = scale.pxPerMs
    val anchorMs = (scrollState.value + centroidX) / oldPxPerMs

    scale = scale.withZoom(
        (scale.zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
    )

    val nextScroll = anchorMs * scale.pxPerMs - centroidX
    scrollState.scrollTo(
        nextScroll.roundToInt().coerceIn(0, scrollState.maxValue)
    )
}
```

### 3.5 Lebar timeline mengikuti konten

```kotlin
val contentDurationMs = renderSession.durationMs
val viewportDurationMs = viewportWidthPx / scale.pxPerMs
val visibleDomainMs = maxOf(
    contentDurationMs + END_PADDING_MS,
    viewportDurationMs,
)
val timelineWidthPx = visibleDomainMs * scale.pxPerMs
```

Jangan lagi menggunakan `MAX_TIMELINE_MS` untuk lebar visual bila konten hanya 15 detik.

### 3.6 Adaptive ruler

```kotlin
fun tickStepMs(pxPerMs: Float): Long = when {
    pxPerMs * 100L >= 72f -> 100L
    pxPerMs * 500L >= 72f -> 500L
    pxPerMs * 1_000L >= 72f -> 1_000L
    pxPerMs * 5_000L >= 72f -> 5_000L
    else -> 10_000L
}
```

### 3.7 Waveform yang benar setelah trim

Jangan menggambar seluruh peak source pada setiap clip.

```kotlin
fun visiblePeaks(
    peaks: List<Int>,
    sourceDurationMs: Long,
    startMs: Long,
    endMs: Long,
): List<Int> {
    if (peaks.isEmpty() || sourceDurationMs <= 0L) return emptyList()

    val from = floor(startMs.toDouble() / sourceDurationMs * peaks.size)
        .toInt().coerceIn(0, peaks.lastIndex)
    val until = ceil(endMs.toDouble() / sourceDurationMs * peaks.size)
        .toInt().coerceIn(from + 1, peaks.size)
    return peaks.subList(from, until)
}
```

Untuk zoom tinggi, gunakan level-of-detail waveform pyramid agar tidak menggambar seluruh peak.

### 3.8 Trim inspector

```kotlin
@Composable
fun TrimInspector(
    clip: SequenceClip,
    preview: TrimPreview,
    onChange: (Long, Long) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        RangeWaveform(
            source = clip.source,
            startMs = preview.startMs,
            endMs = preview.endMs,
            onRangeChange = onChange,
        )
        Row {
            TimecodeField("Mulai", preview.startMs)
            TimecodeField("Selesai", preview.endMs)
        }
        Row {
            TextButton(onClick = onCancel) { Text("Batal") }
            Button(onClick = onApply) { Text("Terapkan") }
        }
    }
}
```

Tombol “Trim” hanya membuka inspector; tidak boleh langsung memotong 100 ms.

### Definition of Done

- [ ]  Drag klip tidak mem-pan viewport.
- [ ]  Pan ruang kosong tidak memindahkan klip.
- [ ]  Pinch tidak restart di tengah gesture dan anchor tidak bergeser.
- [ ]  Timeline pendek fit ke layar; timeline panjang dapat di-pan/zoom.
- [ ]  Trim waveform menunjukkan subset sumber yang benar.
- [ ]  320 dp dan font scale 2.0 tidak menghasilkan teks vertikal/terpotong.
- [ ]  Seluruh copy memakai bahasa Indonesia yang konsisten.

---

## Tahap 4 — Preview dan audio graph

### Sasaran

Menutup EDT-07 dan EDT-08 serta menjamin preview responsif.

### 4.1 Reader harus sequential-first

Implementasi lama melakukan `seekTo + codec.flush()` pada setiap read 960 frame. Ganti dengan cursor kontinu.

```kotlin
internal class StatefulPcmSourceReader(
    private val decoder: DecoderSession,
) : PcmSourceReader {
    private var cursorFrame: Long? = null

    override fun read(sourceFrame: Long, frameCount: Int): ShortArray {
        require(sourceFrame >= 0L && frameCount >= 0)

        if (cursorFrame != sourceFrame) {
            decoder.seekAndPrime(sourceFrame)
            cursorFrame = sourceFrame
        }

        val result = decoder.decodeForward(frameCount)
        cursorFrame = sourceFrame + result.size
        return result
    }

    override fun invalidate() {
        cursorFrame = null
        decoder.flush()
    }
}
```

`seekAndPrime()` harus decode dari sync point sampai exact requested frame; jangan menganggap timestamp decoder tepat pada target.

### 4.2 PCM cache untuk editing

Untuk pengalaman paling stabil, buat proxy PCM mono 48 kHz saat import.

```kotlin
internal data class EditingProxyKey(
    val sourceFingerprint: String,
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    val version: Int = 1,
)

suspend fun ensureEditingProxy(source: AudioSourceRef): ProxyHandle =
    singleFlight.run(keyFor(source)) {
        proxyCache.findValid(keyFor(source))
            ?: decoder.decodeToAtomicProxy(source)
    }
```

Gunakan temp file lalu atomic rename. Cache harus punya checksum/header validation dan reference ownership oleh session/draft/export.

### 4.3 Audio graph bersama

```kotlin
internal class EditorAudioGraph(
    private val sourceProvider: PcmSourceProvider,
    private val clipProcessor: ClipProcessor,
    private val junctionProcessor: JunctionProcessor,
    private val limiter: MasterLimiter,
) {
    fun render(
        snapshot: RenderSnapshot,
        startFrame: Long,
        frameCount: Int,
    ): ShortArray {
        val clips = snapshot.intersections(startFrame, frameCount)
        val mixed = renderClips(clips, startFrame, frameCount)
        val joined = junctionProcessor.apply(mixed, snapshot.junctions)
        return limiter.process(joined)
    }

    fun resetAt(frame: Long) {
        sourceProvider.seek(frame)
        clipProcessor.reset()
        junctionProcessor.reset()
        limiter.reset()
    }
}
```

Preview dan export harus menerima `RenderSnapshot` yang sama.

### 4.4 Limiter persisten

```kotlin
internal class DefaultTimelineRenderer(
    // ...
) : TimelineRenderer {
    private var limiter = MasterLimiter()

    override fun render(
        session: EditorSession,
        startFrame: Long,
        frameCount: Int,
    ): ShortArray = synchronized(renderLock) {
        val mixed = renderMixed(session, startFrame, frameCount)
        limiter.process(mixed)
    }

    override fun invalidate(sourceIds: Set<String>) = synchronized(renderLock) {
        closeAffectedReaders(sourceIds)
        clipProcessor.reset()
        limiter = MasterLimiter()
    }
}
```

### 4.5 Preview scheduling

- Target queue awal: 500–1.000 ms, bukan hard-coded 3 detik bila edit interaktif.
- Low-water mark memicu render berikutnya.
- Seek meningkatkan generation token dan membuang hasil render lama.
- Perubahan efek memakai debounce 50–100 ms; commit final langsung me-render.

```kotlin
private suspend fun renderLoop(generation: Long) {
    while (isCurrent(generation)) {
        val queued = sink.queuedFrames()
        if (queued < LOW_WATER_FRAMES) {
            val request = planner.nextChunk(MAX_CHUNK_FRAMES) ?: break
            val pcm = graph.render(snapshot, request.start, request.count)
            if (!isCurrent(generation)) return
            sink.writeFully(pcm)
        } else {
            delay(5L)
        }
    }
}
```

### 4.6 Kesetaraan preview/export

Buat hash PCM sebelum encoding pada fixture pendek:

```kotlin
@Test
fun `preview and export render identical PCM`() {
    val snapshot = fixtureSnapshot()
    val previewPcm = previewGraph.renderAll(snapshot)
    val exportPcm = exportGraph.renderAll(snapshot)

    assertContentEquals(previewPcm, exportPcm)
}
```

### Definition of Done

- [ ]  Decode kontigu tidak memanggil seek atau flush per chunk.
- [ ]  Limiter tidak di-reset antar-chunk kontinu.
- [ ]  Seek membatalkan stale render melalui generation token.
- [ ]  Preview start latency terukur dan tidak regress.
- [ ]  Tidak ada underrun pada fixture target/perangkat low-end.
- [ ]  PCM preview identik dengan PCM input encoder export.

---

## Tahap 5 — Export Ogg/Opus, validasi, dan recovery

### Sasaran

Memastikan satu output Ogg final konsisten, idempotent, dan tidak meninggalkan file parsial.

### 5.1 State machine export

```kotlin
enum class ExportStage {
    RESERVE,
    RENDER,
    ENCODE,
    FINALIZE_OGG,
    VALIDATE,
    PUBLISH,
    COMMIT_HISTORY,
    CLEANUP,
}

data class ExportFailure(
    val code: String,
    val stage: ExportStage,
    val retryable: Boolean,
    val safeMessage: String,
)
```

### 5.2 Reserve → Execute → Validate → Commit

```kotlin
suspend fun export(request: ExportRequest): ExportResult {
    val reservation = outputStore.reserve(request.attemptId, request.name)
    var committed = false

    try {
        outputStore.openPartial(reservation).use { output ->
            encoderFactory.open(output, request.preset).use { encoder ->
                graph.renderChunks(request.snapshot).collect { pcm ->
                    currentCoroutineContext().ensureActive()
                    encoder.write(pcm)
                }
                encoder.finish()
            }
        }

        val validation = validator.validateComplete(reservation.partialFile)
        if (!validation.valid) {
            return ExportResult.Failure(
                ExportFailure(
                    code = "OUTPUT_INVALID",
                    stage = ExportStage.VALIDATE,
                    retryable = true,
                    safeMessage = "Audio hasil tidak dapat divalidasi.",
                )
            )
        }

        val publicUri = outputStore.publish(reservation)
        val historyId = history.commitIdempotent(
            attemptId = request.attemptId,
            uri = publicUri,
            metadata = validation.metadata,
        )
        committed = true
        return ExportResult.Success(publicUri, historyId)
    } finally {
        if (!committed) outputStore.rollback(reservation)
    }
}
```

### 5.3 Validasi harus decode, bukan hanya header

```kotlin
internal interface OggOutputValidator {
    suspend fun validateComplete(file: File): ValidationResult
}

internal class DecodeBasedOggValidator(
    private val probe: AudioProbe,
) : OggOutputValidator {
    override suspend fun validateComplete(file: File): ValidationResult {
        val info = probe.decodeFully(file)
        return ValidationResult(
            valid = info.container == "ogg" &&
                info.codec == "opus" &&
                info.sampleRate == 48_000 &&
                info.channels == 1 &&
                info.decodedFrames > 0 &&
                info.completedWithoutError,
            metadata = info,
        )
    }
}
```

Pada instrumentation/CI, validasi silang dengan `ffprobe`/`ffmpeg -f null`. Di aplikasi, gunakan decoder bundled/platform yang membaca output sampai EOS.

### 5.4 Retry idempotent

- `attemptId` adalah idempotency key.
- Sebelum menjalankan ulang, cek history dan output reservation.
- Jangan menerbitkan nama baru jika attempt yang sama sudah sukses.
- Retry setelah user mengubah nama/preset/session harus memakai attempt baru.

```kotlin
fun shouldReuseAttempt(old: ExportAttempt, next: ExportRequest): Boolean =
    old.status == FAILED &&
    old.snapshotHash == next.snapshotHash &&
    old.outputName == next.name &&
    old.preset == next.preset
```

### 5.5 Cleanup ownership

Setiap artifact memiliki owner eksplisit:

```kotlin
data class ArtifactLease(
    val artifactId: String,
    val ownerType: OwnerType,
    val ownerId: String,
    val expiresAtMs: Long?,
)

enum class OwnerType { SESSION, DRAFT, EXPORT_ATTEMPT }
```

Maintenance worker hanya menghapus artifact tanpa lease aktif dan lebih tua dari retention threshold.

### Definition of Done

- [ ]  Output hanya terlihat setelah validasi sukses.
- [ ]  Cancel/failure menghapus partial dan reservation.
- [ ]  Retry attempt yang sama tidak membuat output/history ganda.
- [ ]  Perubahan request menghasilkan attempt baru.
- [ ]  Validator melakukan decode sampai EOS.
- [ ]  Durasi output sesuai hasil sequence dalam toleransi Opus pre-skip/end trim.

---

## Tahap 6 — QA, observability, rollout, dan acceptance

### Sasaran

Membuktikan fitur layak digunakan pada perangkat nyata dan mencegah regresi sesudah rilis.

### 6.1 Matriks pengujian

| Area | Kasus minimum |
| --- | --- |
| Input | Ogg Opus mono/stereo, pendek, panjang, VBR, corrupt, URI revoked |
| Sequence | 1, 2, 5, dan 20 klip; reorder; delete; split; gap; crossfade |
| Efek | pitch -4/0/+4, speed 0.5/1/2, fade, cleanup |
| UI | 320/360/412 dp, landscape, font scale 1.0/1.3/2.0 |
| Android | API minimum, 29, 33, 35/36; low-end dan high-end |
| Lifecycle | rotate, background, process recreation, low storage, cancel/retry |
| Output | decode penuh, waveform/durasi, Telegram playback |

### 6.2 Performance budget

Tetapkan gate, bukan sekadar “terasa lancar”:

- pointer-to-visual latency P95 < 32 ms;
- tidak ada command-history write saat pointer masih bergerak;
- preview start P95 < 500 ms untuk proxy yang tersedia;
- seek-to-audio P95 < 300 ms;
- AudioTrack underrun = 0 pada skenario acceptance;
- frame Compose jank < 5% selama drag/reorder;
- memory bounded dan tidak tumbuh linear terhadap durasi source.

### 6.3 Error taxonomy

```kotlin
sealed class EditorError(
    val code: String,
    val userMessage: String,
    val retryable: Boolean,
) {
    data object SourceUnavailable : EditorError(
        "SOURCE_UNAVAILABLE",
        "File audio tidak dapat dibaca. Pilih ulang file.",
        false,
    )

    data object PreviewUnderrun : EditorError(
        "PREVIEW_UNDERRUN",
        "Preview terhenti. Coba putar kembali.",
        true,
    )

    data object OutputValidationFailed : EditorError(
        "OUTPUT_VALIDATION_FAILED",
        "Audio hasil tidak valid dan belum disimpan.",
        true,
    )
}
```

UI menampilkan tindakan yang relevan: Pilih ulang, Coba lagi, Kurangi efek, atau Kosongkan ruang.

### 6.4 Rollout

1. Feature flag internal.
2. Dogfood dengan logging debug tanpa data audio.
3. Beta kecil dengan crash/ANR/performance monitoring.
4. Bandingkan export success rate, cancel rate, preview error, dan time-to-export.
5. Naikkan rollout hanya jika acceptance gate terpenuhi.
6. Pertahankan rollback path ke editor lama selama satu release; jangan mencampur draft schema tanpa versioning.

### Definition of Done final

- [ ]  Semua test Tahap 0–5 hijau.
- [ ]  Tidak ada P0/P1 terbuka.
- [ ]  Semua performance budget lulus pada perangkat target.
- [ ]  Output tiga fixture Ogg dapat diputar dan di-decode penuh.
- [ ]  Draft survive process death dan schema migration.
- [ ]  Tidak ada file partial/cache tanpa owner setelah terminal state.
- [ ]  Copy, accessibility, dan layout lulus pada font scale 2.0.
- [ ]  Rollback dan migrasi telah diuji.

---

## Pemetaan file implementasi

| File/komponen | Aksi |
| --- | --- |
| `EditorViewModel.kt` | Pisahkan committed/transient state; intent target ID; atomic commit |
| `TimelineCanvas.kt` | Baseline drag; gesture owner; anchor zoom; content-sized domain |
| `EditorModels.kt` | Tambah revision dan sequence model |
| `TimelineOperations.kt` | Tambah preview-safe operation, append, reorder, junction validation |
| `CommandHistory.kt` | Satu commit per gesture; stale baseline rejection |
| `EditorToolSheets.kt` | Trim inspector; tool rail; copy Indonesia |
| `EditorScreen.kt` | Timeline-first layout; CTA utama tunggal |
| `PcmSourceReader.kt` | Sequential cursor; seek hanya saat non-contiguous |
| `TimelineRenderer.kt` | Shared graph; persistent limiter; deterministic reset |
| `EditorPreviewEngine.kt` | Low-water queue, metrics, generation cancellation |
| `EditorExportWorker.kt` | Stage/error code, full validation, idempotent commit |
| Draft/cache layer | Schema version, proxy ownership, atomic migration/cleanup |
| Test packages | Integrasi gesture, golden layout, fixture audio, device E2E |

## Pull request strategy

Pisahkan perubahan agar review dan rollback aman:

1. **PR-1:** regression tests + metrics seam.
2. **PR-2:** intent target ID + gesture transaction.
3. **PR-3:** sequence model + legacy projection/migration.
4. **PR-4:** timeline UI + gesture arbitration.
5. **PR-5:** stateful decoder + shared audio graph.
6. **PR-6:** export validation + idempotent recovery.
7. **PR-7:** instrumentation matrix, performance gate, dan cleanup.

Setiap PR harus menyertakan test yang membuktikan finding terkait tertutup. Hindari satu PR besar yang mengubah UI, model, decoder, dan export sekaligus.

## Exit criteria proyek

<aside>
✅

Remediasi dinyatakan selesai hanya bila alur berikut lulus pada perangkat nyata: pilih A, B, C → otomatis tersusun berurutan → reorder B, A, C → trim A → pitch B → preview tanpa underrun → satu kali undo untuk setiap gesture → export → decode output sampai EOS → durasi dan urutan audio benar → tidak ada file partial atau history ganda setelah retry/process recreation.

</aside>
