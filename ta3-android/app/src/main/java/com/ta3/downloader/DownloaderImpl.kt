package com.ta3.downloader

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based implementation of NewPipeExtractor's Downloader interface.
 * Required to initialize NewPipe.init() before any stream extraction.
 */
class DownloaderImpl private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val bodyOrNull = if (httpMethod == "GET" || httpMethod == "HEAD") null
                         else dataToSend?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, bodyOrNull)
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478")

        headers.forEach { (name, values) ->
            if (name.lowercase() != "user-agent" && name.lowercase() != "cookie" && name.lowercase() != "accept-language") {
                values.forEach { value -> builder.addHeader(name, value) }
            }
        }

        val response = client.newCall(builder.build()).execute()
        val responseBody = response.body?.string() ?: ""
        val responseCode = response.code
        val responseMessage = response.message
        val responseHeaders = response.headers.toMultimap()
        val latestUrl = response.request.url.toString()
        response.close()

        return Response(responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
    }

    companion object {
        @Volatile
        private var instance: DownloaderImpl? = null

        fun getInstance(): DownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: DownloaderImpl().also { instance = it }
            }
        }
    }
}
