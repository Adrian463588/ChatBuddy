# PRD — ChatBuddy: Local AI Chatbot Android App

**Version:** 1.0  
**Status:** Draft  
**Last Updated:** 2026-08-27
**Platform:** Android (minSdk 26 / targetSdk 35)  
**Package:** `com.chatbuddy`

---

## 1. Overview

ChatBuddy adalah aplikasi Android AI chatbot lokal berbasis Jetpack Compose yang berjalan **offline secara default** setelah setup awal. Aplikasi menggabungkan RAG (Retrieval-Augmented Generation) berbasis dokumen lokal, terjemahan realtime, OCR, dan penerjemahan gambar. Pencarian web hanya berjalan setelah pengguna mengaktifkan fallback secara eksplisit; dokumen, persona, dan prompt sistem tidak pernah dikirim ke server eksternal.

### 1.1 Masalah yang Diselesaikan

| Masalah | Solusi ChatBuddy |
|---------|-----------------|
| Privasi data chatbot ke cloud | Local-first, zero telemetri, web fallback opt-in per pertanyaan |
| LLM tidak memahami dokumen pribadi | RAG lokal dengan embedding ONNX |
| Terjemahan realtime butuh internet | ML Kit offline translation |
| OCR butuh koneksi/scan ke server | ML Kit offline text recognition |
| Model AI hilang saat uninstall | Penyimpanan via SAF folder yang persisten |
| Download model besar terputus | Resumable download dengan Range request |

### 1.2 Target Pengguna

- Mahasiswa dan peneliti yang bekerja dengan dokumen besar (skripsi, jurnal, buku)
- Profesional yang butuh terjemahan offline (perjalanan, zona tanpa sinyal)
- Pengguna yang peduli privasi data

---

## 2. Feature Requirements

### F-01: AI RAG Chat Lokal (Dokumen)

**Prioritas:** P0 — Fitur Inti

**Deskripsi:**  
Pengguna dapat melakukan percakapan dengan chatbot yang merespons berdasarkan konten dokumen yang telah di-embed ke vector database lokal.

**Acceptance Criteria:**

- [ ] Pengguna dapat memilih SAF folder sebagai sumber dokumen RAG
- [ ] Mendukung format: PDF, DOCX, TXT
- [ ] File hingga 200 MB per file dapat diproses (streaming, bukan load ke memori)
- [ ] Chunking 512 token dengan 50 token overlap
- [ ] Embedding menggunakan `all-MiniLM-L6-v2` (INT8 ONNX, ~23 MB) -> 384 dimensi
- [ ] Vector store menggunakan `sqlite-vec` (footprint 30 MB)
- [ ] Top-K retrieval (default K=5) sebelum prompt ke LLM
- [ ] Respons di-stream token-by-token ke UI
- [ ] Mode toggle: RAG aktif / tanpa RAG (plain LLM chat)
- [ ] Kutipan sumber ditampilkan di bawah respons AI

**Pipeline:**
```
Query -> Embed(query) -> VectorSearch(K=5) -> BuildPrompt(ctx+query) -> LLM.stream() -> UI
```

---

### F-02: Terjemahan Realtime (Timekettle-style)

**Prioritas:** P0 — Fitur Inti

**Deskripsi:**  
Layar terjemahan dua panel dengan deteksi input teks realtime. Terjemahan terjadi otomatis dengan debounce 300 ms setelah pengguna berhenti mengetik. Setelah paket bahasa diunduh sekali, semua operasi berjalan offline.

**Acceptance Criteria:**

- [ ] Pilih bahasa sumber dan bahasa target via dropdown (50+ bahasa via ML Kit)
- [ ] Input teks -> terjemahan muncul otomatis (debounce 300 ms)
- [ ] Download paket bahasa sekali, lalu offline permanen
- [ ] Swap bahasa (sumber <-> target) dengan satu tombol
- [ ] Salin teks hasil terjemahan
- [ ] Riwayat terjemahan tersimpan di Room DB
- [ ] Indikator status model: Downloaded / Downloading / Not Available

