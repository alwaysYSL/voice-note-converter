<aside>
🚨

**Verdict: NOT ACCEPTABLE / perlu redesign interaction sebelum dirilis.**

Keluhan “drag track terasa aneh” terkonfirmasi langsung dari source. Posisi drag dihitung dari state klip yang terus berubah ditambah delta kumulatif, sehingga perpindahan terakumulasi berulang dan klip melesat lebih jauh dari jari. Lebih fatal, intent drag tidak membawa `clipId`; ViewModel mengubah klip yang sedang terseleksi, bukan selalu klip yang disentuh. Setiap pointer event juga dibuat menjadi satu command permanen, memuat ulang preview, menutup decoder/effect state, dan memenuhi undo history. Kombinasi ini membuat gesture tidak stabil, salah target, patah-patah, dan sulit di-undo.

</aside>

## Ruang lingkup dan metode

- Source: repository GitHub dan ZIP source yang dilampirkan pada 16 September 2026.
- Fokus: model timeline, gesture Compose, state/command history, preview, PCM reader, mixer, efek, export Ogg/Opus, test, dan rancangan UI.
- Metode: audit statis menyeluruh pada `editor/model`, `editor/ui`, `editor/audio`, `editor/work`, cache/draft, dan test terkait.
- Unit test lokal dicoba, tetapi Gradle Wrapper membutuhkan unduhan Gradle 9.3.1 dari `services.gradle.org`; lingkungan audit tidak memiliki akses jaringan. Karena itu temuan runtime perlu dikonfirmasi lagi pada perangkat dengan fixture Ogg nyata.

!Screenshot test editor pada viewport 320 × 480 memperlihatkan tombol aksi bawah terkompres sampai teks vertikal dan timeline praktis tidak terlihat.

Screenshot test editor pada viewport 320 × 480 memperlihatkan tombol aksi bawah terkompres sampai teks vertikal dan timeline praktis tidak terlihat.

## Prinsip produk yang seharusnya menjadi pusat desain

Use case utama bukan DAW multitrack penuh. Pengguna ingin:

1. memilih beberapa file Ogg yang sudah dikompres;
2. menyusun urutannya secara berantai;
3. memotong awal/akhir dan memberi efek opsional;
4. mendengar preview yang akurat;
5. mengekspor satu file Ogg/Opus gabungan.

Arsitektur UI saat ini justru menonjolkan track paralel, playhead bebas, move absolut, mixer, mute, volume, serta timeline tetap lima menit. Ini menambah kompleksitas tanpa membuat alur “gabungkan berurutan” mudah atau aman.

## Ringkasan temuan

| ID | Prioritas | Temuan | Dampak pengguna |
| --- | --- | --- | --- |
| EDT-01 | P0 | Perhitungan drag mengakumulasi posisi state baru + delta kumulatif | Klip melesat, tidak mengikuti jari |
| EDT-02 | P0 | Intent Move/Trim tidak membawa `clipId` | Klip lain yang terseleksi dapat berubah |
| EDT-03 | P0 | Setiap event drag menjadi command + reload preview | Jank, audio putus, undo history cepat penuh |
| EDT-04 | P0 | “Add track” membuat lane paralel pada playhead, bukan append berantai | File dapat tertumpuk/mix, bukan digabung urut |
| EDT-05 | P1 | Gesture pan, zoom, tap-seek, move, dan trim saling berkompetisi | Scroll/drag sering tertangkap recognizer yang salah |
| EDT-06 | P1 | `pointerInput(zoom)` restart selama pinch | Pinch dapat terputus dan viewport meloncat |
| EDT-07 | P1 | Decoder melakukan seek + flush untuk setiap chunk 20 ms | Preview boros, lambat, rawan underrun |
| EDT-08 | P1 | Master limiter dibuat ulang setiap pemanggilan render | Gain envelope tidak kontinu; preview/export dapat pumping |
| EDT-09 | P1 | Toolbar “Trim” diam-diam memotong 100 ms | Aksi destruktif tanpa mode/konfirmasi yang jelas |
| EDT-10 | P1 | Layout bawah memakai tiga tombol berbobot dalam satu Row sempit | Teks pecah vertikal; timeline kehilangan ruang |
| EDT-11 | P1 | Timeline selalu selebar lima menit dan tidak virtualized | Navigasi panjang, ruang kosong dominan, rendering tidak efisien |
| EDT-12 | P1 | Waveform tidak dipotong sesuai source range | Visual tidak lagi merepresentasikan audio setelah trim |
| EDT-13 | P2 | Bahasa UI campur Indonesia–Inggris dan label teknis | Hierarki dan affordance sulit dipahami |
| EDT-14 | P1 | Test gesture memeriksa intent, bukan state akhir/jarak jari | Bug integrasi drag lolos walau test terlihat lengkap |

