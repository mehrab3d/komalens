# KomaLens — Real-Time Manga, Manhwa & Manhua Screen Translator

KomaLens is an Android comic translation studio and real-time screen reader designed for manga (Japanese), manhwa (Korean), and manhua (Chinese) readers. It combines a floating screen-capture overlay for external reading apps with a built-in webtoon/image reader, powered by a hybrid Google ML Kit + Gemini AI translation pipeline that replaces raw CJK speech bubbles in place and lets readers tap any bubble for deep linguistic breakdowns.

## User Review & Critical Decisions

> [!IMPORTANT]
> All core functional and UX preferences from our initial Q&A have been locked into this specification. Please review the architectural choices below and click **Proceed** when ready to build.

- **Confirmed Capture & Reading Modes**: **Both Floating Screen Overlay & Built-In Reader** — Users can launch a floating translation pill over external manga/webtoon apps (using Android `MediaProjection` and `SYSTEM_ALERT_WINDOW`) or read directly inside KomaLens via the built-in Webtoon Browser & Image Chapter Reader.
- **Confirmed OCR & Translation Engine**: **Hybrid ML Kit + Gemini AI (`gemini-3.5-flash`)** — Google ML Kit Text Recognition v2 (Japanese, Korean, Chinese) handles sub-second bounding-box detection and instant on-device translation, while Gemini AI provides context-aware comic dialogue localization, SFX interpretation, and tap-to-inspect grammar/romanization breakdowns.
- **Confirmed Source Languages**: **Japanese (Manga), Korean (Manhwa), and Chinese (Manhua)** — Full support for vertical right-to-left Japanese typesetting, vertical scrolling Korean webtoons, and Simplified/Traditional Chinese manhua panels.
- **Confirmed Features**: **In-Bubble Text Replacement**, **Tap-to-Inspect Bubble Breakdown Drawer**, **Custom Character & Terminology Glossary**, and **Saved Translated Pages & Reading History** synced via Firebase Firestore with Google Sign-In.

---

## 1. Overview & Core Concept

- **What It Does**: Eliminates waiting for late scanlations by detecting Japanese, Korean, and Chinese text on screen or inside loaded comic pages, masking the original text inside speech bubbles, and rendering localized English text directly over the artwork.
- **Target Audience / Persona**: Avid manga, manhwa, and manhua readers following raw chapters on webtoon portals, official raw apps, or downloaded chapter images who want accurate character names, natural comic phrasing, and instant linguistic inspection.
- **Key Value**: Unlike generic camera translators that struggle with vertical Japanese text, stylized comic fonts, or inconsistent character names, KomaLens merges ML Kit's precise CJK bounding-box OCR with Gemini AI's comic-aware localization and a user-curated terminology glossary.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Authentication & Cloud Sync Setup**: On launch, users sign in with Google via Jetpack Credential Manager so their custom character glossaries and translated reading history stay synced across devices.
2. **Built-In Comic & Webtoon Reader**:
   - Users can load local chapter images (via the zero-permission Android Photo Picker), open raw webtoon/manga URLs in the integrated WebView reader, or test immediately with curated built-in raw Manga (JP), Manhwa (KR), and Manhua (ZH) sample panels.
   - Tapping **Scan & Translate Page** detects all speech bubbles, groups character lines into coherent dialogue blocks, applies custom glossary rules, and renders clean English text overlays inside each bubble.
   - A quick-toggle pill switches between **Translated Overlay**, **Hold/Peek Original Raw**, and **Side-by-Side Bubble List**.
3. **Tap-to-Inspect Bubble Breakdown**:
   - Tapping any detected speech bubble highlights its bounding box and opens an interactive **Bubble Inspector Sheet** displaying:
     - Original CJK script (Japanese Kanji/Kana, Korean Hangul, or Chinese Hanzi).
     - Full phonetic reading (Romaji / Revised Romanization / Pinyin).
     - Fast ML Kit literal translation vs. Gemini AI context-localized dialogue.
     - Vocabulary & cultural/honorific breakdown (e.g., `-senpai`, `-nim`, `Shixiong`) with a one-tap **"+ Add Term to Glossary"** button.
4. **Floating Screen Overlay Mode (Real-Time External App Translator)**:
   - From the **Overlay Radar** tab, the user grants "Display over other apps" and `MediaProjection` screen capture permission to launch a draggable floating KomaLens controller over any third-party app.
   - Tapping the floating **Scan Screen** trigger captures the current screen frame, runs the Hybrid ML Kit + Gemini pipeline, and renders interactive translated speech bubbles directly on the transparent screen overlay.
