# NovelDokusha — Security

> **Scope**: API key storage, signing, network security, CloudFlare bypass, and security-relevant permissions.

## 1. Gemini API Key Storage

### 1.1 Current storage

The Gemini API key is stored in **plaintext** in `SharedPreferences`:

```kotlin
val GEMINI_API_KEY = object : Preference<String>("GEMINI_API_KEY") {
    override var value by SharedPreference_String(name, preferences, "")
}
```

- Stored via `PreferenceManager.getDefaultSharedPreferences(context)` — the app's default preferences file.
- Backed by an XML file at `/data/data/my.noveldokusha/shared_prefs/my.noveldokusha_preferences.xml`.
- **Not encrypted**, **not obfuscated**.

### 1.2 Access path

```
Settings UI (SettingsGemini composable)
  → state.geminiSettings.apiKey.value = it
  → AppPreferences.GEMINI_API_KEY.value = it
  → SharedPreferences.edit().putString("GEMINI_API_KEY", it).apply()

Scraper (Singleton, constructed at app startup)
  → appPreferences.GEMINI_API_KEY.value  ← read ONCE
  → TimoTxtGemini(geminiApiKey = ...)
  → GeminiApiClient(apiKey = ...)
  → used in URL query param: ?key=$apiKey
```

### 1.3 Threat model

| Threat | Status |
|---|---|
| **Rooted device** — attacker reads `/data/data/.../shared_prefs/*.xml` | ❌ Vulnerable (plaintext) |
| **Backup extraction** — attacker restores a backup zip and reads the SQLite + SharedPreferences | ⚠️ Partial — backup only includes the Room DB, NOT SharedPreferences. API key is safe in backups. |
| **Man-in-the-middle** — attacker intercepts the Gemini API call | ✅ Safe — uses HTTPS with certificate pinning disabled but TLS still encrypts the URL (including query param `?key=`). URL query params ARE encrypted in HTTPS. |
| **App process memory dump** — attacker reads the key from RAM | ❌ Vulnerable (key is a String, lives in memory) |
| **Log leakage** — key logged via Timber | ✅ Safe — `GeminiApiClient` does NOT log the API key. The URL containing the key is never logged. |

### 1.4 Recommendations

1. **Use Android Keystore + EncryptedSharedPreferences** (from `androidx.security:security-crypto`):
   ```kotlin
   val masterKey = MasterKey.Builder(context)
       .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
       .build()

   val sharedPreferences = EncryptedSharedPreferences.create(
       context,
       "secret_shared_prefs",
       masterKey,
       EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
       EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
   )
   ```
   This encrypts the SharedPreferences file at rest. The key is still in memory while the app runs, but rooted-device attacks require breaking the Keystore first.

2. **Don't put the key in URL query params** — use the `x-goog-api-key` header instead:
   ```kotlin
   val request = Request.Builder()
       .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
       .addHeader("x-goog-api-key", apiKey)
       .post(requestBody.toRequestBody(jsonMediaType))
       .build()
   ```
   This keeps the key out of URLs (which can leak via logs, referrer headers, etc.).

3. **Migrate from captured-at-construction to lazy reads** — currently `Scraper` reads the key ONCE at construction. If the user changes the key in Settings, the change doesn't take effect until app restart. A lazy property would fix this AND reduce the window during which the key is in memory.

## 2. APK Signing

### 2.1 Debug builds
Auto-signed with AGP's well-known debug keystore:
- Keystore: `~/.android/debug.keystore`
- Alias: `androiddebugkey`
- Password: `android` (both store and key)
- **Publicly known** — anyone can sign an APK with this keystore. NOT secure for distribution.

### 2.2 Release builds (CI/CD)
The `build-release-manual.yml` workflow generates a **fresh debug keystore** per run:
```bash
keytool -genkey -v \
  -keystore app/debug.keystore \
  -storepass android \
  -alias androiddebugkey \
  -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

> ⚠️ This keystore is **destroyed** after the workflow run — each release is signed with a different key. This means:
> - Users **cannot upgrade** from one release to another (different signatures → install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
> - Users must **uninstall** the old version before installing the new one, losing their library and reading progress.

### 2.3 Release builds (proper, with secrets)
The original `buildRelease.yml` workflow uses real signing secrets:
- `SIGNING_KEY` — base64-encoded `.jks`/`.keystore` file (stored as a GitHub secret)
- `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD` — stored as GitHub secrets

The keystore is decoded at build time:
```bash
echo -n ${{ secrets.SIGNING_KEY }} | base64 -d > app/storeFile.jsk
```

This produces consistently-signed APKs that can be upgraded in-place.

### 2.4 Recommendations for distribution
- **For personal use / testing**: The debug-keystore-per-run approach is fine.
- **For distribution to other users**: Set up real signing secrets and use `buildRelease.yml` (or migrate it to modern action versions — see `Build-fixes.md`).
- **For Play Store**: Use a Play Store upload key (separate from the app signing key, which Google holds).

## 3. Network Security

### 3.1 HTTPS
All source scrapers use HTTPS. The `NetworkClient` does NOT do certificate pinning — it trusts the system CA store. This is necessary because many novel sites use Cloudflare or shared hosting with rotating certs.

### 3.2 Cleartext traffic
`android:usesCleartextTraffic` is NOT set in the manifest, so it defaults to `false` on Android 9+ (API 28+). Since `minSdk = 26`, this means:
- On Android 8.0 (API 26-27): cleartext traffic is allowed by default.
- On Android 9+ (API 28+): cleartext traffic is blocked by default.

All sources use HTTPS, so this is fine. The `local://` URI scheme is handled in-app (not networked).