---

## EDT-01 — P0: rumus drag menyebabkan gerakan eksponensial/berlebih

Lokasi: `TimelineCanvas.kt:380–464`.

Implementasi menyimpan `dragOffsetPx` sebagai delta kumulatif, tetapi menghitung posisi dari `currentClip.timelineStartMs`. Setelah event pertama diproses, ViewModel menerbitkan klip dengan posisi baru; event kedua lalu menambahkan seluruh delta kumulatif ke posisi baru itu.

Contoh sederhana: posisi awal 0 ms; event menghasilkan delta kumulatif 100 ms, lalu 200 ms, lalu 300 ms. Posisi yang dikirim menjadi 100, kemudian 300, kemudian 600 ms—padahal jari baru bergerak 300 ms.

### Patch minimum

Ambil posisi awal sekali pada `onDragStart`, hitung preview relatif terhadap baseline tetap, dan commit sekali saat selesai.

```kotlin
sealed interface EditorIntent {
    data class BeginMove(val clipId: String) : EditorIntent
    data class PreviewMove(val clipId: String, val timelineStartMs: Long) : EditorIntent
    data class CommitMove(val clipId: String, val timelineStartMs: Long) : EditorIntent
    data object CancelGesture : EditorIntent
}

var originStartMs by remember { mutableLongStateOf(0L) }
var totalDragPx by remember { mutableFloatStateOf(0f) }
var previewStartMs by remember { mutableLongStateOf(clip.timelineStartMs) }

detectHorizontalDragGestures(
    onDragStart = {
        originStartMs = currentClip.timelineStartMs
        totalDragPx = 0f
        onIntent(EditorIntent.BeginMove(currentClip.id))
    },
    onHorizontalDrag = { change, dx ->
        change.consume()
        totalDragPx += dx
        previewStartMs = (originStartMs + totalDragPx / pxPerMs)
            .roundToLong()
            .coerceIn(0L, latestAllowedStart)
        onIntent(EditorIntent.PreviewMove(currentClip.id, previewStartMs))
    },
    onDragEnd = {
        onIntent(EditorIntent.CommitMove(currentClip.id, previewStartMs))
    },
    onDragCancel = { onIntent(EditorIntent.CancelGesture) },
)
```

## EDT-02 — P0: gesture dapat mengubah klip yang salah

Lokasi:

- `TimelineCanvas.kt:357–360`: callback hanya mengirim angka.
- `EditorViewModel.kt:442–458`: target diambil lagi melalui `selectedClip()`.

Jika pengguna langsung men-drag klip B sementara A masih terseleksi, gesture milik B mengirim `Move(start)`, tetapi ViewModel mengeksekusi `MoveClipCommand` terhadap A. `clickable` bukan jaminan seleksi terjadi sebelum horizontal drag selesai.

### Perbaikan wajib

Semua mutasi kontekstual harus membawa identitas target.

```kotlin
sealed interface EditorIntent {
    data class MoveClip(val clipId: String, val timelineStartMs: Long) : EditorIntent
    data class TrimClip(
        val clipId: String,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
    ) : EditorIntent
}

is EditorIntent.MoveClip -> execute(
    MoveClipCommand(intent.clipId, intent.timelineStartMs)
)
is EditorIntent.TrimClip -> execute(
    TrimClipCommand(intent.clipId, intent.sourceStartMs, intent.sourceEndMs)
)
```

Seleksi adalah state UI; identitas command tidak boleh bergantung pada seleksi yang bisa berubah secara asinkron.

