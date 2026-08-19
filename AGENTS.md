# AGENTS.md — ChatBuddy AI Agent Instructions

> Entry-point file. AI agent HARUS membaca file ini terlebih dahulu sebelum melakukan task apapun.  
> File ini merutekan agent ke dokumen yang tepat sesuai konteks task.

---

## Routing: Baca Dulu Sebelum Action

| Task yang diminta | Baca dulu |
|-------------------|-----------|
| UI / layout / design / color / typography | `DESIGN.md` |
| Fitur baru / requirements / scope | `PRD.md` |
| Arsitektur / kode / implementasi | Section "Architecture Rules" di bawah |
| Testing / debugging | Section "Testing Rules" di bawah |
| File baru / struktur proyek | Section "Project Structure Rules" di bawah |

---

## Project Context

**Nama:** ChatBuddy  
**Platform:** Android (minSdk 26, targetSdk 35)  
**Language:** Kotlin 2.0+  
**UI:** Jetpack Compose + Material 3 Expressive  
**Package:** `com.chatbuddy`

**Karakteristik kritis:**
- 100% offline setelah setup — TIDAK boleh ada network call untuk inferensi AI
- Storage via SAF (Storage Access Framework) — model AI dan dokumen harus bertahan setelah uninstall
- Semua AI processing: on-device (llama.cpp JNI, ONNX Runtime, ML Kit)

---

## Architecture Rules

### Clean Architecture (WAJIB diikuti)

```
UI (Compose + ViewModel)
    |
    v
Domain (UseCases — pure Kotlin, no Android deps)
    |
    v
Data (Repository Impl, Room, ONNX, llama.cpp JNI)
```

**Aturan strict:**
- ViewModel TIDAK boleh import `android.*` kecuali untuk lifecycle
- UseCase TIDAK boleh import Room entity langsung — gunakan domain model
- Repository interface ada di `domain/`, implementasi di `data/`
- Dependency injection HANYA via Dagger Hilt `@Inject constructor`

### State Management

```kotlin
// Selalu gunakan StateFlow untuk UI state
private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
val state: StateFlow<ScreenState> = _state.asStateFlow()

// SharedFlow untuk one-shot events (error, navigation)
private val _events = MutableSharedFlow<UiEvent>()
val events: SharedFlow<UiEvent> = _events.asSharedFlow()
```

### Coroutines Rules

- IO-bound work (file, DB, model): `Dispatchers.IO`
- CPU-bound (embedding, chunking): `Dispatchers.Default`
- UI update: `Dispatchers.Main`
- ONNX embedding: max 2 threads (`setIntraOpNumThreads(2)`) — thermal throttling prevention
- LLM streaming: collect via `Flow<String>`, emit token-by-token ke UI

### Defensive Programming (WAJIB)

```kotlin
// 1. ModelGate — jangan izinkan navigasi ke fitur AI jika model belum ada
@Composable
fun ModelGate(isModelReady: Boolean, content: @Composable () -> Unit) {
    if (!isModelReady) {
        DownloadPromptScreen()
    } else {
        content()
    }
}

// 2. Atomic file write — WAJIB untuk semua download
fun downloadToSAF(uri: Uri, fileName: String) {
    val tmpUri = createFile(folderUri, "$fileName.tmp")
    // ... write to tmp ...
    renameFile(tmpUri, fileName) // hanya setelah selesai sempurna
}

// 3. Session lifecycle — tutup llama session dengan benar
llmSession.use { session ->
    session.generate(prompt).collect { token -> emit(token) }
} // session ditutup dan di-null di awaitClose
```

---

## AI / ML Integration Rules

### LLM (llama.cpp via JNI)

- Format: GGUF only
- ABI: arm64-v8a, armeabi-v7a
- Load model dari SAF URI menggunakan FileDescriptor
- Streaming: emit token ke `Flow<String>`, JANGAN buffer sampai selesai
- Error handling: jika model file tidak ada atau corrupt -> emit error state, jangan crash