**Referensi inspirasi:** Timekettle M4 Pro, Lenovo ERAZER Translator

---

### F-03: OCR Dokumen & Gambar

**Prioritas:** P0 — Fitur Inti

**Deskripsi:**  
Ekstraksi teks dari gambar atau foto menggunakan ML Kit Text Recognition. Berjalan sepenuhnya offline.

**Acceptance Criteria:**

- [ ] OCR dari galeri foto (pick from SAF)
- [ ] OCR dari kamera realtime (CameraX + tap to capture)
- [ ] Mendukung teks Latin dan CJK (Chinese/Japanese/Korean)
- [ ] Tampilkan bounding box overlay teks yang terdeteksi
- [ ] Teks hasil OCR dapat disalin, diedit, atau diteruskan ke modul terjemahan
- [ ] Hasil OCR dapat di-paste langsung ke chat RAG sebagai query

---

### F-04: Terjemahan Gambar / Kamera (OCR + Translate)

**Prioritas:** P1 — Penting

**Deskripsi:**  
Pipeline gabungan: Kamera -> OCR -> Terjemahan. Pengguna mengarahkan kamera ke teks asing, lalu hasil terjemahan muncul di layar dengan overlay pada posisi teks asli.

**Acceptance Criteria:**

- [ ] Capture gambar -> OCR -> terjemahan otomatis dalam satu alur
- [ ] Overlay terjemahan blok-per-blok di atas gambar asli
- [ ] Pilih bahasa sumber dan target
- [ ] Seluruh pipeline offline setelah model terunduh
- [ ] Hasil terjemahan dapat disalin/dibagikan

**Pipeline:**
```
CameraX.capture() -> InputImage -> MLKit.OCR() -> TranslationEngine.translate() -> OverlayUI
```

---

### F-05: AI Persona Configuration

**Prioritas:** P1 — Penting

**Deskripsi:**  
Pengguna dapat mengustomisasi kepribadian AI dengan system prompt, temperature, dan parameter generasi.

**Acceptance Criteria:**

- [ ] Template bawaan: Sunny Companion, Study Buddy, Practical Guide, Translation Helper
- [ ] Template bawaan memiliki aturan probing terbatas: satu pertanyaan klarifikasi yang fokus hanya ketika ambiguitas memengaruhi jawaban
- [ ] Edit system prompt bebas (textarea)
- [ ] Slider temperature (0.1 - 1.0, default 0.7)
- [ ] Slider top-p (0.5 - 1.0, default 0.9)
- [ ] Max tokens (256 - 4096, default 2048)
- [ ] Simpan beberapa persona, pilih satu sebagai aktif
- [ ] Delete dan duplicate persona
- [ ] Persona aktif diterapkan ke semua sesi chat baru

---

### F-06: Download Model & Bundle (Resumable)

**Prioritas:** P0 — Fitur Inti

**Deskripsi:**  
Pengguna mengunduh LLM GGUF, embedding model ONNX, dan bundle lainnya via UI download manager. Semua file disimpan ke SAF folder yang dipilih pengguna — file tetap ada setelah app di-uninstall.

**Acceptance Criteria:**

- [ ] UI download screen dengan daftar bundle yang tersedia:
  - LLM: Gemma 4 E2B IT Q4_0 (GGUF, 2.84 GB; pinned verified artifact)
  - Embedding: MiniLM-L6-v2 INT8 (ONNX, ~23 MB)
  - OCR bundle (ML Kit, bundled)
  - Translation pack per bahasa (ML Kit, download on demand)
- [ ] Tombol Download per bundle
- [ ] Progress bar dengan persentase dan ukuran bytes
- [ ] Tombol Pause / Resume per download
- [ ] Auto-resume saat sinyal kembali (WorkManager + HTTP Range header)
- [ ] Checkpoint disimpan ke SharedPreferences
- [ ] File disimpan ke SAF folder (persisten setelah uninstall)
- [ ] Tulis ke .tmp dulu, rename ke nama final setelah selesai (atomic write)
- [ ] Status per bundle: Not Downloaded / Downloading / Paused / Complete / Error
- [ ] Retry otomatis saat gagal (WorkManager exponential backoff)

