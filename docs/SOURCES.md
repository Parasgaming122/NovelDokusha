# NovelDokusha Sources

This document lists all novel sources currently supported by NovelDokusha, as
well as sources that have been removed. It also documents the Cloudflare bypass
mechanism and the source architecture.

## Active Sources

### English

| Source | Base URL | Notes |
|--------|----------|-------|
| Light Novel Translations | `https://lightnovelstranslations.com/` | |
| Read Novel Full | `https://readnovelfull.com/` | |
| Royal Road | `https://www.royalroad.com/` | |
| Novel Updates | `https://www.novelupdates.com/` | Also used as a database |
| Reddit | `https://www.reddit.com/` | |
| AT | `https://www.a-t.nu/` | |
| Wuxia | `https://wuxia.click/` | **Updated 2026-06-30**: replaced dead `wuxia.blog` with `wuxia.click`. Next.js-based site; data extracted from `__NEXT_DATA__` JSON. |
| Novel Fire | `https://novelfire.net/` | **Added 2026-06-30**. Same template as NovelPhoenix. Search uses `?keyword=` (not `?q=`). |
| Novel Phoenix | `https://novelphoenix.com/` | **Added 2026-06-30**. Same template as NovelFire. Chapter list is paginated (100/page). |
| Novel Cool | `https://www.novelcool.com/` | **Added 2026-06-30**. Scraper implemented from archived HTML structure; will work when site is back online. |
| Lnori | `https://lnori.com/` | **Added 2026-06-30**. Volume-based Japanese light novel source. Each "chapter" is an entire volume. |
| Wuxia Box | `https://www.wuxiabox.com/` | **Added 2026-06-30**. Scraper implemented from archived HTML; may be behind Cloudflare challenge. |
| Sousetsuka | `https://www.sousetsuka.com/` | |
| Box Novel | `https://boxnovel.com/` | |
| NovelHall | `https://www.novelhall.com/` | |
| Wuxia World | `https://wuxiaworld.site/` | |
| Saikai | `https://saikaiscan.com.br/` | May be behind Discord login |
| Light Novel World | `https://www.lightnovelworld.com/` | |
| Meio Novel | `https://meionovel.id/` | |
| More Novel | `https://morenovel.net/` | |
| NovelKu | `https://novelku.id/` | |
| Wb Novel | `https://wbnovel.com/` | |

### Indonesian

| Source | Base URL |
|--------|----------|
| Indonesia Webnovel | `https://indowebnovel.id/` |
| Baca Lightnovel | `https://bacalightnovel.co/` |
| Sakura Novel | `https://sakuranovel.id/` |

### Chinese

| Source | Base URL | Notes |
|--------|----------|-------|
| TimoTxt | `https://www.timotxt.com/` | Base source. Fetches Chinese HTML directly from timotxt.com (via Cloudflare bypass). No translation. |
| TimoTxt (Translated) | `https://www-timotxt-com.translate.goog/` | Uses `translate.goog` as a Cloudflare-bypassing proxy. Fetches Chinese HTML, then translates to English via Google Translate API (`translate.googleapis.com/translate_a/single?client=gtx`). |
| TimoTxt (Gemini) | `https://www-timotxt-com-gemini.goog/` | Same `translate.goog` proxy pipeline, but uses Google Gemini AI for higher-quality chapter translation. Requires a Gemini API key (set in Settings). |

### Local

| Source | Notes |
|--------|-------|
| Local Source | Read local EPUB files |

## Removed Sources (2026-06-30)

The following sources were removed because their domains are dead, parked, or serving scam content:

| Source | Old Domain | Reason |
|--------|-----------|--------|
| Read Light Novel | `https://www.readlightnovel.org` | Domain expired (DNS NXDOMAIN) |
| BestLightNovel | `https://bestlightnovel.com/` | Redirects to scam tarot game |
| 1stKissNovel | `https://1stkissnovel.org/` | Scam stub; ww1 subdomain returns HTTP 436 |
| MTLNovel | `http://ww1.mtlnovels.com` | Dead stub (475 bytes) |
| Korean Novels MTL | `https://www.koreanmtl.online/` | Scam PPC-redirect stub |
| NovelBin | `https://novelbin.net/` | Dead stub (474 bytes) |

