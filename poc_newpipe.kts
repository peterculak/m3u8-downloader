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
NewPipe.init(MockDownloader())

try {
    val info = StreamInfo.getInfo(ServiceList.YouTube, "https://youtu.be/N4oRw8HmqGw")
    val audio = info.audioStreams.maxByOrNull { it.averageBitrate }
    println("URL: " + audio?.content)
    println("Bitrate: " + audio?.averageBitrate)
    println("Format: " + audio?.format?.name)
    // Check fields using reflection!
    val fields = audio?.javaClass?.declaredFields
    fields?.forEach { field ->
        field.isAccessible = true
        println("FIELD: " + field.name + " = " + field.get(audio))
    }
} catch (e: Exception) {
    e.printStackTrace()
}
