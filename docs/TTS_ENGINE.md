# NovelDokusha — TTS Engine

> **Scope**: The text-to-speech system — `TextToSpeechManager` (generic `Utterance<T>` queue), the reader's `ReaderTextToSpeech` wrapper, and the `NarratorMediaControlsService` foreground service with MediaSession.

## 1. Architecture Overview

```
features/reader/ReaderTextToSpeech.kt       ← reader-specific wrapper
        │
        ▼
tooling/text_to_speech/TextToSpeechManager.kt   ← generic Utterance<T> queue
        │
        ▼
android.speech.tts.TextToSpeech             ← Android platform TTS engine
        │
        ▼
NarratorMediaControlsService                ← foreground service with MediaStyle notification
        │
        ▼
MediaSessionCompat + MediaButtonReceiver    ← media button routing
```

The TTS engine is split across two modules:
- `tooling/text_to_speech` — generic, reusable `TextToSpeechManager<T : Utterance<T>>` that wraps Android's `TextToSpeech` API. Knows nothing about novels or readers.
- `features/reader` — `ReaderTextToSpeech` (which instantiates a `TextToSpeechManager<ReaderItem>`) + `NarratorMediaControlsService` (the foreground service).

## 2. `tooling/text_to_speech` — the generic TTS manager

### 2.1 `Utterance<T>` interface

```kotlin
interface Utterance<T : Utterance<T>> {
    enum class PlayState { PLAYING, FINISHED, LOADING }

    val utteranceId: String
    val playState: PlayState
    fun copyWithState(playState: PlayState): T
}
```

The generic `T : Utterance<T>` self-bound lets the manager return the concrete subtype (e.g. `ReaderItem`) from queue operations, so callers don't need to cast.

### 2.2 `VoiceData`

```kotlin
data class VoiceData(
    val id: String,
    val language: String,
    val needsInternet: Boolean,
    val quality: Int
)
```

Mapped from `android.speech.tts.Voice`:
- `id` = `Voice.name` (e.g. `"en-us-x-sfg#male_1"`)
- `language` = `Voice.locale.displayLanguage`
- `needsInternet` = `Voice.isNetworkConnectionRequired`
- `quality` = `Voice.quality` (0–100)

### 2.3 `TextToSpeechManager<T : Utterance<T>>`

```kotlin
class TextToSpeechManager<T : Utterance<T>>(
    context: Context,
    initialItemState: T
)
```

#### Initialization
1. Constructs `android.speech.tts.TextToSpeech(context) { result -> ... }`.
2. On `TextToSpeech.SUCCESS`:
   - `listenToUtterances()` — installs an `UtteranceProgressListener` (onStart/onDone/onError/onRangeStart)
   - `availableVoices.addAll(getAvailableVoices())` — maps `service.voices` to `VoiceData`
   - `updateActiveVoice()` — sets `activeVoice.value = service.voice?.toVoiceData()`
   - Emits `serviceLoadedFlow` (a `MutableSharedFlow<Unit>(replay = 1)`)
3. `serviceLoadedFlow` is collected by `ReaderTextToSpeech.init { ... }` which then calls `trySetVoiceById(getPreferredVoiceId())`, `trySetVoicePitch(getPreferredVoicePitch())`, `trySetVoiceSpeed(getPreferredVoiceSpeed())` — i.e., the user's last-saved voice/pitch/speed is applied once the TTS service is ready.

#### State exposed

| Property | Type | Purpose |
|---|---|---|
| `availableVoices` | `SnapshotStateList<VoiceData>` | All voices from the platform TTS engine |
| `activeVoice` | `MutableState<VoiceData?>` | Currently selected voice |
| `voicePitch` | `mutableFloatStateOf` | Current pitch (0.1–5.0) |
| `voiceSpeed` | `mutableFloatStateOf` | Current speed (0.1–5.0) |
| `currentActiveItemState` | `mutableStateOf<T>` | Single source of truth for "what's playing right now" |
| `currentTextSpeakFlow` | `SharedFlow<T>` | Emits on start/done of each logical utterance |
| `serviceLoadedFlow` | `MutableSharedFlow<Unit>(replay = 1)` | Emits when TTS service is ready |

