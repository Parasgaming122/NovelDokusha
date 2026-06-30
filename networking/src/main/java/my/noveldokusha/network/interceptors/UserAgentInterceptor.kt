package my.noveldokusha.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response


internal class UserAgentInterceptor : Interceptor {

    companion object {
        /**
         * Modern Chrome User-Agent string.
         *
         * The previous value ("Mozilla/5.0 (Windows NT 6.3; WOW64)") was a Windows 8.1-era
         * UA that an increasing number of sites (Cloudflare, Royal Road, Novel Updates, etc.)
         * silently classify as a bot. Using a recent Chrome desktop UA dramatically reduces
         * false-positive bot challenges on otherwise unprotected endpoints.
         *
         * Updated: 2026-06 to Chrome 120 desktop UA.
         */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Browser-like default headers added to every outbound request when missing.
         * Sites that inspect Accept-Language / Sec-Fetch-* flags treat requests
         * missing them as suspicious; backfilling improves compatibility with
         * Cloudflare-protected sites.
         */
        private val DEFAULT_HEADERS = listOf(
            "accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,*/*;q=0.8",
            "accept-language" to "en-US,en;q=0.9",
            "accept-encoding" to "gzip, deflate, br",
            "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "none",
            "sec-fetch-user" to "?1",
            "upgrade-insecure-requests" to "1",
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val requestBuilder = originalRequest.newBuilder()

        // Set UA if missing
        if (originalRequest.header("User-Agent").isNullOrBlank()) {
            requestBuilder.removeHeader("User-Agent")
            requestBuilder.addHeader("User-Agent", DEFAULT_USER_AGENT)
        }

        // Backfill browser-like headers if not already set
        for ((name, value) in DEFAULT_HEADERS) {
            if (originalRequest.header(name).isNullOrBlank()) {
                requestBuilder.addHeader(name, value)
            }
        }

        return chain.proceed(requestBuilder.build())
    }
}