## Database Sources

| Database | Base URL |
|----------|----------|
| Novel Updates | `https://www.novelupdates.com/` |
| Baka-Updates | `https://www.mangaupdates.com/` |

## Architecture Notes

### Source Interface

All sources implement `SourceInterface` (defined in `SourceInterface.kt`). There are two subtypes:

- **`SourceInterface.Base`** — Sources with only a base URL (no catalog/search). Used for sources like Reddit, Sousetsuka, AT.
- **`SourceInterface.Catalog`** — Sources with a catalog URL, search, book info, chapter list, and chapter reader. Most sources implement this.

### Registration

Sources are registered in `Scraper.kt` via the `sourcesList` set. Each source
takes a `NetworkClient` parameter for HTTP requests. The `Scraper` class is
provided via Hilt dependency injection.

### String Resources

Each source has a display name string in
`strings/src/main/res/values/strings-no-translatable.xml` with the naming
convention `source_name_<id>`.

### Testing

Each catalog source has an instrumented test in
`app/src/androidTest/java/my/noveldokusha/SourcesCatalogTest.kt` that verifies
the source can be opened in the app.

Unit tests in `app/src/test/java/my/noveldokusha/ScraperTest.kt` verify that:
- All source base URLs end with a slash
- All source IDs are unique
- All sources can be found by their base URL

### ProGuard / R8 Rules

The app's ProGuard rules (`app/proguard-rules.pro`) include `-keep` rules for:
- All source classes under `my.noveldokusha.scraper.sources.**`
- All database classes under `my.noveldokusha.scraper.databases.**`
- The `Scraper` registry class
- The `SourceInterface` sealed interface and its nested subtypes
- All networking interceptors (including the Cloudflare bypass)
- Custom exception classes

This ensures that R8's code removal doesn't strip source metadata (IDs, base
URLs, string resource IDs) that are accessed reflectively at runtime.

### Database Migrations

When a source's domain changes, a database migration is added in
`tooling/local_database/src/main/java/my/noveldokusha/feature/local_database/migrations/`
to update stored URLs. Historical migrations for removed sources are kept to
support users with older databases.

## Cloudflare Bypass