## EDT-03 — P0: setiap pixel drag menjadi transaksi permanen

Lokasi: `EditorViewModel.kt:426–478` dan `publishSession():1634–1657`.

Setiap `onHorizontalDrag` memanggil command. Setiap command:

- menambah snapshot undo;
- memvalidasi seluruh session;
- menerbitkan Compose state baru;
- memanggil `previewEngine.updateSession()`;
- membatalkan render job;
- pause/flush AudioTrack;
- meng-invalidasi reader dan effect processor;
- menghitung ulang cache references.

Pada 60–120 pointer event per detik, ini adalah desain yang pasti terasa berat. Undo capacity hanya 50; satu drag singkat dapat menghapus seluruh riwayat edit sebelumnya.

### Desain transaksi gesture

- **Begin:** simpan immutable baseline.
- **Update:** hanya ubah overlay/transient preview; jangan masuk command history dan jangan membuka ulang decoder.
- **Commit:** validasi dan masukkan satu command saat jari dilepas.
- **Cancel:** kembalikan baseline.
- Saat playback aktif, pause sekali di Begin atau gunakan debounced preview; jangan flush per pointer event.

## EDT-04 — P0: model default tidak sesuai fungsi “concatenate”

Lokasi: `EditorViewModel.importTrack():705–751`.

“Add track” membuat `EditorTrack` baru dan menaruh klip pada `session.playheadMs`. Jika playhead berada di 0, semua file baru mulai di 0 dan `TrackMixer` mencampurnya bersamaan. Ini adalah overlay/mixing, bukan penggabungan berurutan.

### Perbaikan produk

Jadikan **Sequence mode** sebagai default:

```kotlin
fun appendClip(session: EditorSession, source: AudioSourceRef): TimelineResult {
    val target = session.tracks.firstOrNull()
        ?: EditorTrack(id = "sequence", name = "Hasil gabungan")
    val appendAt = target.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val clip = AudioClip(
        id = UUID.randomUUID().toString(),
        source = source,
        sourceStartMs = 0L,
        sourceEndMs = source.durationMs,
        timelineStartMs = appendAt,
    )
    return if (session.tracks.isEmpty()) {
        TimelineOperations.addTrack(session, target.copy(clips = listOf(clip)))
    } else {
        TimelineOperations.addClip(session, target.id, clip)
    }
}
```

Jika multitrack tetap dibutuhkan, letakkan sebagai “Mode lanjutan”, bukan alur utama.

## EDT-05/06 — P1: arena gesture tidak memiliki kepemilikan yang jelas

Timeline memasang:

- `horizontalScroll`;
- `detectTapGestures` untuk seek;
- `detectTransformGestures` untuk zoom sekaligus pan;
- horizontal drag pada klip;
- horizontal drag lagi pada dua trim handle.

`detectTransformGestures` juga menerima pan satu jari. Pada node yang sama dengan `horizontalScroll`, hasilnya bergantung pada konsumsi event. Selain itu key `pointerInput(zoom)` berubah pada setiap pinch update sehingga coroutine recognizer dibatalkan di tengah gesture.

### Arah perbaikan

- Satu state machine gesture: `Idle | Panning | Moving(clipId) | Trimming(clipId, edge) | Pinching`.
- Pinch hanya aktif saat `pointerCount >= 2`.
- Satu jari di ruang kosong = pan; tap tanpa melewati touch slop = seek.
- Satu jari di body klip = move; di handle eksplisit = trim.
- Jangan key `pointerInput` dengan nilai zoom yang berubah selama gesture.
- Pertahankan titik fokus pinch:

```kotlin
val oldPxPerMs = pxPerMs
val anchorMs = (scrollPx + centroid.x) / oldPxPerMs
zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
val newScroll = anchorMs * newPxPerMs - centroid.x
scrollState.scrollTo(newScroll.roundToInt().coerceAtLeast(0))
```

## EDT-07 — P1: preview men-decode ulang dengan seek/flush per chunk 20 ms

Lokasi:

- `EditorPreviewEngine.RENDER_CHUNK_FRAMES = 960` (20 ms pada 48 kHz).
- `PcmSourceReader.readInternal():148–150` selalu memanggil `extractor.seekTo(... PREVIOUS_SYNC)` dan `codec.flush()`.

