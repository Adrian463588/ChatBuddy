# DESIGN.md — ChatBuddy Design System

> Dibaca oleh AI agent setiap kali task melibatkan UI.  
> Dokumen ini adalah source of truth untuk semua keputusan visual.  
> TIDAK boleh ada keputusan desain di luar dokumen ini.

---

## 1. Design Identity

**Nama:** ChatBuddy  
**Kepribadian visual:** Intelligent · Calm · Private · Local  
**Mood:** Dark-first, clean, focused — seperti IDE premium, bukan consumer app  
**Anti-persona:** BUKAN aplikasi warna-warni, BUKAN gradien AI klise, BUKAN "fun chatbot" yang childish

**Satu kalimat:** ChatBuddy terasa seperti alat profesional yang kuat, bukan mainan.

---

## 2. Design Foundation: Material 3 Expressive

Platform: **Android Material 3 Expressive** (Android 16+, dengan fallback M3 standard)

- Spring-based motion system (bukan linear/ease)
- Shape morphing pada interaksi
- 48 color roles (bukan 6 warna flat)
- Typography expressive: bukan sekadar size, tapi weight + tracking intentional

---

## 3. Color System

### 3.1 Brand Palette (OKLCH — bukan HSL)

```
Primary:       oklch(0.62 0.18 255)    -- Deep Indigo Blue (bukan #6366f1!)
               Hex approx: #3D6BE8
On-Primary:    oklch(0.98 0.01 255)    -- Near white

Secondary:     oklch(0.55 0.10 200)    -- Teal Slate
On-Secondary:  oklch(0.98 0.01 200)

Tertiary:      oklch(0.72 0.12 140)    -- Sage Green (untuk status sukses)

Error:         oklch(0.58 0.22 25)     -- Warm Red (bukan pure #FF0000)
```

### 3.2 Surface Hierarchy (Dark Mode — Default)

```
Background:      oklch(0.12 0.01 255)   -- Near-black with blue undertone
Surface:         oklch(0.16 0.01 255)   -- Elevated surface
Surface Variant: oklch(0.20 0.015 255)  -- Card / panel background
Outline:         oklch(0.35 0.02 255)   -- Subtle dividers
Outline Variant: oklch(0.28 0.015 255)  -- Even subtler
```

### 3.3 Light Mode Override

```
Background:      oklch(0.97 0.005 255)  -- Off-white with blue tint (BUKAN pure white)
Surface:         oklch(0.94 0.008 255)
Surface Variant: oklch(0.90 0.010 255)
```

### 3.4 Semantic Color Tokens

```
color-chat-user-bubble:    Primary container
color-chat-ai-bubble:      Surface Variant
color-rag-active:          Tertiary (sage green indicator)
color-download-progress:   Primary
color-download-error:      Error
color-persona-badge:       Secondary container
color-ocr-overlay:         oklch(0.85 0.22 55) -- Amber highlight for bounding box
color-translation-panel:   Surface with 0.6 alpha overlay
```

### Anti-Pattern Colors (DILARANG)

- `#6366f1` (Tailwind Indigo 500) — terlalu umum
- Purple-to-blue gradient sebagai background utama
- Pure black `#000000` sebagai background
- Pure white `#FFFFFF` sebagai background
- Warna aksen yang bersaing (jangan ada 3+ warna accent di 1 screen)

---

## 4. Typography System

### 4.1 Font Families

```
Display / Headline:   "DM Sans"          -- Geometric sans, distinctive but not quirky
Body / UI:            "Inter Variable"   -- HANYA untuk body text, BUKAN judul
Monospace (code):     "JetBrains Mono"   -- Untuk output LLM yang menampilkan code
```

> Alasan DM Sans: Memiliki character yang lebih kuat dari Inter, tapi tidak
> seeklektrik Space Grotesk. Cocok untuk AI tool yang ingin terasa professional.

### 4.2 Type Scale (Material 3 Expressive)