### Embedding (ONNX Runtime)

- Model: `all-MiniLM-L6-v2` INT8 quantized (~23 MB, 384-dim output)
- Session options: `setIntraOpNumThreads(2)`, disable intra-op spinning
- Mean pooling wajib diterapkan ke output tensor
- Batch embedding: proses chunk satu per satu untuk control memory

### RAG Pipeline

```
1. Document ingestion:
   SAF URI -> Stream -> Parse (PDFBox/POI) -> Chunk(512/50) -> Embed -> sqlite-vec

2. Query:
   UserQuery -> Embed -> VectorSearch(K=5) -> BuildContext -> LLM.stream() -> UI
```

- Chunk size: 512 token, overlap: 50 token
- Top-K: 5 (configurable)
- Vector DB: sqlite-vec (TIDAK boleh diganti FAISS atau library lain tanpa alasan)

### Download Manager

- HTTP Range header: `Range: bytes=$downloadedBytes-`
- Checkpoint: simpan byte offset ke SharedPreferences setelah setiap chunk
- WorkManager: `CoroutineWorker`, return `Result.retry()` saat jaringan putus
- Progress: emit ke `StateFlow<DownloadProgress>` dari worker
- SAF write mode: `"rwa"` (read-write-append) untuk resume

---

## Project Structure Rules

```
app/src/main/java/com/chatbuddy/
├── data/
│   ├── local/database/     # Room: AppDatabase, DAO, Entity
│   ├── local/repository/   # Impl dari domain/repository interfaces
│   ├── model/              # Data layer models (Room entities)
│   └── download/           # ResumableDownloadManager, DownloadWorker
├── domain/
│   ├── repository/         # Interface (pure Kotlin)
│   ├── usecase/            # 1 class = 1 use case, operator fun invoke()
│   └── model/              # Domain models (no Room annotations)
├── presentation/
│   ├── chat/               # ChatScreen.kt, ChatViewModel.kt
│   ├── rag/                # RAGScreen.kt, RAGViewModel.kt
│   ├── translate/          # TranslationScreen.kt, TranslationViewModel.kt
│   ├── ocr/                # OCRScreen.kt, CameraScreen.kt, OCRViewModel.kt
│   ├── imagetranslate/     # ImageTranslationScreen.kt
│   ├── settings/           # PersonaScreen.kt, SettingsScreen.kt
│   ├── download/           # DownloadScreen.kt, DownloadViewModel.kt
│   └── common/             # ModelGate.kt, reusable composables
├── ai/
│   ├── embedding/          # OnDeviceEmbedder.kt
│   ├── llm/                # LocalLLMEngine.kt (JNI wrapper)
│   ├── rag/                # RagEngine.kt
│   ├── ocr/                # OCREngine.kt
│   └── translation/        # TranslationEngine.kt, ImageTranslationEngine.kt
├── di/                     # Hilt modules
└── utils/                  # Extensions, formatters, constants
```

**File naming conventions:**
- Screen: `FeatureScreen.kt`
- ViewModel: `FeatureViewModel.kt`
- UseCase: `VerbNounUseCase.kt` (e.g., `ChunkDocumentUseCase.kt`)
- Repository: `NounRepository.kt` (interface), `NounRepositoryImpl.kt` (impl)

---

## Testing Rules

### Unit Tests (domain layer)

```kotlin
// UseCase tests: pure JUnit, no Android deps
@Test
fun `ChunkDocumentUseCase splits at correct boundaries`() {
    val useCase = ChunkDocumentUseCase()
    val result = useCase("a".repeat(600), chunkSize = 512, overlap = 50)
    // chunk 1: 0-512, chunk 2: 462-600 (overlap 50)
    assertEquals(2, result.size)
    assertEquals(50, result[1].startIndex - result[0].startIndex + 512 - 512)
}
```