5. **Custom Glossary & Saved History**:
   - **Glossary Manager**: Define series-specific character names, martial arts sects, skills, and honorific rules (e.g., `성진우` → `Sung Jin-Woo`) that are automatically enforced during translation.
   - **Reading History & Saved Captures**: Browse previously translated pages and dialogue logs, filter by language (`JP`, `KR`, `ZH`) or series tag, and re-open full bubble breakdowns anytime.

### Visual Identity & Theme
- **Aesthetic Direction**: **Editorial Manga Studio ("Ink & Neon")** — High-contrast dark ink surfaces inspired by modern digital comic studios, accented with crisp halftone details, electric cyber-cyan OCR target indicators, and warm manga-magenta highlights.
- **Color Palette & Mood**:
  - `--background` / Surface Primary: Deep Sumi Ink (`#0D0F14`) and Layered Panel Slate (`#161922`) to keep comic artwork vibrant and distraction-free.
  - `--primary`: Electric Cyber Cyan (`#00E5FF`) for active OCR scan beams, bounding-box locks, and primary actions.
  - `--secondary` / `--accent`: Manga Screentone Coral/Magenta (`#FF3366`) for SFX tags and Gemini AI context badges, paired with Paper Cream (`#F8F6F0`) for high-legibility speech bubble fills.
- **Typography & Hierarchy**:
  - **Display & Headers**: Bundled **Space Grotesk** (`res/font/`) for bold, geometric studio headers, language badges, and HUD counters.
  - **Body & Dialogue Typesetting**: Bundled **Plus Jakarta Sans** (`res/font/`) for crisp, compact comic speech-bubble typesetting and linguistic breakdowns, plus **JetBrains Mono** for phonetic readings and OCR confidence metrics.
- **Component Styling & Layout**:
  - Material 3 adaptive layout supporting both compact vertical handhelds and expanded two-pane tablet views (Canvas Reader on the left, live Bubble Inspector & Glossary supporting pane on the right).
  - Edge-to-edge full-bleed reader canvas with floating translucent HUD controls respecting system status and navigation bar insets.
- **Interactive Feedback & Motion**:
  - Animated laser scan-line sweep across the comic page during OCR detection.
  - Spring-animated bounding-box morphing when switching between raw CJK text and English bubble replacement.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hybrid ML Kit On-Device OCR + Gemini 3.5 Flash Context Engine**
  - *Chosen Approach*: Use Google ML Kit Text Recognition v2 (Japanese, Korean, Chinese recognizers) for rapid spatial bounding-box extraction and ML Kit Translate for offline/instant baseline translation, paired with Gemini (`gemini-3.5-flash`) for contextual comic localization, romanization, and grammar notes.
  - *Why*: Pure LLM vision calls can be slow and imprecise with exact pixel coordinates for small speech bubbles, while pure on-device machine translation often mistranslates manga slang, pronouns, and character names. Combining ML Kit's exact spatial coordinates with Gemini's contextual intelligence delivers both speed and scanlation-grade quality.
  - *Alternatives Considered*: ML Kit alone (lacks romanization, SFX context, and glossary awareness) or Gemini Vision alone (higher latency for real-time screen scanning).
- **Decision 2: Dual-Mode Capture (Built-In Reader + Floating `MediaProjection` Overlay)**
  - *Chosen Approach*: Provide both a self-contained in-app Reader (supporting local images, Webtoon URLs, and preloaded JP/KR/ZH raw comic pages) and a system-wide Foreground Service Floating Overlay.
  - *Why*: Users frequently switch between reading in browser/image viewers and external apps. Having a full in-app reader also guarantees instant, zero-friction testing in any environment before enabling system overlay permissions.
- **Decision 3: Cloud Persistence with Firebase Firestore & Google Sign-In**
  - *Chosen Approach*: Store custom character glossaries and saved translation history in Firebase Firestore scoped to the authenticated Google user via Jetpack Credential Manager.
  - *Why*: Building a series glossary (character names, recurring terms) and saving translated chapters takes significant user effort; Firestore ensures these records persist across device changes and app reinstalls with strict per-user security rules.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Presentation Layer (Jetpack Compose)                 │
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────┐  ┌────────────┐  │
│  │ Built-In Reader  │  │ Floating Overlay │  │ Glossary  │  │  Saved     │  │
│  │ (Image / WebURL  │  │ Controller & HUD │  │  Studio   │  │  History   │  │
│  │  & Sample Raws)  │  │ (System Window)  │  │  Screen   │  │  Screen    │  │
│  └────────┬─────────┘  └────────┬─────────┘  └─────┬─────┘  └──────┬─────┘  │
│           │                     │                  │               │        │
│           ▼                     ▼                  ▼               ▼        │
│  ┌───────────────────────────────────────┐  ┌────────────────────────────┐  │
│  │     Bubble Inspector Bottom Sheet     │  │   Auth Gate (Credential    │  │
│  │ (Raw, Romaji/Pinyin, Breakdown, +Term)│  │   Manager Google Sign-In)  │  │
│  └───────────────────┬───────────────────┘  └──────────────┬─────────────┘  │
└──────────────────────┼─────────────────────────────────────┼────────────────┘
                       │                                     │
                       ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     Domain & State Layer (MVVM ViewModels)                  │
