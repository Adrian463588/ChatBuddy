# ChatBuddy requirements traceability

Traceability ini menghubungkan PRD/AGENTS dengan implementasi nyata dan evidence. `Implemented` berarti jalur kode ada; tidak berarti semua runtime dependency sudah terverifikasi pada device.

| ID | Requirement | Implementation | Status/evidence |
| --- | --- | --- | --- |
| F-01 | Chat local RAG dari folder SAF dengan referensi dokumen | `DocumentRepositoryImpl`, `ChunkDocumentUseCase`, `OnDeviceEmbeddingRepository`, `SqliteVecVectorStoreRepository`, `LocalRagChatRepository` | Local evidence, source binding, abstain, sqlite-vec attempt, and Room exact-cosine compatibility path implemented; real model/device retrieval remains a separate gate |
| F-01-web | AI companion web fallback ketika local evidence miss | `WebSearchRepository`, `MediaWikiWebSearchRepository`, `LocalRagChatRepository`, `ChatViewModel`, bento source cards | Explicit opt-in, HTTPS-only Wikipedia search, untrusted-content prompt boundary, URL provenance, and answer withholding tests implemented |
| F-02 | Realtime text translation | `TranslationViewModel`, `MlKitTranslationRepository`, 300 ms debounce, `AndroidVoiceRepository` | Implemented against ML Kit managed packs with one-device Whisper/audio/TTS pipeline; provider-pack/audio device gate pending |
| F-03 | OCR dokumen, image, kamera | `MlKitOcrRepository`, `CameraOcrAnalyzer`, `CameraPreview`, `OcrViewModel` | Bundled ML Kit recognizers, CameraX capture, gallery SAF, EXIF-safe boxes, and inline errors implemented; camera/gallery device gate pending |
| F-04 | Persona dengan system prompt | `PersonaEntity`, `PersonaRepositoryImpl`, `ValidatePersonaUseCase`, `PersonaViewModel`, `PersonaTemplate`, `AssistantBehaviorPolicy` | Built-in Sunny/Study/Practical/Translation templates, bounded probing, custom prompt policy, Room persistence, safe activation, and unit/instrumentation coverage implemented |
| F-05 | Terjemahan image/foto | `ImageTranslationRepositoryImpl`, `TranslatedBlockOverlay`, `OcrImagePreview` | OCR-to-translation blocks and translated bounding-box overlay implemented; device acceptance pending |
| F-06 | Chunking/embedding file sampai 200 MB | SAF metadata limit, bounded TXT/PDF/DOCX token reader, lazy chunk sequence, 512/50 configuration | Implemented with page/XML streaming and a 200 MB read guard |
| F-07 | Local model, resumable download, uninstall persistence | `model-manifest.json`, `SafModelStore`, `ResumableDownloadManager`, `DownloadWorker`, `ModelRuntimeCache`, `LlamaJniEngine` | SAF/Range/checksum/cache/native build implemented; real 2.84 GB device download and uninstall/reinstall evidence pending |

## Cross-cutting controls

- All fallible repository operations return `AppResult`.
- Screen state uses `StateFlow`; one-shot UI messages use `SharedFlow`.
- IO is isolated to `Dispatchers.IO`; chunking/embedding work is not performed on the main thread.
- Missing runtime dependencies stop with an actionable unavailable state. No fabricated model response is emitted.
- Root `docs/**` is ignored and must not be staged; only product source, specifications, ADR, CI, and evidence are eligible.