---

### F-07: SAF Persistent Storage

**Prioritas:** P0 — Infrastruktur

**Deskripsi:**  
Semua file besar (model AI, dokumen RAG) disimpan di folder SAF yang dipilih pengguna. Permission URI persisten disimpan system, tidak hilang saat uninstall.

**Acceptance Criteria:**

- [ ] Onboarding flow: pilih SAF folder pada first launch
- [ ] URI permission diambil via `takePersistableUriPermission`
- [ ] URI disimpan di SharedPreferences
- [ ] Validasi URI masih valid setiap app launch (detect jika folder dihapus user)
- [ ] Re-picker jika URI tidak valid
- [ ] Dokumen RAG juga dipilih dari SAF (tidak perlu copy ke app internal)

---

## 3. Non-Functional Requirements

### 3.1 Performance

| Metrik | Target |
|--------|--------|
| First token latency (LLM) | < 3 detik pada mid-range device |
| Embedding per chunk | < 200 ms (MiniLM INT8, 2 threads) |
| Vector search K=5 | < 10 ms (sqlite-vec brute force) |
| OCR latency | < 1.5 detik per frame |
| Translation debounce | 300 ms setelah input berhenti |
| App cold start | < 2 detik ke home screen |

### 3.2 Memory

| Komponen | Limit |
|----------|-------|
| sqlite-vec vector DB | <= 30 MB |
| ONNX embedding session | <= 50 MB |
| LLM context window | <= target VRAM per model |
| Background processing (embedding) | Dispatcher.IO, max 2 threads |

### 3.3 Privacy & Security

- Zero internet setelah setup kecuali fallback web diaktifkan pengguna
- Zero telemetri / analytics
- Tidak ada API keys hardcoded
- Dokumen tidak meninggalkan device
- GDPR/DSGVO compliant by design

### 3.4 Offline-First

- Semua fitur utama berjalan tanpa internet setelah model terunduh
- Graceful degradation: UI informatif jika model belum tersedia

---

## 4. Technology Stack

### 4.1 Core Stack

| Layer | Teknologi |
|-------|-----------|
| Language | Kotlin 2.0+ |
| UI | Jetpack Compose + Material 3 Expressive |
| Architecture | Clean Architecture + MVVM |
| DI | Dagger Hilt |
| Async | Kotlin Coroutines + Flow |
| DB | Room + SQLite |
| State | StateFlow / SharedFlow |
| Camera | CameraX |
| Storage | SAF (Storage Access Framework) |
| Background | WorkManager |

### 4.2 AI / ML Stack

| Komponen | Teknologi | Detail |
|----------|-----------|--------|
| LLM Inference | llama.cpp via JNI | GGUF format, arm64-v8a + armeabi-v7a |
| Embedding | ONNX Runtime Mobile | all-MiniLM-L6-v2 INT8, 384-dim |
| Vector Store | sqlite-vec | 30 MB footprint, brute-force < 10 ms |
| OCR | Google ML Kit Text Recognition | Offline, Latin + CJK |
| Translation | Google ML Kit Translate | 50+ bahasa, offline pack |
| Document Parse | Apache PDFBox + Apache POI | PDF, DOCX |
| Image Capture | CameraX ImageCapture | CAPTURE_MODE_MINIMIZE_LATENCY |

---

## 5. Project Structure

