package my.noveldokusha.network.interceptors

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import my.noveldokusha.core.domain.WebViewCookieManagerInitializationFailedException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

private val ERROR_CODES = listOf(
    HttpsURLConnection.HTTP_FORBIDDEN /*403*/,
    HttpsURLConnection.HTTP_UNAVAILABLE /*503*/,
    HttpsURLConnection.HTTP_TOO_MANY_REQUESTS /*429*/,
)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare", "ddos-guard", "CF-RAY")

/**
 * Markers used to detect a Cloudflare JS challenge body, even when the response
 * status code is 200 (the "interstitial" challenge). Modern Cloudflare deployments
 * often return 200 with a small HTML payload containing these markers and a
 * JavaScript redirect — the previous interceptor only triggered on 403/503 and
 * would silently feed that challenge HTML to the Jsoup parser, breaking every
 * catalog source behind such protection.
 */
private val CHALLENGE_BODY_MARKERS = arrayOf(
    "Just a moment...",
    "cf-browser-verification",
    "cf_chl_opt",
    "cf-mitigated",
    "window._cf_chl_opt",
    "Checking your browser before accessing",
    "challenge-platform",
    "cdn-cgi/challenge-platform",
    "/cdn-cgi/challenge-platform/h/",
    "cf-turnstile",
    "Attention Required! | Cloudflare",
)

/**
 * If a CloudFare security verification redirection is detected, execute a
 * webView and retrieve the necessary headers.
 *
 * Two detection modes:
 *  1. Status-code based (legacy): 403/503/429 with `Server: cloudflare`.
 *  2. Body-marker based (new): 200 OK with a Cloudflare interstitial body
 *     ("Just a moment...", "cf-browser-verification", etc.). Many CF-protected
 *     novel sites now return 200 + a JS challenge rather than 403, which would
 *     otherwise leak the challenge HTML into the Jsoup parser.
 */
internal class CloudFareVerificationInterceptor(
    @ApplicationContext private val appContext: Context
) : Interceptor {

    private val lock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (isNotCloudFare(response)) {
            return response
        }

        return lock.withLock {
            try {
                val cookieManager = CookieManager.getInstance()
                    ?: throw WebViewCookieManagerInitializationFailedException()

                response.close()
                // Remove old cf_clearance from the cookie
                val cookie = cookieManager
                    .getCookie(request.url.toString())
                    ?.splitToSequence(";")
                    ?.map { it.split("=").map(String::trim) }
                    ?.filter { it[0] != "cf_clearance" }
                    ?.joinToString(";") { it.joinToString("=") }

                cookieManager.setCookie(request.url.toString(), cookie)

                runBlocking(Dispatchers.IO) {
                    resolveWithWebView(request, cookieManager)
                }

                val responseCloudfare = chain.proceed(request)

                if (!isNotCloudFare(responseCloudfare)) {
                    throw CloudfareVerificationBypassFailedException()
                }

                responseCloudfare
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e.message, e.cause)
            }
        }
    }

    /**
     * Returns true if the response is NOT a Cloudflare challenge.
     * Returns false (i.e. "is Cloudflare") when:
     *  - Status code is 403/503/429 AND Server header matches a CF marker, OR
     *  - Response body contains one of the known CF challenge markers
     *    (the response is peeked non-destructively so the caller can still
     *    read the body afterwards).
     */
    private fun isNotCloudFare(response: Response): Boolean {
        // Status-code path: 403/503/429 + CF server header
        val serverHeader = response.header("Server")
        val cfRay = response.header("CF-RAY")
        val isCfServer = serverHeader != null && SERVER_CHECK.any { serverHeader!!.contains(it, ignoreCase = true) }
        val isCfByHeaders = cfRay != null || isCfServer

        if (response.code in ERROR_CODES && isCfByHeaders) {
            return false
        }

        // Body-marker path: peek the first 8 KB of the body (non-destructively)
        // and check for known CF challenge markers.
        // Only inspect HTML responses (small enough to be a challenge page).
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return true
        }
        // Don't try to peek huge bodies — challenge pages are tiny (<16KB).
        // If the server reports a content-length and it's huge, skip the peek.
        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
        if (len in 1L..16_000L || len == -1L) {
            val peeked = response.peekBody(16_000L).string()
            // Quick short-circuit before scanning markers
            if (peeked.length < 32_000) {
                for (marker in CHALLENGE_BODY_MARKERS) {
                    if (peeked.contains(marker, ignoreCase = true)) {
                        // Body markers + Cloudflare-served = treat as challenge
                        if (isCfByHeaders || marker == "Just a moment..." ||
                            marker == "cf-browser-verification" ||
                            marker == "challenge-platform"
                        ) {
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        request: Request,
        cookieManager: CookieManager
    ) = withContext(Dispatchers.Default) {
        val headers = request
            .headers
            .toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }

        WebSettings.getDefaultUserAgent(appContext)

        withContext(Dispatchers.Main) {
            val webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = request.header("user-agent")
                    ?: UserAgentInterceptor.DEFAULT_USER_AGENT

                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {}
            }
            webView.loadUrl(request.url.toString(), headers)
            // This will won't be often executed so no need for eager delay exit
            delay(20.seconds)
            webView.stopLoading()
            webView.destroy()
        }
    }
}