### Integration Tests (data layer)

```kotlin
// Download resume test
@Test
fun `resume download from checkpoint after network cut`() = runTest {
    // Setup: partial file in SAF, checkpoint = 1024 bytes
    // Action: call downloadModel() with existing partial file
    // Assert: HTTP Range header = "bytes=1024-"
}
```

### UI Tests (Compose)

```kotlin
// ModelGate test
@Test
fun `ModelGate shows download prompt when model not ready`() {
    composeTestRule.setContent {
        ModelGate(isModelReady = false) { ChatScreen() }
    }
    composeTestRule.onNodeWithText("Download Model").assertIsDisplayed()
}
```

---

## Code Quality Rules

### Anti-Pattern Checklist (JANGAN lakukan ini)

- [ ] JANGAN load file 200 MB ke memory sepenuhnya — gunakan InputStream streaming
- [ ] JANGAN block main thread dengan IO atau inferensi
- [ ] JANGAN hardcode URL model atau API key di source code
- [ ] JANGAN gunakan `GlobalScope` — selalu gunakan `viewModelScope` atau `lifecycleScope`
- [ ] JANGAN inisialisasi ONNX session lebih dari sekali — gunakan singleton via Hilt
- [ ] JANGAN izinkan double-close pada LLM session — wrap dengan `use {}`
- [ ] JANGAN tulis file download langsung ke nama final — tulis ke `.tmp` dulu
- [ ] JANGAN navigasi ke fitur AI tanpa cek model ready (ModelGate)

### SOLID Compliance

```kotlin
// S — Single Responsibility
class ChunkDocumentUseCase { /* hanya chunking */ }
class OnDeviceEmbedder { /* hanya embedding */ }

// O — Open/Closed: extend via interface
interface TranslationProvider {
    suspend fun translate(text: String): String
}

// D — Dependency Inversion: inject interface, bukan impl
class ImageTranslationEngine @Inject constructor(
    private val ocr: OCRProvider,        // interface
    private val translator: TranslationProvider  // interface
)
```

### Result Pattern (wajib untuk semua operasi fallible)

```kotlin
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : AppResult<Nothing>()
    object Loading : AppResult<Nothing>()
}
```

---

## Dependencies Quick Reference

```kotlin
// Core
"androidx.compose:compose-bom:2024.11.00"
"androidx.navigation:navigation-compose:2.8.4"
"com.google.dagger:hilt-android:2.52"
"androidx.room:room-runtime:2.6.1"
"androidx.work:work-runtime-ktx:2.9.1"

// Camera
"androidx.camera:camera-camera2:1.4.0"
"androidx.camera:camera-lifecycle:1.4.0"
"androidx.camera:camera-view:1.4.0"

// ML Kit
"com.google.mlkit:text-recognition:16.0.0"
"com.google.mlkit:translate:17.0.3"

// AI
"com.microsoft.onnxruntime:onnxruntime-android:1.19.2"
// llama.cpp: via JNI, prebuilt .so in jniLibs/

// Document
"org.apache.pdfbox:pdfbox-android:2.0.27.0"
"org.apache.poi:poi:5.2.5"
```

---

## Pre-Task Checklist

Sebelum menulis kode, agent HARUS menjawab:

1. Screen / fitur ini ada di layer apa? (presentation / domain / data / ai)
2. Ada interface yang perlu dibuat dulu sebelum implementasi?
3. Apakah ada model yang harus ready sebelum fitur ini bisa digunakan? (ModelGate needed?)
4. Apakah ada IO blocking yang perlu dipindah ke `Dispatchers.IO`?
5. Apakah file write melibatkan download? -> Gunakan atomic write (.tmp -> rename)
6. Apakah ini operasi fallible? -> Wrap dengan `AppResult<T>`
7. Baca `DESIGN.md` jika task ini melibatkan UI baru