```
app/src/main/java/com/chatbuddy/
├── data/
│   ├── local/
│   │   ├── database/          # Room DB, DAOs, VectorDB
│   │   └── repository/        # Repository implementations
│   ├── model/                 # Data models
│   └── download/              # ResumableDownloadManager, DownloadWorker
├── domain/
│   ├── repository/            # Repository interfaces
│   ├── usecase/               # ChunkDocument, EmbedDocument, RAGQuery, etc.
│   └── model/                 # Domain models (Persona, ChatMessage, etc.)
├── presentation/
│   ├── chat/                  # ChatScreen, ChatViewModel
│   ├── rag/                   # RAGDocumentScreen, DocumentPicker
│   ├── translate/             # TranslationScreen, RealtimeTranslationViewModel
│   ├── ocr/                   # OCRScreen, CameraScreen
│   ├── imagetranslate/        # ImageTranslationScreen
│   ├── settings/              # PersonaSettingsScreen, SettingsScreen
│   ├── download/              # DownloadScreen, ModelSelector
│   └── common/                # Shared composables, ModelGate
├── ai/
│   ├── embedding/             # OnDeviceEmbedder (ONNX)
│   ├── llm/                   # LocalLLMEngine (llama.cpp JNI)
│   ├── rag/                   # RagEngine, RagPipeline
│   ├── ocr/                   # OCREngine (ML Kit)
│   └── translation/           # TranslationEngine, ImageTranslationEngine
├── di/                        # Hilt modules
└── utils/                     # Extensions, formatters
```

---

## 6. User Stories

- **US-01:** Sebagai mahasiswa, saya ingin upload skripsi PDF dan bertanya tentang isinya tanpa harus membacanya keseluruhan.
- **US-02:** Sebagai traveler, saya ingin mengarahkan kamera ke menu restoran berbahasa Jepang dan langsung mendapat terjemahan Indonesia.
- **US-03:** Sebagai peneliti, saya ingin men-scan dokumen kertas dan mengekstrak teksnya untuk diedit.
- **US-04:** Sebagai pengguna privasi, saya ingin chatbot yang tidak pernah mengirim pertanyaan saya ke server manapun.
- **US-05:** Sebagai pengguna dengan koneksi lambat, saya ingin download model besar bisa di-pause dan dilanjut kapan saja.
- **US-06:** Sebagai power user, saya ingin membuat persona AI "Dosen Pembimbing" dengan system prompt dan karakter yang spesifik.

---

## 7. Build Phases

| Phase | Scope | Status |
|-------|-------|--------|
| Phase 1 | Project setup, dependencies, SAF storage, download manager | Implemented; model download/persistence device gate pending |
| Phase 2 | RAG pipeline: chunking, embedding, vector search | Chunking/embedding and sqlite-vec probe plus bounded Room exact fallback implemented; artifact/device retrieval pending |
| Phase 3 | LLM inference (llama.cpp JNI) + streaming chat UI | JNI build and streaming boundary implemented; Gemma device inference pending |
| Phase 4 | Translation engine + realtime translation screen | ML Kit managed provider and one-device voice pipeline implemented; provider-pack/audio device acceptance pending |
| Phase 5 | OCR engine + camera integration | ML Kit/CameraX implementation present; camera/gallery model/session acceptance pending |
| Phase 6 | Image translation pipeline (OCR + Translate overlay) | OCR-to-translation handoff and bounding-box overlay implemented; device acceptance pending |
| Phase 7 | Persona settings UI | Implemented with Room persistence and validation |
| Phase 8 | Polish, testing, ProGuard, release | Debug/unit/lint/native gates pass; final device/model and production-signing acceptance pending |

---

## 8. Architecture Decisions

### Mengapa SAF untuk Storage?
URI SAF yang dipersisten via `takePersistableUriPermission` tetap valid setelah uninstall. Model LLM multi-GB tidak perlu diunduh ulang.

### Mengapa sqlite-vec?
Zero dependency tambahan, 30 MB memory footprint, brute-force search < 10 ms untuk corpus < 50.000 chunk. Lebih ringan dari FAISS untuk on-device.

### Mengapa ONNX Runtime?
`all-MiniLM-L6-v2` INT8 ONNX ~23 MB. Limit 2 thread (setIntraOpNumThreads) mencegah thermal throttling pada device mid-range.