│                                                                             │
│  ┌───────────────────────────────────────┐  ┌────────────────────────────┐  │
│  │        TranslatorViewModel            │  │   GlossaryAndHistoryVM     │  │
│  │ • Active Page Bitmap & Bubble State   │  │ • Series Filter & Search   │  │
│  │ • Source Script (JP / KR / ZH)        │  │ • Glossary Injection Map   │  │
│  │ • Overlay Mode (Translated/Raw/Split) │  │ • Saved Session Snapshots  │  │
│  └───────────────────┬───────────────────┘  └──────────────┬─────────────┘  │
└──────────────────────┼─────────────────────────────────────┼────────────────┘
                       │                                     │
         ┌─────────────┴─────────────┐                       │
         ▼                           ▼                       ▼
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────────┐
│  Google ML Kit SDK  │   │  Gemini REST Engine │   │   Firestore Repository  │
│ • Text Recognition  │   │ (gemini-3.5-flash)  │   │ • /users/{uid}/glossary │
│   (JP, KR, ZH v2)   │   │ • Contextual Bubble │   │ • /users/{uid}/history  │
│ • On-Device         │   │   Localization      │   │ • Real-time Snapshots   │
│   Translation       │   │ • Romanization &    │   │   & Hardened Rules      │
│ • Bubble Grouping   │   │   Grammar Breakdown │   │                         │
└─────────────────────┘   └─────────────────────┘   └─────────────────────────┘
```

### Data Model & State
- **`DetectedBubble`**: Holds spatial bounding box (`RectF` normalized `0f..1f` so overlays scale cleanly across any screen orientation or zoom level), `rawText`, `sourceLanguage` (`JA`, `KO`, `ZH`), `mlKitTranslation`, `geminiLocalizedText`, `romanization` (Romaji/Romanization/Pinyin), `grammarNotes`, `sfxCategory`, and `isVertical`.
- **`GlossaryEntry` (Firestore `/users/{uid}/glossary/{entryId}`)**: `id`, `userId`, `sourceTerm`, `targetTerm`, `sourceLanguage`, `seriesTitle`, `category` (`Character`, `Skill/Term`, `Location`, `Honorific`), `createdAt`, `updatedAt`.
- **`TranslationHistoryItem` (Firestore `/users/{uid}/history/{historyId}`)**: `id`, `userId`, `pageTitle`, `sourceLanguage`, `sourceType` (`Reader`, `Webtoon`, `ScreenOverlay`), `bubbleCount`, `summaryPreview`, `bubblesJson` (serialized list of translated bubbles for instant review), `createdAt`, `updatedAt`.

### Interactive Component & State Mapping
- **Reader & Bubble Canvas (`ReaderScreen`)**:
  - Selecting a language script (`Japanese`, `Korean`, `Chinese`) switches the active ML Kit TextRecognizer client (`JapaneseTextRecognizerOptions`, `KoreanTextRecognizerOptions`, `ChineseTextRecognizerOptions`) and updates reading-order sorting (Right-to-Left vertical columns for Manga vs. Top-to-Bottom left-aligned for Manhwa).
  - Pinch-to-zoom and pan gestures transform both the underlying comic bitmap and the synchronized `DetectedBubble` overlay canvas in lockstep.
  - Tapping a translated speech bubble sets `selectedBubble` in `TranslatorViewModel`, opening the `BubbleInspectorSheet`.
- **Floating Screen Translator Service (`ScreenOverlayService`)**:
  - Manages Android `SYSTEM_ALERT_WINDOW` permission flow and `MediaProjectionManager.createScreenCaptureIntent()` launcher.
  - Renders a compact, draggable floating trigger pill; tapping **Capture & Translate** grabs a frame from the `VirtualDisplay` `ImageReader`, hides the overlay briefly during capture to avoid self-occlusion, runs the OCR + Gemini translation pipeline, and displays interactive speech-bubble overlays with a single-tap dismiss control.
- **Glossary & History Persistence (`GlossaryScreen` & `HistoryScreen`)**:
  - Adding, editing, or deleting a glossary term writes immediately to Firestore `/users/{uid}/glossary` with server timestamps; active glossary terms are automatically passed into both local post-processing and the Gemini system instruction prompt so character names never drift.
  - Saving a translated page or tapping "Save to History" records the full bubble breakdown to `/users/{uid}/history`, allowing users to search, filter by language, or delete past sessions.