Walaupun reader dipertahankan, decoder tidak benar-benar streaming. Untuk preview 3 detik, sekitar 150 chunk dapat memicu seek dan flush. Pada Ogg/Opus, seek ke sync sebelumnya lalu decode-forward berulang sangat mahal dan dapat menghasilkan underrun.

### Perbaikan

Buat reader stateful dengan cursor:

```kotlin
if (sourceFrame == decodedCursorFrame) {
    return decodeForward(frameCount) // jalur normal
}
seekAndPrime(sourceFrame)             // hanya untuk seek non-kontigu
return decodeForward(frameCount)
```

Gunakan chunk 40–100 ms untuk scheduling, ring buffer, prefetch adaptif, dan statistik underrun. Untuk source Ogg yang sudah sesuai format, pertimbangkan decode satu kali ke PCM cache saat import agar trim/effect/preview memiliki random access stabil.

## EDT-08 — P1: state limiter ter-reset antar render call

Lokasi: `DefaultTimelineRenderer.render():52–69`.

`MasterLimiter()` dibuat di dalam setiap pemanggilan `render`. Preview engine memanggil renderer per 960 frame; envelope limiter kembali ke state awal tiap 20 ms. Ini berpotensi menimbulkan pumping, diskontinuitas level, atau perbedaan boundary.

Simpan limiter sebagai state renderer dan reset hanya pada seek/invalidate:

```kotlin
private var limiter = MasterLimiter()

override fun invalidate(sourceIds: Set<String>) = synchronized(lock) {
    limiter = MasterLimiter()
    // close/reset decoder and DSP state
}
```

Export dan preview harus memakai aturan state reset yang identik.

## EDT-09 — P1: tombol “Trim” bukan membuka alat trim

Lokasi: `EditorToolSheets.kt:75–88`.

Tombol berlabel “Trim” langsung mengurangi end sebanyak 100 ms. Ini mengejutkan, tidak memberi feedback boundary, dan setiap tap destruktif. Trim seharusnya memilih mode/inspector dengan dua timecode, handle, waveform detail, tombol Reset, dan Apply/Cancel.

## EDT-10/11/12 — P1: struktur UI mengorbankan timeline

Masalah terkonfirmasi:

- header track tetap 136 dp; pada layar 320 dp, area timeline tersisa sangat kecil;
- bottom bar memaksa Undo, Redo, Add track, Save draft, dan Export dalam satu Row;
- tiga tombol berbobot memecah teks menjadi karakter vertikal;
- toolbar efek horizontal menyembunyikan aksi tanpa petunjuk;
- timeline selalu memakai `MAX_TIMELINE_MS` (5 menit): 5.400 dp pada zoom 1;
- ruler hanya memberi label per 10 detik, terlalu kasar saat trim;
- waveform yang sama diregangkan ke klip, tanpa cropping berdasarkan `sourceStartMs/sourceEndMs`.

### Rancangan UI yang disarankan

**Layar utama — Sequence editor**

- Top app bar: Back, nama proyek, Undo/Redo icon, overflow.
- Transport ringkas: Play/Pause, timecode aktual / total, tombol “fit timeline”.
- Timeline mengambil 65–75% tinggi layar.
- Satu lane “Hasil gabungan” dengan kartu klip berurutan.
- Tombol tambah berbentuk `+ Tambah audio` di akhir sequence.
- Bottom tool rail hanya untuk klip terpilih: Trim, Split, Pitch, Speed, Fade, Delete.
- CTA utama tunggal: `Gabungkan & simpan`.
- Save draft pindah ke overflow atau autosave lokal.

**Interaksi klip**

- Tap = pilih.
- Drag body = reorder; default snap ke antar-klip, bukan posisi ms bebas.
- Long-press + drag = reorder dengan haptic pickup/drop.
- Trim = inspector khusus; preview loop 1–2 detik di sekitar boundary.
- Gap hanya dibuat melalui aksi eksplisit “Tambah jeda”.
- Crossfade opsional muncul pada sambungan dua klip.

**Zoom dan ruler**