#### Voice / pitch / speed management

```kotlin
fun trySetVoiceById(id: String): Boolean {
    val voice = service.voices.find { it.name == id } ?: return false
    service.voice = voice
    updateActiveVoice()
    return true
}

fun trySetVoicePitch(value: Float): Boolean {
    if (value !in 0.1f..5f) return false
    service.setPitch(value)
    voicePitch.value = value
    return true
}

fun trySetVoiceSpeed(value: Float): Boolean {
    if (value !in 0.1f..5f) return false
    service.setSpeechRate(value)
    voiceSpeed.value = value
    return true
}
```

#### Utterance queueing

Internal state:
- `_queueList: mutableMapOf<String, T>` — utteranceId → current state
- `_queueListItemSize: mutableMapOf<String, Int>` — utteranceId → number of sub-slices

##### `speak(text, textSynthesis)`

1. **Split text** via `delimiterAwareTextSplitter(fullText=text, maxSliceLength=TextToSpeech.getMaxSpeechInputLength(), charDelimiter='.')` — Android's TTS has a per-utterance char limit (~4000 chars), so we split on sentence boundaries to stay under it.
2. **Store the entry**:
   - `_queueList[utteranceId] = textSynthesis`
   - `_queueListItemSize[utteranceId] = subItems.size`
3. **For each sub-slice**, call `service.speak(textSlice, QUEUE_ADD, bundle, uniqueID)` where `uniqueID = "$index|$utteranceId"`.

##### `UtteranceProgressListener.onStart(utteranceId)`

Parses `"index|utteranceId"`; only acts when `index == 0` (first sub-item):
- Sets `currentActiveItemState.value = queueList[utteranceId].copyWithState(PLAYING)`
- Emits to `currentTextSpeakFlow`

##### `UtteranceProgressListener.onDone(utteranceId)` / `onError(utteranceId)`

Parses `"index|utteranceId"`; only acts when `index == itemSize - 1` (last sub-item):
- Removes from queue
- Sets `currentActiveItemState.value = ...copyWithState(FINISHED)`
- Emits to `currentTextSpeakFlow`

> **Why the sub-index scheme?** A logical utterance (e.g. a 6000-character paragraph) may be split into 2 sub-slices. The manager only marks the logical utterance as "done" when its **last** sub-slice finishes. This is what allows the reader to chain utterances cleanly — it listens for the logical-utterance-done event, not the per-slice event.

##### `stop()`
Calls `service.stop()` + clears both maps.

#### `maxStringLengthPerTextUnit()`
Returns `TextToSpeech.getMaxSpeechInputLength()` — the Android constant (~4000 chars). Used by callers to know the max slice size before calling `speak`.

#### Module dependencies
`tooling/text_to_speech` depends only on `tooling/algorithms` (for `delimiterAwareTextSplitter`) + standard AndroidX. **No Hilt, no networking** — it's a pure stateful wrapper around Android's TTS API.

## 3. `features/reader/ReaderTextToSpeech` — the reader wrapper

Constructed by `ReaderSession` with:
- `items: () -> List<ReaderItem>` — accessor for the current chapter's items
- `chapterLoadedFlow: SharedFlow<ChapterLoaded>` — for chaining into next chapter
- `isChapterIndexValid`, `isChapterIndexLoaded` callbacks
- `tryLoadPreviousChapter`, `loadNextChapter` callbacks
- Voice prefs getters/setters (backed by `AppPreferences`)

Builds a `TextToSpeechManager<ReaderItem>` with `initialItemState = TextSynthesis(itemPos = ReaderItem.Title(..., chapterIndex=-1, ...), playState = FINISHED)`.

