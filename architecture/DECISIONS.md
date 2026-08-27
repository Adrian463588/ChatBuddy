# Architecture decision records

## ADR-001 — SAF is the owner of persistent model artifacts

The user selects a writable tree URI. ChatBuddy persists the grant and creates `ChatBuddyModels`. Downloads write to an artifact-specific `.tmp` using append mode, checkpoint the real byte length, verify size/SHA-256, then rename atomically. App-private storage is never used as the canonical model location, so uninstall does not intentionally delete the model. A device uninstall/reinstall session remains required evidence.

## ADR-002 — Manifest metadata is mandatory

Every downloadable artifact has a pinned URL/revision, byte size, SHA-256, license, ABI set, and `ModelStorageKind`. The broken Gemma 1B URL is excluded. The current mobile baseline is the official Gemma 4 E2B IT `Q4_0` artifact because it is the smallest verified Gemma 4 GGUF in the selected source.

## ADR-003 — sqlite-vec is preferred with a real embedding compatibility path

The repository targets the sqlite-vec virtual table contract and 384-dimensional vectors. Android SQLite does not automatically expose the extension, so the repository attempts sqlite-vec first and stores the same real embeddings in a Room BLOB column when the extension is unavailable. The compatibility path ranks those vectors with exact cosine similarity and loads the winning chunk records; it never invents evidence. The app builds the pinned sqlite-vec extension for Android and reports `ROOM_EXACT_DEGRADED` when probing or querying that extension fails. Raw vector SQL uses Room 2.7's `useWriterConnection`/`useReaderConnection` APIs, which are compatible with `BundledSQLiteDriver`; it does not assume a `SupportSQLiteOpenHelper` exists when a driver is configured. Missing external rows are reconciled from the Room BLOB owner after a process interruption.

## ADR-004 — llama.cpp JNI owns one serialized session

The JNI wrapper loads GGUF through a duplicated SAF file descriptor, uses the model chat template, and exposes token-at-a-time generation. Kotlin serializes the session with a `Mutex`; native model/context/sampler resources are freed before replacement. The dependency is fetched at build time from a pinned commit; inference has no network path.

## ADR-005 — ML Kit is explicitly managed by Play Services

Translation and OCR use real ML Kit APIs. Translation model availability and provenance are surfaced as managed state. The app does not mislabel Play Services files as SAF-persistent artifacts after uninstall. An OPUS-MT ONNX fallback remains a separate provider contract and is `UNAVAILABLE` until a licensed artifact, tokenizer, checksum, and runtime are verified; no fallback output is fabricated.

## ADR-006 — voice is turn-taking until Whisper JNI is proven

Audio capture uses `AudioRecord` at 16 kHz and Android TTS only when offline capability is reported. Whisper.cpp JNI is built from a pinned revision, but no transcript is emitted until its SAF artifact and device session are verified. Bluetooth multi-device conversation is outside v1.

## ADR-007 — reference projects are patterns only

The supplied `docs/**` directories are not copied into product source and are ignored by Git. Product decisions are reconciled against `AGENTS.md`, `PRD.md`, and `DESIGN.md` and recorded here.

## ADR-008 — translation language selection uses dropdown fields

The translation screen uses two Material 3 exposed dropdown fields labelled `From` and `To`, with editable autocomplete input and a separate icon button for swapping the direction. Typing filters the real `TranslationUiState.languages` list by display name or language tag; selecting an item commits its tag, while an empty provider list disables the selectors instead of inventing language options. This removes the pill/chip-like language controls while keeping the interaction recognizable as a translation flow.

This follows Android's exposed-dropdown guidance for editable autocomplete fields and the Android Google Translate flow, which presents `From` and `To` language selectors plus a reverse action:

- https://developer.android.com/reference/kotlin/androidx/compose/material3/ExposedDropdownMenuBox.composable
- https://support.google.com/translate/answer/6142478?co=GENIE.Platform%3DAndroid&hl=en

## ADR-009 — model download state is actionable in the feature gate

Starting a model download immediately moves the artifact to `Queued` after WorkManager accepts the unique work. `ModelGate` then renders the real lifecycle inline: queued, downloading with bytes and percentage, paused, verifying, and error. Pause, resume, and retry are wired to the repository; the feature no longer emits a success toast that leaves the user without context. Transfer progress continues to come from the SAF download worker and checksum verification remains the completion boundary.

This follows Android's WorkManager guidance for observing intermediate progress and Compose guidance for determinate and indeterminate progress indicators:

- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe
- https://developer.android.com/develop/ui/compose/components/progress?authuser=19

## ADR-010 — SAF resumable writes use the Android-supported append mode

`ContentResolver.openOutputStream` accepts `wa` for write-append; `rwa` is not a valid Android mode. The download manager reads the real `.tmp` length, requests `bytes=<offset>-`, appends with `wa`, checkpoints after writes, verifies size and SHA-256, and only then renames the file to its final name. This prevents a pre-request SAF failure while preserving interrupted-download resume.

Reference: https://developer.android.com/reference/android/content/ContentResolver

## ADR-011 — large model transfers are foreground work and failures stay actionable

Artifacts over 100 MB use WorkManager foreground execution with a `dataSync` notification so the 2.84 GB Gemma transfer is not silently limited to the ordinary worker window. Smaller bundles keep the normal worker path. Network `IOException` remains retryable with exponential backoff and the SAF checkpoint is preserved; SAF write/read failures, HTTP failures, range mismatches, checksum failures, and atomic-rename failures become an inline error with a real Retry action. Retrying replaces stale unique work so the button is effective.

References:

- https://developer.android.com/reference/androidx/work/CoroutineWorker
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work

## ADR-012 — web fallback is explicit, local-first, and provenance-bound

ChatBuddy searches indexed local documents first. Only when RAG returns no
evidence above the relevance threshold and the user has enabled the web toggle
does the app send the question over HTTPS to `WebSearchRepository`. The initial
keyless provider is the Wikipedia MediaWiki Action API. Documents, embeddings,
persona instructions, and the local system prompt never cross this boundary.
Web results are labelled untrusted reference data in the LLM prompt and are
shown as source cards with provider, excerpt, and HTTPS URL. A failed web
request or empty result withholds the answer instead of fabricating one.

References:

- https://mlanthology.org/neurips/2020/lewis2020neurips-retrievalaugmented/
- https://developer.android.com/develop/connectivity/network-ops/connecting
- https://www.mediawiki.org/wiki/API:Search/en

## ADR-013 — bundled personas use structured, bounded probing

ChatBuddy ships small, deterministic persona templates so a first-time user can
start with `Sunny Companion` without writing a system prompt. Templates are
configuration only and are copied to Room after an explicit user action. The
default persona is warm and cheerful, but its prompt keeps the behavior grounded:
it asks at most one focused clarification when a missing detail materially changes
the answer, states a low-risk assumption when possible, and never invents facts,
citations, actions, or hidden reasoning. Users can still edit, duplicate, activate,
or delete the persisted persona.

The prompt is structured into mission, grounding, probing, and response style so
the local model receives an explicit objective, constraints, context boundary, and
output style. This follows Google's prompt-design guidance on persona, constraints,
context, and structured prompts, and Microsoft's guidance to use targeted
disambiguation questions for ambiguous intent:

- https://cloud.google.com/vertex-ai/generative-ai/docs/learn/prompts/prompt-design-strategies
- https://learn.microsoft.com/en-us/microsoft-copilot-studio/guidance/cux-disambiguate-intent