- lebar konten berdasarkan `max(sessionDuration, viewportDuration)`, bukan lima menit tetap;
- adaptasikan tick: 100 ms / 500 ms / 1 s / 5 s / 10 s sesuai zoom;
- tombol Fit, zoom slider opsional, dan auto-scroll saat drag mendekati tepi;
- waveform harus mengambil subset peak sesuai range sumber.

Contoh pemetaan waveform trim:

```kotlin
val from = (sourceStartMs.toDouble() / source.durationMs * peaks.size)
    .toInt().coerceIn(0, peaks.lastIndex)
val until = ceil(sourceEndMs.toDouble() / source.durationMs * peaks.size)
    .toInt().coerceIn(from + 1, peaks.size)
val visiblePeaks = peaks.subList(from, until)
```

## EDT-13 — P2: copy dan terminologi tidak konsisten

UI mencampur “Audio editor”, “Add track”, “Export”, “Simpan sebagai draft”, “Ganti file”, dan pesan error Inggris. Gunakan satu bahasa serta istilah berdasarkan tujuan:

- “Tambah audio”, bukan “Add track”;
- “Gabungkan & simpan”, bukan “Export edited timeline”;
- “Urutan audio”, bukan “TRACKS” untuk mode utama;
- tampilkan durasi total nyata, bukan “0:02 / timeline”;
- pesan error menyebut item dan tindakan pemulihan.

## EDT-14 — P1: test belum memvalidasi kontrak UX

Test Compose saat ini banyak mengumpulkan `EditorIntent` tanpa menjalankannya melalui ViewModel. Akibatnya test tidak mendeteksi bahwa `currentClip` berubah selama gesture atau bahwa intent tanpa ID mengubah selection target yang salah. Test “changing selection” juga melakukan klik lebih dulu, berbeda dari gesture pengguna yang langsung drag.

### Test yang wajib ditambahkan

1. **Drag distance invariant:** tiga event 10 px menghasilkan total 30 px, bukan 60 px.
2. **Target invariant:** A terseleksi; langsung drag B; hanya B berubah.
3. **Single undo:** 50 pointer event dalam satu gesture menghasilkan tepat satu undo entry.
4. **No decoder churn:** preview engine tidak di-invalidate per update transient.
5. **Gesture arbitration:** drag klip tidak mem-pan viewport; pan ruang kosong tidak memindahkan klip.
6. **Pinch continuity:** satu pinch mempertahankan anchor time di bawah centroid.
7. **Sequence append:** file B otomatis mulai pada end A.
8. **Reorder:** B dipindah sebelum A menghasilkan start berurutan dan tanpa gap/overlap.
9. **Trim accuracy:** output waveform/durasi/PCM cocok dengan range sumber.
10. **Golden layout:** 320×480, 360×640, 412×915, font scale 1.3/2.0, landscape.
11. **Device E2E:** 3 Ogg nyata → trim/pitch → preview → satu Ogg decodable.
12. **Audio validation:** hasil diuji dengan `ffprobe` dan decode penuh, bukan hanya magic bytes/header.

Contoh test integrasi drag:

```kotlin
@Test
fun dragBWithoutPreselectionMovesOnlyBAndCreatesOneUndo() {
    val vm = realEditorViewModel(session = session(selected = "A"))
    compose.setContent { EditorScreen(vm.uiState.collectAsState().value, vm::dispatch) }

    compose.onNodeWithTag("clip-B").performTouchInput {
        down(center)
        repeat(5) { moveBy(Offset(10f, 0f)) }
        up()
    }

    assertEquals(0L, vm.clip("A").timelineStartMs)
    assertEquals(expectedBStart, vm.clip("B").timelineStartMs)
    assertEquals(1, vm.undoDepth)
}
```

---

## Arsitektur target yang direkomendasikan

```
EditorSession
├── SequenceLane (default)
│   └── ordered Clip[]
├── AdvancedTrack[] (optional)
├── transientGesture: GesturePreview?
└── committedRevision

EditorViewModel
├── beginGesture(targetId, mode)
├── updateGesture(delta)       // transient only
├── commitGesture()            // one command
├── cancelGesture()
└── appendSourcesInOrder()

AudioGraph
├── Source PCM cache / sequential decoder
├── Clip range + speed + pitch + gain + fades
├── Junction crossfade / gap
├── Persistent master limiter
└── Software Opus encoder + Ogg writer
```

