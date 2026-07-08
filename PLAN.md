# PLAN.md — ParasDokusha Improvement Plan

> Created after full analysis of ParasDokusha + NoveLA fork + HnDK0 external-sources.
> Read this BEFORE making any edits.

## Current State (ParasDokusha)

### What works:
- 28 built-in Kotlin sources + 1 local EPUB source
- TimoTxt sources (raw, Google Translate, Gemini) with transformWebviewUrl separation
- Cloudflare bypass (BrowserHeaders + WebView solver)
- Hilt DI, Room v9, OkHttp 5, Compose + XML hybrid
- Lua engine (luaj-jse) for HnDK0 external sources — loads en/zh/mtl plugins from GitHub
- Wtrlab native Kotlin source (ported from Python script)
- Workflow fixes (5 workflows)
- Docs: AGENTS.md, ARCHITECTURE.md, PERFORMANCE.md, TROUBLESHOOTING.md, SOURCES.md

### What's broken / needs fixing:
1. **Wtrlab returns Chinese text** — `"translate":"web"` should be `"translate":"ai"` — FIXED
2. **External Lua sources don't match URLs** — `createAdapter()` uses `src.baseUrl` (always null) instead of `parseBaseUrlFromLua(luaScript)` — FIXED
3. **`Response.toDocument()` doesn't set base URI** — `doc.location()` returns empty string — FIXED
4. **MLKit is slow and bloats APK** — should be replaced with NoveLA's 4 cloud translation providers

## NoveLA Fork — Key Findings

### Translation (4 providers, all cloud-based):
1. **Google PA (Enhanced)** — `translate-pa.googleapis.com/v1/translateHtml`, auto-fetches API key from wtr-lab.com
2. **Google Free** — `translate.googleapis.com/translate_a/single` (same as TimoTxtTranslate)
3. **Gemini** — `generativelanguage.googleapis.com`, BLOCK_NONE safety, numbered-list protocol
4. **OpenAI-compatible** — configurable baseUrl/model, multi-key rotation

### Lua Sources:
- LuaEngine with ~30 API functions
- LuaSourceAdapter with 4-subclass hierarchy (base/configurable/filterable/full)
- Filter protocol (6 types)
- Cloudflare per-source options via `cf_options`
- Disk cache for instant UI rendering

### UI Features:
- Floating TTS mini-player (system-wide overlay)
- Lock-screen media session with cover art + remaining time
- Dynamic CPS calibration (EMA smoothing)
- Parallel translation display (original + translated)
- Per-novel translation prompts

## Implementation Plan

### Phase 1: Critical Bug Fixes (DONE THIS SESSION)
1. Fix Wtrlab — change "web" to "ai" mode ✓
2. Fix external Lua source baseUrl — use parseBaseUrlFromLua ✓
3. Fix Response.toDocument() to set base URI ✓

### Phase 2: Remove MLKit + Port 4 Translation Providers (NEXT SESSION)
- Delete `tooling/text_translator/translator/` module
- Port NoveLA's TranslationManagerComposite + 4 providers into translator_nop
- Port ChapterTranslation Room entity for DB caching
- Port parallel translation display in reader
- Port per-novel prompt system
- Port 226-language picker

### Phase 3: Improve Lua Source Integration (LATER)
- Port FilterableCatalog interface
- Port Configurable settings support
- Port cf_options Cloudflare per-source configuration
- Port source cache JSON for instant UI rendering

### Phase 4: UI Improvements (LATER)
- Port Floating TTS mini-player
- Port lock-screen media session with cover art
- Port dynamic CPS calibration
- Port parallel translation display

## Decisions
1. Don't upgrade Kotlin/AGP/Gradle — stay on 1.9.23 / 8.2.2
2. Don't copy NoveLA's UI — only port specific features as additions
3. Keep built-in Kotlin sources — Lua sources are ADDITIONAL
4. Remove MLKit in next session
5. Upload to tmpfiles.org with max expiry (172800 seconds)
