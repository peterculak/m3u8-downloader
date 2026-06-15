package com.ta3.downloader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Scraper for https://prehraj.to/
 *
 * Flow:
 *  1. login()       – POST credentials, store session cookie
 *  2. search(q)     – GET /hledej/<query>, parse results
 *  3. resolveVideoUrl(pageUrl) – GET movie page with cookie, extract direct MP4 URL
 */
object PrehrajScraper {

    private const val TAG = "PrehrajScraper"
    private const val BASE = "https://prehraj.to"
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // In-memory session cookie (set after login, cleared on login failure)
    @Volatile
    var sessionCookie: String = ""
        private set

    val isLoggedIn: Boolean get() = sessionCookie.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(CookieJar.NO_COOKIES)   // we manage cookies manually
        .build()

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private fun get(url: String, withSession: Boolean = false): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "sk,cs;q=0.9,en;q=0.8")
        if (withSession && sessionCookie.isNotEmpty()) {
            builder.header("Cookie", sessionCookie)
        }
        return client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            resp.body?.string() ?: ""
        }
    }

    private fun post(url: String, formBody: FormBody, referer: String = BASE): Pair<String, Headers> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "sk,cs;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            body to resp.headers
        }
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    /**
     * Perform a login to prehraj.to:
     *  1. GET /prihlasit to harvest the CSRF token
     *  2. POST credentials + CSRF token
     *  3. Store the session cookie
     *
     * Throws on network error or wrong credentials (no session cookie returned).
     */
    suspend fun login(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "Logging in as $email")
        sessionCookie = ""

        // Step 1: POST login form to the Nette framework endpoint
        val formBuilder = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .add("_do", "loginDialog-login-loginForm-submit")
            .add("login", "Přihlásit se")

        val loginReq = Request.Builder()
            .url("$BASE/?frm=loginDialog-login-loginForm")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "sk,cs;q=0.9,en;q=0.8")
            .post(formBuilder.build())
            .build()

        // Use a client that does NOT follow redirects so we can read Set-Cookie
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val cookies = mutableListOf<String>()

        noRedirectClient.newCall(loginReq).execute().use { resp ->
            Log.d(TAG, "Login response: ${resp.code}")
            // Collect all Set-Cookie values
            resp.headers.values("Set-Cookie").forEach { cookies.add(it) }

            // If there was a redirect, follow it manually to collect further cookies
            var location = resp.header("Location")
            var prevCookies = cookies.joinToString("; ") { it.substringBefore(";") }
            var hops = 0
            while (location != null && hops < 5) {
                hops++
                val redirectUrl = if (location.startsWith("http")) location else "$BASE$location"
                val redirectReq = Request.Builder()
                    .url(redirectUrl)
                    .header("User-Agent", UA)
                    .header("Cookie", prevCookies)
                    .get()
                    .build()
                noRedirectClient.newCall(redirectReq).execute().use { r ->
                    r.headers.values("Set-Cookie").forEach { cookies.add(it) }
                    location = r.header("Location")
                    prevCookies = cookies.joinToString("; ") { it.substringBefore(";") }
                }
            }
        }

        if (cookies.isEmpty()) {
            throw Exception("Login failed: no cookies returned. Check credentials.")
        }

        // Build a cookie string from all Set-Cookie values (name=value pairs only)
        val cookieString = cookies.joinToString("; ") { it.substringBefore(";") }
        sessionCookie = cookieString
        Log.d(TAG, "Session cookie stored (${cookieString.length} chars)")
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Search prehraj.to for [query].
     * Returns a list of results. Login is not required for search.
     */
    suspend fun search(query: String): List<PrehrajMovie> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "$BASE/hledej/$encoded"
        Log.d(TAG, "Searching: $url")

        val html = get(url, withSession = isLoggedIn)
        val doc = Jsoup.parse(html)
        val results = mutableListOf<PrehrajMovie>()

        // Each result is inside: div.video-wrapper > div > a.video--link
        // Title is in the `title` attribute of the link, or h3.video__title inside it
        // Thumbnail is the first img.thumb1 (non-lazy-loaded) inside the link
        doc.select("div.video-wrapper").forEach { wrapper ->
            val link  = wrapper.select("a.video--link").firstOrNull() ?: return@forEach
            val href  = link.attr("href").takeIf { it.isNotEmpty() } ?: return@forEach
            val title = link.attr("title").trim().ifEmpty {
                link.select("h3.video__title, .video__title").text().trim()
            }
            if (title.isEmpty()) return@forEach

            val pageUrl = if (href.startsWith("http")) href else "$BASE$href"

            // First real thumbnail (not lazy-loaded)
            var thumb = link.select("img.thumb1").attr("src")
            if (thumb.isEmpty()) thumb = link.select("img.thumb").firstOrNull()?.attr("src") ?: ""
            if (thumb.startsWith("/")) thumb = "$BASE$thumb"

            results.add(PrehrajMovie(title = title, pageUrl = pageUrl, thumbnailUrl = thumb))
        }

        Log.d(TAG, "Search returned ${results.size} results")
        results.distinctBy { it.pageUrl }
    }

    // ─── Resolve video URL ────────────────────────────────────────────────────

    /**
     * Fetch the movie detail page and extract the direct video URL.
     * Requires an active session (login first).
     *
     * The video URL appears as:
     *  - A <video src="..."> or <source src="..."> tag, OR
     *  - A JSON payload: "file":"<url>" or "src":"<url>"
     */
    suspend fun resolveVideoUrl(pageUrl: String): String = withContext(Dispatchers.IO) {
        if (!isLoggedIn) throw Exception("Not logged in to prehraj.to")

        Log.d(TAG, "Resolving video URL from: $pageUrl")
        val rawHtml = get(pageUrl, withSession = true)
        // Decode HTML entities so &amp; in CDN URLs doesn't strip the token params
        val html = rawHtml.replace("&amp;", "&")

        // Strategy 1: <video src="..."> or <source src="...">
        val doc = Jsoup.parse(html)
        val videoTag = doc.select("video[src], source[src]").firstOrNull()
        if (videoTag != null) {
            val src = videoTag.attr("src")
            if (src.isNotEmpty() && (src.contains(".mp4") || src.contains(".mkv") || src.contains("cdn"))) {
                Log.d(TAG, "Found video src in tag: ${src.take(80)}")
                return@withContext src
            }
        }

        // Strategy 2: CDN URL with token params anywhere in page source (most reliable)
        val cdnRegex = Regex("""https://[a-z0-9\-]+\.(?:premiumcdn|cdn|storage|stream)[^\s"'<>]+\.mp4[^\s"'<>]*""")
        val cdnMatch = cdnRegex.find(html)
        if (cdnMatch != null) {
            val url = cdnMatch.value.replace("\\/", "/")
            Log.d(TAG, "Found CDN URL: ${url.take(80)}")
            return@withContext url
        }

        // Strategy 3: Any mp4 URL
        val mp4Regex = Regex("""https://[^\s"'<>]+\.mp4[^\s"'<>]*""")
        val mp4Match = mp4Regex.find(html)
        if (mp4Match != null) {
            val url = mp4Match.value.replace("\\/", "/")
            Log.d(TAG, "Found MP4 URL: ${url.take(80)}")
            return@withContext url
        }

        throw Exception("Could not find video URL on page: $pageUrl")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun isMovieDetailUrl(url: String): Boolean {
        // Match: https://prehraj.to/<slug>/<hex-like-id>
        return url.startsWith("$BASE/") &&
               url.removePrefix("$BASE/").count { it == '/' } == 1 &&
               url.length > BASE.length + 5
    }

    fun clearSession() {
        sessionCookie = ""
    }
}