```
Display Large:    DM Sans, 57sp, weight 400, tracking -0.25sp
Display Medium:   DM Sans, 45sp, weight 400, tracking 0
Display Small:    DM Sans, 36sp, weight 400, tracking 0

Headline Large:   DM Sans, 32sp, weight 600, tracking 0
Headline Medium:  DM Sans, 28sp, weight 600, tracking 0
Headline Small:   DM Sans, 24sp, weight 600, tracking 0

Title Large:      DM Sans, 22sp, weight 500, tracking 0
Title Medium:     DM Sans, 16sp, weight 500, tracking 0.15sp
Title Small:      DM Sans, 14sp, weight 500, tracking 0.1sp

Body Large:       Inter Variable, 16sp, weight 400, tracking 0.5sp
Body Medium:      Inter Variable, 14sp, weight 400, tracking 0.25sp
Body Small:       Inter Variable, 12sp, weight 400, tracking 0.4sp

Label Large:      Inter Variable, 14sp, weight 500, tracking 0.1sp
Label Medium:     Inter Variable, 12sp, weight 500, tracking 0.5sp
Label Small:      Inter Variable, 11sp, weight 500, tracking 0.5sp
```

---

## 5. Spacing System (8-Point Grid)

**Unit dasar: 8 dp**

```
spacing-xs:   4 dp   -- tight internal padding
spacing-sm:   8 dp   -- standard internal padding
spacing-md:   16 dp  -- standard external spacing
spacing-lg:   24 dp  -- section spacing
spacing-xl:   32 dp  -- screen-level breathing room
spacing-2xl:  48 dp  -- major section separation
spacing-3xl:  64 dp  -- hero section padding
```

**Touch target minimum:** 48 dp x 48 dp (WCAG / M3 requirement)

**Content max width:** 600 dp (pada landscape/tablet, konten tidak stretch full)

---

## 6. Shape System

```
shape-xs:     4 dp   -- chips, tags kecil
shape-sm:     8 dp   -- buttons, input fields
shape-md:     12 dp  -- cards, panels
shape-lg:     16 dp  -- bottom sheets, dialogs
shape-xl:     24 dp  -- large modal surfaces
shape-full:   50%    -- FAB, avatar, circular buttons
```

**Aturan shape:**
- JANGAN setiap elemen corner radius sama (monotone)
- Cards: shape-md (12 dp)
- Chat bubbles: shape-lg kiri atas flat untuk AI, shape-lg kanan atas flat untuk user
- FAB: shape-full
- Bottom navigation: tidak ada radius (flush ke edge)

---

## 7. Motion System (Spring-Based)

### 7.1 Spring Specs

```kotlin
// Standard interaction (button tap, card expand)
val springStandard = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)

// Emphasis (modal appear, download complete)
val springEmphasis = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

// Utility (fade, subtle state change)
val tweenUtility = tween<Float>(
    durationMillis = 200,
    easing = FastOutSlowInEasing
)
```

### 7.2 Animation Rules

**Boleh:**
- Satu animasi yang well-timed per interaksi
- Typing indicator untuk streaming LLM (3 dot bounce)
- Progress bar fill animation (download)
- Screen transition: shared element atau slide
- Micro-animation: FAB scale on press

**DILARANG:**
- 5+ elemen fade-up bersamaan saat screen load
- Gradient orb yang berputar sebagai "AI loading" indicator
- Particle/sparkle effects
- Rotation animation yang terus menerus (spinner di mana-mana)
- Animasi yang tidak bisa di-skip / terlalu lambat (> 500ms untuk micro)

---

## 8. Screen-by-Screen Design Spec

### 8.1 Chat Screen

**Primary purpose:** Percakapan dengan AI  
**Primary action:** Send message  
**Secondary:** Toggle RAG mode, view sources

**Layout:**
```
[ TopAppBar: "ChatBuddy" | persona name chip | settings icon ]
[ Bento header: "AI companion" | Local-first / Local + web status ]
[ Bento knowledge card: local documents toggle | opt-in web fallback toggle ]
[ LazyColumn — message list ]
  [ AI bubble — left aligned, Surface Variant bg ]
  [ User bubble — right aligned, Primary container bg ]
  [ Source cards — local document and web provenance separated below AI bubble ]
  [ Typing indicator — 3 dot spring animation ]
[ Composer bento card ]
  [ TextField + Send icon button ]
```

**Warna bubble:**
- AI: Surface Variant background, Body Medium text, On-Surface-Variant color
- User: Primary Container background, On-Primary-Container text

**Streaming:** Tampilkan teks secara bertahap (append per token). Cursor blink di akhir.

**AI companion boundary:** Local documents are always preferred when RAG is
enabled. The web fallback is an explicit switch, not a hidden automatic
request. Its label states that only the question crosses the HTTPS boundary.
Web source cards use the public-source icon, show provider and excerpt, and
open the HTTPS URL when activated. No decorative chips, fake counts, or
generic toast-only actions are used.

