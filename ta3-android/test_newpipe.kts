import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.downloader.Downloader

// Setup mock downloader
class MockDownloader : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
        val url = java.net.URL(request.url())
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = request.httpMethod()
        request.headers().forEach { (k, v) -> v.forEach { conn.addRequestProperty(k, it) } }
        val code = conn.responseCode
        val msg = conn.responseMessage
        val headers = conn.headerFields.mapValues { it.value.toList() }
        val body = if (code < 400) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)
        return org.schabi.newpipe.extractor.downloader.Response(code, msg, headers, String(body), request.url())
    }
}
NewPipe.init(MockDownloader())

try {
    val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=jNQXAC9IVRw")
    val audio = info.audioStreams.maxByOrNull { it.averageBitrate }
    println("URL: " + audio?.content)
} catch (e: Exception) {
    e.printStackTrace()
}