Many novel hosting sites are protected by Cloudflare's "managed challenge" or
"JS challenge" systems. NovelDokusha includes a **multi-tier** Cloudflare
evasion/bypass mechanism inspired by the [trawl](https://github.com/germondai/trawl)
project. The bypass lives in two files:

- `networking/src/main/java/my/noveldokusha/network/interceptors/BrowserHeadersInterceptor.kt`
  — Tier 1: makes every OkHttp request look like a real Chrome navigation so
  most sites never escalate to a challenge in the first place.
- `networking/src/main/java/my/noveldokusha/network/interceptors/CloudfareVerificationInterceptor.kt`
  — Tier 2/3: if a challenge is still served, fires up a headless WebView to
  solve it, harvests the `cf_clearance` cookie, and retries the original
  request.

### Tier 1 — Browser-headers evasion

The `BrowserHeadersInterceptor` sets the standard set of headers that Chrome
sends on every navigation request. Many bot-protection services never escalate
to a JS challenge at all if the initial request looks browser-like; conversely,
a request that omits these headers is trivially identifiable as a non-browser
client. The headers set (each only if not already present on the request):

| Header | Value | Why |
|--------|-------|-----|
| `Accept` | `text/html,application/xhtml+xml,...` | Standard browser content negotiation |
| `Accept-Language` | `en-US,en;q=0.9` | Matches the WebView default |
| `Accept-Encoding` | `gzip, deflate, br` | DecodeResponseInterceptor handles decompression |
| `Cache-Control` | `no-cache` | Avoids serving stale CF challenge pages from cache |
| `Pragma` | `no-cache` | HTTP/1.0 equivalent, belt-and-suspenders |
| `Upgrade-Insecure-Requests` | `1` | Chrome always sends this on navigations |
| `Sec-Fetch-Dest` | `document` | Modern browser fingerprint signal |
| `Sec-Fetch-Mode` | `navigate` | Modern browser fingerprint signal |
| `Sec-Fetch-Site` | `none` | Top-level navigation, no referrer |
| `Sec-Fetch-User` | `?1` | User-initiated navigation |
| `Sec-CH-UA` | `"Chromium";v="120",...` | Matches the User-Agent set by UserAgentInterceptor |
| `Sec-CH-UA-Mobile` | `?1` | Mobile UA |
| `Sec-CH-UA-Platform` | `"Android"` | Matches the Pixel UA |

### Tier 2/3 — WebView-based challenge solving

When a response is detected as a Cloudflare challenge, the
`CloudFareVerificationInterceptor` takes over:

1. **Detection** — The interceptor checks every response for Cloudflare
   signatures:
   - HTTP 202/403/429/502/503 with a `Server: cloudflare*` (or `ddos-guard*`)
     header, OR
   - The `cf-mitigated` response header is present (CF sets this even on
     non-HTML responses — strongest signal), OR
   - Response body containing Cloudflare challenge markers (e.g.
     `cf-browser-verification`, `Just a moment`, `challenge-platform`,
     `cf-turnstile`, `verify you are human`, `id="turnstile-wrapper"`,
     `ddos-guard.net`, etc.) — this catches "managed challenge" pages that
     return HTTP 200 with no Server header.

2. **WebView-based bypass** — When a challenge is detected:
   - A headless `WebView` is created on the main thread (required by Android).
   - The WebView's User-Agent is forced to match the OkHttp request's
     User-Agent (critical — Cloudflare rejects challenges where the UA
     differs between the triggering request and the challenge-solving
     request).
   - All relevant headers (Accept, Accept-Language, Referer, Sec-CH-UA-*,
     Sec-Fetch-*) are forwarded to the WebView so the fingerprint matches.
   - The WebView loads the challenged URL, which triggers Cloudflare's
     JavaScript challenge.

3. **Multi-signal completion detection** — The interceptor polls every 300ms
   and exits as soon as ANY of:
   - The `cf_clearance` cookie appears in the `CookieManager`, OR
   - The page title changes away from a challenge title (`Just a moment`,
     `Verify you are human`, `Please wait`, `One more step`,
     `Attention required`, `Checking your browser`) — catches challenges
     that set the cookie via a redirect that doesn't change the URL, OR
   - The page URL navigates away from the challenge URL.

4. **Force re-navigation when stuck** — If `cf_clearance` has been set but
   the page is still on the challenge URL after 5 seconds, the interceptor
   manually loads the original URL. This is the trick trawl uses to break
   "cookie set but page stuck" loops that Turnstile sometimes triggers.

5. **Turnstile checkbox click** — Every 3 seconds, the interceptor injects
   JavaScript into the WebView that dispatches a click event at the
   Cloudflare Turnstile iframe (and a fallback in-page widget). For
   interactive Turnstile widgets, this is what actually solves the
   challenge; for non-interactive ("managed") widgets, it's a harmless
   no-op.

6. **IP-block fast-fail** — If the WebView navigates to a URL containing
   `/cdn-cgi/error/`, `error=1020`, or `error=1015`, the interceptor
   aborts immediately with a clear error message. No amount of
   cookie-baking will fix a hard IP block; the user needs a different
   network.

7. **Cookie sharing** — The `ScraperCookieJar` (OkHttp's CookieJar) is
   backed by the same `android.webkit.CookieManager` as the WebView. So
   when the WebView solves the challenge and receives `cf_clearance`,
   OkHttp automatically picks it up for subsequent requests.

8. **Retry mechanism** — If the first bypass attempt fails (still
   challenged after retry), the interceptor retries up to 3 times. Each
   attempt creates a fresh WebView.

9. **Leak safety** — The WebView is always `stopLoading()` + `destroy()`
   in a `finally` block, and the `WebViewClient` reference is reset
   before destroy to avoid leaking the Activity context.

### Configuration

The bypass behavior is controlled by these constants in the interceptor file:

| Constant | Default | Purpose |
|----------|---------|---------|
| `MAX_BYPASS_ATTEMPTS` | 3 | Max WebView retries per challenged request |
| `MAX_CHALLENGE_WAIT` | 20 seconds | Hard cap on single attempt duration |
| `CHALLENGE_POLL_INTERVAL` | 300ms | Cookie polling frequency |
| `FORCE_NAVIGATION_DELAY` | 5 seconds | Grace period before force-re-navigating a stuck challenge |
| `ERROR_CODES` | 202, 403, 429, 502, 503 | HTTP status codes that trigger challenge detection |
| `CHALLENGE_HEADER_NAMES` | `cf-mitigated` | Response headers whose presence indicates a challenge |
| `SERVER_HEADER_VALUES` | cloudflare, ddos-guard, ... | `Server` header values that identify CF/DDoS-Guard |
| `CHALLENGE_BODY_MARKERS` | 21 markers | Body substrings that identify a challenge page |
| `CHALLENGE_TITLE_MARKERS` | 6 markers | Page-title substrings that indicate an active challenge |
| `IP_BLOCKED_URL_MARKERS` | `/cdn-cgi/error/`, `error=1020`, `error=1015` | URL patterns indicating a hard IP block |

### Interceptor chain order

The OkHttp interceptors are applied in this order (defined in
`NetworkClient.kt`):

1. `HttpLoggingInterceptor` (debug builds only) — logs full request/response
   bodies.
2. `UserAgentInterceptor` — sets a modern Android Chrome User-Agent if none
   is present.
3. `BrowserHeadersInterceptor` — Tier 1: adds the browser-fingerprint
   headers described above.
4. `DecodeResponseInterceptor` — decompresses `gzip` and `br` response
   bodies.
5. `CloudFareVerificationInterceptor` — Tier 2/3: detects challenges and
   solves them via WebView.

Order matters: the User-Agent must be set before the browser headers so
that `Sec-CH-UA` can match it; the decode interceptor must run before the
Cloudflare interceptor so that compressed challenge bodies can be inspected.

### Limitations

- **Turnstile interactive challenges** — The interceptor injects JavaScript
  to click the Turnstile checkbox, which works for most interactive widgets.
  However, Cloudflare may occasionally serve a harder challenge (e.g.
  image-based) that cannot be solved headlessly. In that case the bypass
  will time out after 20 seconds and retry.
- **IP-level blocks** — If Cloudflare blocks the IP entirely (error 1020 /
  1015), the interceptor detects this and fails fast with a clear error
  message. The user would need to use a different network.
- **Android WebView required** — The bypass uses `android.webkit.WebView`,
  which is only available on Android devices. It does not work in unit
  tests or on JVM.
- **First-request cost** — The first challenged request on a fresh session
  pays the full WebView solve cost (2-20 seconds). Subsequent requests to
  the same domain reuse the cached `cf_clearance` cookie and pay no cost.

## TimoTxt Translation Pipeline

The three TimoTxt sources (`TimoTxt`, `TimoTxtTranslate`, `TimoTxtGemini`)
all read from `www.timotxt.com`, a Chinese novel site behind Cloudflare.
They differ in how they fetch content and whether they translate it.

### The Cloudflare bypass trick

`www.timotxt.com` is behind Cloudflare, which blocks non-browser HTTP
clients. Direct OkHttp fetches get a 403/503 challenge page even with the
BrowserHeadersInterceptor.

The translate variants use **`translate.goog`** (Google Translate's URL
proxy) as a bypass:

```
https://www-timotxt-com.translate.goog/<path>?_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp
```

This works because:
1. `translate.goog` is hosted on Google IPs, which Cloudflare does not
   challenge (Google is a trusted upstream).
2. The proxy fetches the page server-side from `timotxt.com` and serves
   the HTML to the client. The HTML it returns is the **original Chinese**
   HTML (not translated — translation happens client-side via JS, which
   OkHttp doesn't execute).
3. Getting untranslated Chinese HTML is actually a **feature**: it lets
   us control translation separately (via Google Translate API or Gemini
   AI) rather than relying on translate.goog's JS-based translation.

### The `toTranslateUrl()` helper

All three TimoTxt sources share a helper that converts any timotxt.com,
gemini.goog, or translate.goog URL to the canonical translate.goog
format with the correct query params:

```kotlin
private fun toTranslateUrl(url: String): String {
    val path = extractPath(url)  // strips domain, keeps path + query
    return "https://www-timotxt-com.translate.goog/${path.trimStart('/')}" +
           "?_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"
}
```

### Translation strategies

| Source | Translation backend | API endpoint | Quality | Speed |
|--------|---------------------|--------------|---------|-------|
| `TimoTxt` | None (raw Chinese) | — | — | Fastest |
| `TimoTxtTranslate` | Google Translate API (free `gtx` client) | `translate.googleapis.com/translate_a/single?client=gtx` | Good | Fast (batch) |
| `TimoTxtGemini` | Google Gemini AI | `generativelanguage.googleapis.com` (via `GeminiApiClient`) | Best | Slower (AI reasoning) |

### Chapter text translation pipeline

1. **Fetch HTML** from `translate.goog` (bypasses Cloudflare, returns
   Chinese HTML).
2. **Extract paragraphs** using Jsoup. Strip `<font>` wrapper tags and
   `<span style="display:none">` hidden elements (translate.goog injects
   these).
3. **Chunk** into batches of ~4500 characters (Google Translate API has
   a ~5000-char request limit).
4. **Batch translate**:
   - `TimoTxtTranslate`: Joins titles/paragraphs with `|||` separator,
     sends as a single Google Translate API request, splits the response
     back. Retries with exponential backoff on rate-limit (HTTP 429).
   - `TimoTxtGemini`: Sends each chunk to Gemini with a prompt that
     instructs it to translate Chinese → English while preserving
     paragraph structure and not adding commentary.
5. **Clean up** junk patterns: translate.goog injects "Translate" UI
   text and Google Translate occasionally produces artifacts like
   `[...]` or `>>>`. The `CHINESE_JUNK_PATTERNS` and
   `ENGLISH_JUNK_PATTERNS` regexes strip these.

### Catalog and search URL format

Catalog and search URLs also go through translate.goog:

```
https://www-timotxt-com.translate.goog/cat/index_<page>.html?_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp
https://www-timotxt-com.translate.goog/search/index.html?searchkey=<query>&_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp
```

The `TimoTxtGemini` source uses a different `baseUrl`
(`https://www-timotxt-com-gemini.goog/`) purely for **routing
uniqueness** — so the app's source selector can distinguish it from
`TimoTxtTranslate`. Internally, both fetch from `translate.goog`. The
`transformChapterUrl()` and `transformWebviewUrl()` methods in
`TimoTxtGemini` convert the gemini.goog URL back to translate.goog
before any HTTP fetch or WebView display.

### Limitations

- **translate.goog rate limits** — Under heavy use, Google may
  temporarily block the client IP (HTTP 429). The code retries with
  exponential backoff, but sustained bulk translation will fail.
- **Gemini API key required** — `TimoTxtGemini` requires the user to
  enter a Gemini API key in Settings. Without a key, it falls back to
  showing raw Chinese text.
- **translate.goog HTML structure changes** — If Google changes the
  proxy's HTML output (e.g., adds new wrapper tags, changes the hidden
  span pattern), the cleanup code in `TimoTxtTranslate.kt` needs to be
  updated. The `<font>` unwrap and `span[style*=display:none]` removal
  are the most likely to break.
- **No image translation** — Text inside images (e.g., chapter
  illustrations with Chinese captions) is not translated.