### 3.1 State exposed to UI

`state: TextToSpeechSettingData`:
- `isPlaying: Boolean`
- `isLoadingChapter: Boolean`
- `activeVoice: VoiceData?`
- `voiceSpeed: Float`, `voicePitch: Float`
- `availableVoices: List<VoiceData>`
- `currentActiveItemState: ReaderItem`
- `isThereActiveItem: Boolean`
- `customSavedVoices: List<VoicePredefineState>`
- Set/play/seek callbacks (wired to UI buttons in `VoiceReaderSettingDialog`)

Derived:
- `isActive = isThereActiveItem || isPlaying`
- `isSpeaking = isThereActiveItem && isPlaying`

### 3.2 Half-buffer prefetch

While playing, the manager monitors the queue size:
- When queue drops to `halfBuffer` (= 2) items remaining, pre-fetches the next 4 (`halfBuffer * 2`) utterances for the current chapter
- When queue reaches 0, emits `reachedChapterEndFlowChapterIndex`

`ReaderSession.initReaderTTSObservers()` listens:
- Loads the next chapter if needed
- Calls `readChapterStartingFromStart(nextChapterIndex)`

This gives a seamless "auto-continue to next chapter" experience.

### 3.3 Playback controls

| Method | Behavior |
|---|---|
| `playFirstVisibleItem()` | Starts speaking from the first visible list item |
| `playNextItem()` | Skips to the next item in the current chapter |
| `playPreviousItem()` | Returns to the previous item |
| `playNextChapter()` | Loads + speaks the next chapter from the start |
| `playPreviousChapter()` | Loads + speaks the previous chapter from the start |
| `scrollToActiveItem()` | Scrolls the ListView to the currently-speaking item |
| `setVoiceId(id)`, `setVoicePitch(p)`, `setVoiceSpeed(s)` | Update manager, persist to prefs, `resumeFromCurrentState()` (stop+start from current item) |

### 3.4 `speakItem(item)`

Only speaks items that are `ReaderItems.Text` (Title/Body). Uses `item.textToDisplay` which is `textTranslated ?: text` — so ML Kit translation flows to TTS automatically.

### 3.5 Utterance IDs

`Utterance<T>` IDs: `"${itemPos.chapterItemPosition}-${itemPos.chapterIndex}"`.

The manager splits each utterance further with `delimiterAwareTextSplitter(maxSliceLength = TextToSpeech.getMaxSpeechInputLength(), charDelimiter = '.')` and tracks sub-utterance IDs `"$subIndex|$utteranceId"` to know when an entire logical utterance finished (`onDone` only fires when `subItemUtteranceIndex == itemSize - 1`).

### 3.6 Foreground service lifecycle

When `isActive` becomes true:
- `NarratorMediaControlsService.start(context)` is called
- The service calls `startForeground(...)` with a `MediaStyle` notification

When `ReaderSession.close()` is called:
- `readerTextToSpeech.onClose()` → `service.shutdown()` (the Android TTS service, not the foreground service)
- `NarratorMediaControlsService` is stopped

## 4. `NarratorMediaControlsService` — foreground service

`@AndroidEntryPoint class NarratorMediaControlsService : Service`:
- `startForeground(notificationId, notification)` with a media-style notification built by `NarratorMediaControlsNotification`
- `START_STICKY` if intent is non-null
- `onDestroy()` calls `stopSelf()`

### 4.1 `NarratorMediaControlsNotification`

Builds a `MediaSessionCompat` with:
- `MediaMetadataCompat.METADATA_KEY_DURATION = -1L` (to hide the seekbar — see the StackOverflow comment in source)
- A `NarratorMediaControlsCallback` as the session callback

**Notification actions**: Previous, Rewind, Pause/Play (toggled), FastForward, Next.
**Compact view** shows actions 0/2/4 = Previous/Pause/Next.
**PendingIntents** built via `MediaButtonReceiver.buildMediaButtonPendingIntent`.

