# Architecture decision records

## ADR-001 — SAF is the owner of persistent model artifacts

The user selects a writable tree URI. ChatBuddy persists the grant and creates `ChatBuddyModels`. Downloads write to an artifact-specific `.tmp` using append mode, checkpoint the real byte length, verify size/SHA-256, then rename atomically. App-private storage is never used as the canonical model location, so uninstall does not intentionally delete the model. A device uninstall/reinstall session remains required evidence.

## ADR-002 — Manifest metadata is mandatory

Every downloadable artifact has a pinned URL/revision, byte size, SHA-256, license, ABI set, and `ModelStorageKind`. The broken Gemma 1B URL is excluded. The current mobile baseline is the official Gemma 4 E2B IT `Q4_0` artifact because it is the smallest verified Gemma 4 GGUF in the selected source.

## ADR-003 — sqlite-vec is a hard boundary, not a silent fallback

The repository targets the sqlite-vec virtual table contract and 384-dimensional vectors. Android SQLite does not automatically expose the extension, so the repository returns unavailable until a licensed, ABI-tested extension is packaged and registered. A cosine scan or fabricated retrieval result would violate the specification and is not substituted.

## ADR-004 — llama.cpp JNI owns one serialized session

The JNI wrapper loads GGUF through a duplicated SAF file descriptor, uses the model chat template, and exposes token-at-a-time generation. Kotlin serializes the session with a `Mutex`; native model/context/sampler resources are freed before replacement. The dependency is fetched at build time from a pinned commit; inference has no network path.

## ADR-005 — ML Kit is explicitly managed by Play Services

Translation and OCR use real ML Kit APIs. Translation model availability and provenance are surfaced as managed state. The app does not mislabel Play Services files as SAF-persistent artifacts after uninstall. An OPUS-MT ONNX fallback is a separate future provider and is not represented as ready today.

## ADR-006 — voice is turn-taking until Whisper JNI is proven

Audio capture uses `AudioRecord` at 16 kHz and Android TTS only when offline capability is reported. Whisper artifact metadata and the repository contract exist, but no transcript is emitted until whisper.cpp JNI is built and device-tested. Bluetooth multi-device conversation is outside v1.

## ADR-007 — reference projects are patterns only

The supplied `docs/**` directories are not copied into product source and are ignored by Git. Product decisions are reconciled against `AGENTS.md`, `PRD.md`, and `DESIGN.md` and recorded here.

## ADR-008 — translation language selection uses dropdown fields

The translation screen uses two Material 3 exposed dropdown fields labelled `From` and `To`, with a separate icon button for swapping the direction. Language options come from the real `TranslationUiState.languages` list; an empty provider list disables the selectors instead of inventing language options. This removes the pill/chip-like language controls while keeping the interaction recognizable as a translation flow.

This follows Android's exposed-dropdown guidance, which pairs a read-only text field with a menu, and the Android Google Translate flow, which presents `From` and `To` language selectors plus a reverse action:

- https://developer.android.com/reference/kotlin/androidx/compose/material3/ExposedDropdownMenuBox.composable
- https://support.google.com/translate/answer/6142478?co=GENIE.Platform%3DAndroid&hl=en