### Mengapa llama.cpp via JNI?
GGUF format universal, mendukung quantisasi Q4_K_M hingga Q8_0. JNI bridge memungkinkan streaming token langsung ke Kotlin Flow.

### Mengapa WorkManager untuk download?
Persisten melewati proses kill, restart device, dan kehilangan koneksi. Exponential backoff built-in. Range header + checkpoint memungkinkan resume dari byte terakhir.

---

## 9. Deployment Checklist

- [ ] ProGuard rules untuk ONNX Runtime, ML Kit, Apache PDFBox
- [ ] NDK ABI filters: `arm64-v8a`, `armeabi-v7a`
- [ ] Manifest: camera permission, SAF access, internet (download dan fallback web opt-in saja)
- [ ] ModelGate composable: intercept navigasi jika model belum ada
- [ ] Atomic file write: `.tmp` -> rename setelah download selesai
- [ ] Test: resume download setelah kill proses
- [ ] Test: dokumen 200 MB tidak OOM
- [ ] Test: uninstall + reinstall, model masih ada di SAF folder
- [ ] R8 minification dengan keep rules yang tepat
- [ ] Release signing dengan production keystore

---

## 10. Implementation amendment — 2026-08-19

This amendment supersedes only the model/runtime assumptions that were not
verifiable in the original draft. The product remains local-first and must
fail closed when a runtime dependency is unavailable.

- The mobile LLM baseline is Gemma 4 E2B IT. The verified GGUF entry is the
  official `gemma-4-E2B-it-GGUF` `Q4_0` artifact pinned in
  `app/src/main/assets/model-manifest.json`. The inaccessible 1B URL is not
  used.
- Embedding uses the pinned INT8 ARM64 MiniLM ONNX artifact and its matching
  WordPiece vocabulary. The 384-dimensional output is mean pooled and
  normalized before vector insertion.
- Whisper metadata is pinned, but voice transcription is unavailable until a
  real whisper.cpp JNI build is integrated and tested on Android. No transcript
  is fabricated while it is unavailable.
- ML Kit translation and OCR remain Play Services managed capabilities. They
  are not represented as SAF-persistent files after uninstall.
- The current implementation provides real bounded TXT/PDF/DOCX streaming ingestion,
  chunking, SAF download/resume/checksum/atomic rename, persona persistence,
  Compose ModelGate, CameraX OCR, ML Kit translation, ONNX embedding, a
  llama.cpp JNI build, and a pinned whisper.cpp JNI runtime. sqlite-vec
  registration is attempted first; Android builds without the extension use a
  Room BLOB exact-cosine compatibility backend that stores and searches real
  embeddings. This compatibility path is not fabricated data and remains
  replaceable by the native sqlite-vec extension.
- Acceptance status is evidence-based: Gradle, lint, native ABI, device
  install/launch, model download/resume, offline RAG, camera/gallery OCR,
  translation packs, voice, and uninstall persistence are separate gates.

### 10.1 Live translation implementation amendment — 2026-08-24

F-02 now includes a real one-device live conversation mode. The pipeline is
`AudioRecord (16 kHz) -> frame VAD/sentence boundary -> pinned whisper.cpp
Whisper Base Q5_1 -> ML Kit offline translation -> offline Android TTS`. SAF
remains the durable source of the Whisper model; the verified file is
materialized into `cacheDir` only for native file-path loading and is rebuilt
from SAF when the OS clears the cache.

- Partial Whisper snapshots update the live transcript while the user is
  speaking; final utterances trigger translation and optional TTS.
- The UI exposes real `Listening`, `Transcribing`, `Translating`, `Speaking`,
  and `Error` states with retry/model setup paths.
- The Android v1 scope is one handset with turn-taking and automatic sentence
  breaks. Bluetooth multi-device simultaneous interpretation remains outside
  the PRD contract and is not represented as implemented.

### 10.2 Local-first AI companion and web fallback amendment — 2026-08-25