Prinsip implementasi:

- ID target eksplisit pada setiap command.
- Satu gesture = satu transaksi undo.
- UI preview transient dipisahkan dari model committed.
- Decode kontigu tidak melakukan seek/flush.
- Sequence mode fail-closed terhadap overlap/gap tak disengaja.
- Preview dan export menggunakan graph serta state-reset semantics yang sama.
- Output baru dipublikasikan setelah encode dan decode validation berhasil.

## Tahapan remediasi

### Tahap 0 — hentikan regresi

- Bekukan penambahan efek baru.
- Tambahkan test EDT-01/02/03 terlebih dahulu.
- Telemetri lokal debug: gesture mode, target ID, delta, commit count, render latency, underrun.

### Tahap 1 — P0 interaction patch

- Ubah Move/Trim agar membawa `clipId`.
- Baseline drag immutable.
- Pisahkan transient update dan single commit.
- Hapus dependency mutasi terhadap selection.
- Pastikan satu drag = satu undo.

### Tahap 2 — selaraskan produk dengan concatenate

- Sequence mode sebagai default.
- Multi-select import mempertahankan urutan pilihan.
- Append/reorder otomatis menghitung timeline start.
- Gap/crossfade sebagai fitur eksplisit.

### Tahap 3 — preview/audio performance

- Stateful sequential decoder atau PCM cache saat import.
- Persistent limiter.
- Debounce expensive graph rebuild.
- Ukur P50/P95 render time dan AudioTrack underrun.

### Tahap 4 — redesign UI

- Timeline-first layout.
- Bottom tool rail kontekstual.
- CTA “Gabungkan & simpan”.
- Adaptive ruler/waveform crop/fit-to-content.
- Perbaiki localization dan accessibility.

### Tahap 5 — acceptance gate

- Test unit + Compose integration + instrumentation device.
- Fixture Ogg pendek/panjang, VBR, corrupt, revoked URI.
- Validasi output dengan decode penuh.
- Uji API/ABI target dan perangkat low-end.

## Kriteria penerimaan

- [ ]  Drag 100 px mengubah posisi tepat ekuivalen 100 px pada zoom aktif, toleransi ≤1 frame.
- [ ]  Klip yang disentuh selalu menjadi satu-satunya target mutasi.
- [ ]  Satu drag atau trim menghasilkan satu entry undo.
- [ ]  Tidak ada preview restart/invalidate pada setiap pointer event.
- [ ]  Menambah tiga file menghasilkan A → B → C secara otomatis tanpa overlap/gap.
- [ ]  Reorder memiliki snap, haptic, auto-scroll, dan feedback drop yang jelas.
- [ ]  Trim menampilkan waveform range akurat dan timecode frame/sample-consistent.
- [ ]  Pitch/speed terdengar sama pada preview dan export.
- [ ]  Preview 5 menit pada perangkat low-end tidak underrun dalam skenario normal.
- [ ]  UI layak pada lebar 320 dp dan font scale 2.0 tanpa teks vertikal/terpotong.
- [ ]  Output adalah satu Ogg/Opus yang dapat di-decode penuh dan durasinya sesuai ekspektasi.
- [ ]  Kegagalan tidak mempublikasikan file parsial dan memberi pesan pemulihan yang spesifik.

<aside>
✅

**Kesimpulan akhir**

Masalah editor bukan sekadar “UI kurang bagus”. Ada kesalahan kontrak state dan transaksi gesture pada jalur paling dasar. Patch paling mendesak adalah: target ID eksplisit, baseline drag tetap, transient preview, dan single commit. Setelah itu, produk perlu diarahkan ulang dari mini-DAW multitrack menjadi sequence editor yang mengutamakan append/reorder/trim lalu menghasilkan satu Ogg. Tanpa empat perbaikan P0 tersebut, polishing visual saja tidak akan membuat editor layak digunakan.

</aside>

Rencana Implementasi Remediasi Editor — Tahap 0–6