**Bento and responsive rules:** The header, knowledge controls, and composer
are separate surface-container cards with one clear task each. They keep the
existing 8 dp grid and 48 dp touch targets, use a 600 dp content maximum, and
collapse to one column at compact width. At medium and expanded widths the
cards remain single-column inside the centered reading measure so the message
line length stays comfortable. Large font scale must wrap labels without
clipping or horizontal scrolling.

---

### 8.2 Translation Screen

**Primary purpose:** Terjemahan realtime  
**Primary action:** Ketik di panel sumber

**Layout:**
```
[ TopAppBar: "Translate" ]
[ Language Selector Row ]
  [ Source lang dropdown ] [ Swap button (animated rotate) ] [ Target lang dropdown ]
[ Panel Source — OutlinedTextField, weight(1f) ]
[ Divider dengan ikon panah ]
[ Panel Target — Card Surface Variant, weight(1f) ]
  [ Translated text ]
  [ Copy icon | Share icon ]
[ Bottom: Status chip (Offline / Model Ready / Downloading) ]
```

**Animasi:** Terjemahan muncul dengan fade-in (tidak langsung pop-in).

**Live conversation extension:**

```
[ Live conversation card ]
  [ Listening / Transcribing / Translating / Speaking ]
  [ Start live translation | Stop live translation ]
  [ Speak translation toggle ]
  [ You said — live transcript ]
  [ Translation — selected provider ]
```

- Live mode memakai satu perangkat Android dan microphone permission yang jelas.
- State wajib berasal dari pipeline nyata: `Starting`, `Listening`, `Transcribing`,
  `Translating`, `Speaking`, atau `Error`; tidak ada animasi seolah-olah sedang
  mendengar ketika capture belum berjalan.
- Satu scroll owner tetap dipakai pada screen. Live card tidak menambahkan chip
  atau badge dekoratif; status ditampilkan sebagai satu label yang dapat dibaca
  TalkBack.
- Teks partial dan final tetap selectable. Hasil final baru diputar TTS setelah
  sentence boundary terdeteksi, sehingga suara tidak terpotong setiap frame.

---

### 8.3 OCR Screen

**Primary purpose:** Ekstraksi teks dari gambar  
**Primary action:** Capture atau pick image

**Layout:**
```
[ TopAppBar: "OCR" | gallery picker icon ]
[ Camera preview / image preview -- fillMaxSize ]
  [ Bounding box overlay (amber color-ocr-overlay) ]
[ Bottom sheet (partial) ]
  [ Extracted text -- selectable ]
  [ Actions: Copy | Send to Chat | Send to Translate ]
[ FAB: Capture camera ]
```

---

### 8.4 Image Translation Screen

**Primary purpose:** Terjemahkan teks dalam gambar  
**Primary action:** Capture image

**Layout:**
```
[ Language selector row (compact) ]
[ Camera preview / Captured image ]
  [ OCR bounding box overlay ]
  [ Translation overlay cards di atas bounding box area ]
[ Bottom panel ]
  [ Full original text | Full translated text ]
  [ Copy translated ]
[ FAB: Capture / Retake ]
```

---

### 8.5 Download Screen

**Primary purpose:** Kelola dan unduh model AI  
**Primary action:** Tap download per bundle

**Layout:**
```
[ TopAppBar: "AI Models" ]
[ Storage info card: SAF folder path, available space ]
[ LazyColumn bundles ]
  [ BundleCard ]
    [ Icon | Name | Size | Description ]
    [ Status chip: Not Downloaded / Complete / Downloading ]
    [ Progress bar (visible only when downloading) ]
    [ Action: Download / Pause / Resume / Retry ]
```

**Download card states:**
- Not Downloaded: outlined card, Download button primary
- Downloading: progress bar, percentage text, Pause button
- Paused: muted progress bar, Resume button
- Complete: green checkmark icon, tertiary color accent
- Error: error color, Retry button

---

### 8.6 Persona Settings Screen

**Primary purpose:** Konfigurasi AI persona  
**Primary action:** Edit / Create persona

**Layout:**
```
[ TopAppBar: "AI Persona" ]
[ Active persona highlight card (Primary Container) ]
[ LazyColumn persona list ]
  [ PersonaCard ]
    [ Name | Description | system prompt preview (2 lines max) ]
    [ Select radio | Edit icon | Delete icon ]
[ FAB: Create new persona ]
[ Bottom sheet: Edit persona ]
  [ Name field ]
  [ System prompt textarea ]
  [ Temperature slider ]
  [ Top-P slider ]
  [ Max Tokens selector ]
  [ Save | Cancel ]
```