F-01 supports an explicit local-first companion flow. When document RAG is
enabled, ChatBuddy embeds the question and searches the local vector store
first. Relevant local evidence is the only context sent to the local LLM. If
no evidence passes the relevance threshold and the user has enabled **Search
web if local sources miss**, the app sends only the question over HTTPS to the
configured `WebSearchRepository`. The initial keyless provider is the
Wikipedia MediaWiki Action API; providers that require a secret stay behind a
repository boundary and must not put a key in the APK or repository.

- Web results are untrusted reference data, never instructions. The local LLM
  is prompted to ignore commands contained in documents or web pages and to
  withhold an answer when the returned evidence is insufficient.
- Every web-backed answer displays the provider, excerpt, and HTTPS URL. A
  source card opens the URL using the Android URI handler; local document
  sources are shown separately from web sources.
- If the local vector runtime is unavailable, the app reports the local RAG
  failure instead of silently disguising it as a web answer. If web search is
  unavailable or returns no grounded source, the app withholds the answer.
- Disabling the web toggle guarantees no web request during chat. The local
  LLM is still required for both local and web-grounded answers.

The boundary follows the RAG provenance principle described in the original
RAG paper and Android's repository/network guidance:

- https://mlanthology.org/neurips/2020/lewis2020neurips-retrievalaugmented/
- https://developer.android.com/develop/connectivity/network-ops/connecting
- https://www.mediawiki.org/wiki/API:Search/en

### 10.3 Audit and hardening amendment — 2026-08-27

- Model downloads use one unique WorkManager job per artifact, a SAF `.tmp`
  file, the provider's durable byte length, HTTP Range validation, early-EOF
  retry, manifest size/SHA-256 verification, and atomic rename. A `200`
  response during resume safely restarts from byte zero; it is never appended
  to a partial file.
- `cacheDir` is an optional verified runtime copy keyed by artifact revision,
  size, and SHA-256. A cache miss, insufficient space, or cache I/O failure
  falls back to the verified SAF source; cache state is not confused with
  durable model storage.
- The vector backend reports the backend actually serving queries:
  `SQLITE_VEC`, `ROOM_EXACT_DEGRADED`, or `UNAVAILABLE`. Room embedding BLOBs
  remain the compatibility source and are reconciled into sqlite-vec after a
  process interruption.
- OCR camera analysis owns CameraX lifecycle and uses `KEEP_ONLY_LATEST`.
  EXIF orientation is applied to both source dimensions and preview pixels so
  bounding boxes and translated blocks use the same coordinate space.
- The keyless web boundary is the official Wikipedia MediaWiki API. It is
  opt-in, HTTPS-only, bounded, redirect-disabled, provenance-bound, and does
  not replace the local LLM. Unverified runtime/device gates remain pending;
  no result is fabricated to satisfy them.

### 10.4 Built-in AI companion persona amendment — 2026-08-27

F-05 now ships four usable templates: `Sunny Companion`, `Study Buddy`,
`Practical Guide`, and `Translation Helper`. `Sunny Companion` is the
first-run recommendation: warm and cheerful, but still professional and
grounded. The template action persists and activates the persona in one
transaction, so the user can start without authoring a system prompt.

- Every bundled template has a structured mission, grounding, probing, and
  response-style prompt.
- Custom personas receive the same global grounding and probing policy at the
  local LLM boundary. The assistant asks at most one focused clarification
  only when a missing detail materially changes the answer; low-risk ambiguity
  becomes an explicit assumption and continuation.
- Invalid activation/deletion reports an actionable error without silently
  clearing the current active persona. Success and error feedback are separate
  UI event states.
- Prompt behavior is informed by Google's persona/constraint/system-instruction
  guidance and Microsoft's targeted intent-disambiguation guidance:
  - https://cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/prompt-design-strategies
  - https://cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/system-instruction-introduction
  - https://learn.microsoft.com/en-us/microsoft-copilot-studio/guidance/cux-disambiguate-intent
