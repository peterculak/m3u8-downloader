package com.ta3.downloader
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

class MockDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = java.net.URL(request.url())
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = request.httpMethod()
        request.headers().forEach { (k, v) -> v.forEach { conn.addRequestProperty(k, it) } }
        val code = conn.responseCode
        val msg = conn.responseMessage
        val headers = conn.headerFields.mapValues { it.value.toList() }
        val body = if (code < 400) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)
        return Response(code, msg, headers, String(body), request.url())
    }
}

class NewPipeTest {
    @Test
    fun testExtraction() {
        NewPipe.init(MockDownloader())
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://youtu.be/N4oRw8HmqGw")
        val audio = info.audioStreams.maxByOrNull { it.averageBitrate }
        println("URL: " + audio?.content)
        val fields = audio?.javaClass?.declaredFields
        fields?.forEach {
            it.isAccessible = true
            println("FIELD: " + it.name + " = " + it.get(audio))
        }
    }
}