### 4.2 Three coroutines observe reader session state

| Observer | Updates |
|---|---|
| `state.isPlaying` | Toggles play/pause action icon |
| `currentTextPlaying` | Updates the chapter title in the notification |
| `currentTextPlaying` | Updates the content intent stack (Main → Chapters → Reader for the currently-speaking chapter) |
| `speakerStats` | Updates the text line "Chapter X/N  Progress Y%" |

Notification tap opens a synthetic back-stack: `main → chapters(bookMetadata) → ReaderActivity(scrollToSpeakingItem=true)`.

### 4.3 `NarratorMediaControlsCallback`

`MediaSessionCompat.Callback` that routes `KEYCODE_MEDIA_*` keys to `readerTextToSpeech.state`:
- `KEYCODE_MEDIA_PLAY_PAUSE` → `setPlaying(!isPlaying)`
- `KEYCODE_MEDIA_NEXT` → `playNextChapter()`
- `KEYCODE_MEDIA_PREVIOUS` → `playPreviousChapter()`
- `KEYCODE_MEDIA_FAST_FORWARD` → `playNextItem()`
- `KEYCODE_MEDIA_REWIND` → `playPreviousItem()`

## 5. `delimiterAwareTextSplitter` — shared algorithm

Lives in `tooling/algorithms` (used by both TTS and the reader's `textToItemsConverter`).

```kotlin
fun delimiterAwareTextSplitter(
    fullText: String,
    maxSliceLength: Int,
    charDelimiter: Char = '.'
): List<String>
```

- If text shorter than `maxSliceLength`, returns `listOf(fullText)`.
- Otherwise iterates with `CharBuffer.wrap(fullText)` views (avoids string copies).
- For each chunk, finds the **last** `charDelimiter` within the first `maxSliceLength` chars and slices there.
- If no delimiter, hard-slices at `maxSliceLength - 1`.
- `softEndSlice(endIndex)` returns `subSequence(0, endIndex + 1)`.
- `softStartSlice(startIndex)` returns `subSequence(startIndex + 1, length)`.

### Used by:
- `TextToSpeechManager.speak(...)` — chunk text under `TextToSpeech.getMaxSpeechInputLength()` (~4000 chars), splitting on sentence boundaries (`.`).
- `textToItemsConverter` in `features/reader/tools` — split paragraphs into ≤512-char sub-items (delimited by `.`), so the reader's ListView renders reasonable-sized items.

### Tests
`DelimiterAwareTextSplitterTest` — three cases:
1. No delimiter
2. Single delimiter (no slicing needed)
3. Small `maxSliceLength = 5` slicing verifying the chunks reassemble to the original

## 6. Limitations & Gotchas

1. **No pause/resume across process death**: The TTS manager's queue is in-memory. If the OS kills the app process while TTS is playing, the queue is lost. The foreground service helps prevent this but isn't a guarantee.
2. **Half-buffer prefetch is fixed at 2**: Not configurable. For very long chapters, this may cause a brief pause between utterances while the next batch is fetched.
3. **`TextToSpeech.getMaxSpeechInputLength()` is platform-dependent**: ~4000 chars on most devices, but could differ. The splitter handles this dynamically.
4. **Voice quality depends on the device's TTS engine**: Some devices ship with low-quality voices. Users can install better TTS engines from the Play Store (e.g. Google TTS, Samsung TTS).
5. **ML Kit translation flows to TTS**: If live translation is enabled, `item.textToDisplay` is the translated text, so TTS reads the translation. This is intentional but worth knowing.

## 7. See Also

- `UI_LAYER.md` — reader architecture, `ReaderSession`, `ReaderItemAdapter`
- `TRANSLATION_SYSTEM.md` — ML Kit translation (which flows to TTS)
- `CORE_ENGINE.md` — `delimiterAwareTextSplitter`, `VoicePredefineState`