### 3.3 Cookies
`ScraperCookieJar` bridges OkHttp ↔ `android.webkit.CookieManager`. Cookies are stored:
- In the WebView cookie database (managed by the system).
- Persisted across app restarts.

This is necessary for CloudFlare bypass (the `cf_clearance` cookie is set by the JS challenge).

> ⚠️ **Concern**: Cookies from all sources are stored in the same cookie jar. A malicious source could theoretically read cookies set by other sources. In practice, the cookie jar is scoped by domain (OkHttp enforces this), so this isn't a real issue.

### 3.4 WebView
`WebViewActivity` and the CloudFlare bypass interceptor both enable:
- `javaScriptEnabled = true`
- `domStorageEnabled = true`
- `databaseEnabled = true`
- `thirdPartyCookiesEnabled = true`

This is required for CloudFlare JS challenges to work, but means any JS on a scraped page runs in the WebView. Since users only open the WebView manually (to bypass CloudFlare), this is an acceptable tradeoff.

## 4. CloudFlare Bypass

### 4.1 Mechanism
`CloudFareVerificationInterceptor` (in `networking/`) detects CloudFlare responses (status 403/503 + `Server: cloudflare-nginx` or `cloudflare`) and:

1. Uses a `ReentrantLock` to serialize bypass attempts.
2. Removes old `cf_clearance` cookie from `CookieManager`.
3. Spawns a `WebView` on `Dispatchers.Main` with JS enabled, DOM storage, database, third-party cookies.
4. Loads the URL with request headers.
5. `delay(20.seconds)` to allow the CloudFlare JS challenge to complete.
6. Stops/destroys the WebView.
7. Re-runs the request (now with the `cf_clearance` cookie).
8. Throws `CloudfareVerificationBypassFailedException` if still blocked.

### 4.2 Security implications
- The WebView runs CloudFlare's JS challenge code, which is benign (it's just proving the client is a real browser).
- The `cf_clearance` cookie is stored in the system cookie jar and sent with subsequent requests to the same domain.
- The 20-second delay blocks the calling OkHttp thread — acceptable given the lock-based serialization but worth noting.

### 4.3 User-initiated bypass
If the automatic bypass fails, users can manually open the page in `WebViewActivity` (the in-app browser). The `WebViewClient.onPageFinished` callback shows a "Cookies saved" toast — the cookies are then available to the OkHttp client for subsequent scrapes.

## 5. Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

| Permission | Why it's needed |
|---|---|
| `INTERNET` | Scraping sources, Gemini API calls, GitHub update check |
| `ACCESS_NETWORK_STATE` | WorkManager `NetworkType.CONNECTED` constraint |
| `FOREGROUND_SERVICE` | Backup/restore/import/library-update/TTS services |
| `FOREGROUND_SERVICE_DATA_SYNC` | Backup, restore, EPUB import, library updates (Android 14+ requires per-type) |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Reader narrator TTS (Android 14+ requires per-type) |
| `POST_NOTIFICATIONS` | Library update notifications, new chapter notifications, TTS media notification (Android 13+ requires runtime permission) |

No dangerous permissions beyond `POST_NOTIFICATIONS` (which is runtime-requested in `MainActivity.onCreate` for Android 13+).

## 6. Backup File Security

### 6.1 What's in a backup
```
noveldokusha_backup_yyyy-MM-dd_HH-mm.zip
├── database.sqlite3              # Room SQLite file (NOT encrypted)
└── books/                        # Only if "Save images" was checked
    └── <bookFolderName>/
        └── <imageFiles>
```

The SQLite file contains:
- `Book` table — titles, URLs, cover URLs, descriptions, reading positions
- `Chapter` table — titles, URLs, read state, positions
- `ChapterBody` table — full chapter text

**No passwords or API keys** are in the backup — `SharedPreferences` (where the Gemini key lives) is NOT included.

### 6.2 Implications
- Backups are **not encrypted**. Anyone with the zip file can read all library data and chapter text.
- Users should store backups in a secure location (e.g. encrypted cloud storage).
- The backup format is stable across app versions (it's just a SQLite file + image files).

## 7. Logging

### 7.1 Timber
The app uses Timber for logging. In debug builds, `Timber.DebugTree()` is planted (logs to Logcat). In release builds, **no tree is planted** — logs are no-ops.

### 7.2 ProGuard stripping
`proguard-rules.pro` strips `Log.d/v/i` and `Timber.d/v/i` calls in release builds. Only `w` (warning) and `e` (error) calls remain.

### 7.3 What's NOT logged
- Gemini API key (never appears in logs)
- Full chapter text (would be too verbose)
- User's library contents

### 7.4 What IS logged (debug only)
- HTTP request/response bodies (via `HttpLoggingInterceptor` with `Level.BODY`)
- Source scraping errors
- CloudFlare bypass attempts

## 8. Update Mechanism Security

### 8.1 App update checker
`AppRemoteRepository.getLastAppVersion()` fetches `https://api.github.com/repos/nanihadesuka/NovelDokusha/releases/latest` and parses the `tag_name` and `html_url`.

> ⚠️ **Note**: This points to the **original** repo, not the fork. Fork users won't get notified of fork updates. The URL is hardcoded in `AppRemoteRepository.kt`.

### 8.2 Update installation
The update notification's "Download" button opens the release URL in the system browser — the app does NOT download or install APKs itself. Users must manually download and install. This is safe (no auto-install attack surface) but less convenient.

## 9. See Also

- `CORE_ENGINE.md` — `AppPreferences`, `NetworkClient`, interceptors
- `TRANSLATION_SYSTEM.md` — Gemini API key usage
- `Build-fixes.md` — signing workflow fixes