---

### 8.7 RAG Document Manager

**Primary purpose:** Kelola dokumen untuk RAG  
**Primary action:** Add document

**Layout:**
```
[ TopAppBar: "Documents" | folder info ]
[ Stats row: X docs | Y chunks | Z MB ]
[ LazyColumn document list ]
  [ DocumentCard ]
    [ File icon (by type) | Name | Size | Chunk count | Status ]
    [ Embedding progress (if processing) ]
    [ Delete icon ]
[ FAB: Add document (SAF picker) ]
```

---

## 9. Component Design Specs

### ChatBubble

```kotlin
// User bubble
Surface(
    shape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 4.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp
    ),
    color = MaterialTheme.colorScheme.primaryContainer,
    modifier = Modifier.padding(start = 48.dp) // Push right
)

// AI bubble
Surface(
    shape = RoundedCornerShape(
        topStart = 4.dp, topEnd = 16.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp
    ),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.padding(end = 48.dp) // Push left
)
```

### TypingIndicator (Streaming)

```kotlin
// 3-dot bounce with spring, staggered delay
Row {
    repeat(3) { index ->
        val offsetY by animateFloatAsState(
            targetValue = if (isAnimating) -8f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "dot_$index"
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .offset(y = offsetY.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        if (index < 2) Spacer(Modifier.width(4.dp))
    }
}
```

### DownloadProgressCard

```kotlin
Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
) {
    // ... content ...
    LinearProgressIndicator(
        progress = { progress },
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().height(4.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}
```

---

## 10. Anti-Slop Checklist (MANDATORY before shipping any UI)

AI agent WAJIB memverifikasi setiap screen sebelum commit:

**Typography:**
- [ ] Font bukan Inter/Roboto sebagai display font (harus DM Sans)
- [ ] Tidak ada > 3 ukuran font berbeda di satu screen
- [ ] Hierarki typografi jelas (1 headline, 1 body, 1 label)

**Color:**
- [ ] Tidak ada purple-to-blue gradient sebagai background
- [ ] Background bukan pure black atau pure white
- [ ] Tidak ada > 2 warna accent bersaing di satu screen
- [ ] Warna ocr overlay (amber) hanya muncul di OCR screens

**Layout:**
- [ ] Setiap screen punya 1 primary purpose
- [ ] Tidak ada "card inside card inside card"
- [ ] Tidak semua elemen padding sama (ada hierarchy spacing)
- [ ] Touch targets minimum 48 dp x 48 dp

**Motion:**
- [ ] Tidak ada > 1 animasi prominent per interaksi
- [ ] Tidak ada gradient orb / particle effect
- [ ] Typing indicator hanya muncul saat AI benar-benar streaming

**Density:**
- [ ] Tidak semua fitur visible sekaligus (progressive disclosure)
- [ ] Tidak ada excessive badges/chips
- [ ] Tidak ada icon yang tidak memiliki label atau tooltip

**Content:**
- [ ] Tidak ada placeholder text "Lorem ipsum"
- [ ] Label button adalah verb + noun (bukan hanya "OK" atau "Submit")
- [ ] Error message spesifik (bukan "Something went wrong")

---

## 11. Navigation Structure

```
NavHost (AppNavGraph)
├── Onboarding (first launch — SAF folder setup)
├── Home (Bottom Navigation)
│   ├── Chat (default tab)
│   ├── Translate
│   ├── OCR
│   └── Settings
├── RAGDocumentManager (dari Chat, floating button)
├── ImageTranslation (dari OCR atau Translate)
├── PersonaSettings (dari Settings)
└── DownloadManager (dari Settings atau ModelGate redirect)
```

**Bottom Navigation tabs:** 4 tab, label + icon, no badge kecuali download active

---

## 12. Accessibility Requirements

- Contrast ratio minimum: 4.5:1 untuk text biasa, 3:1 untuk large text (WCAG AA)
- Semua icon harus punya `contentDescription` yang bermakna
- Touch target minimum: 48 dp x 48 dp
- Scrollable content: keyboard navigation support
- Camera screens: announce OCR result ke accessibility service
- Download progress: announce completion via accessibility announcement
