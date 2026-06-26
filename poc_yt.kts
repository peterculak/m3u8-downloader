import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.text.SimpleDateFormat

val html = URL("https://www.youtube.com/@BraňoZávodskýNaživo/streams").readText()

fun extractYtInitialDataJson(html: String): String? {
    val startMarker = "var ytInitialData = "
    val startIndex = html.indexOf(startMarker)
    if (startIndex == -1) return null
    val jsonStart = startIndex + startMarker.length
    val endMarker = ";</script>"
    val endIndex = html.indexOf(endMarker, jsonStart)
    if (endIndex == -1) return null
    var raw = html.substring(jsonStart, endIndex).trim()
    return raw
}

val jsonString = extractYtInitialDataJson(html)
if (jsonString != null) {
    println("ytInitialData extracted, length: ${jsonString.length}")
    
    val timePattern = Regex(""""text"\s*:\s*"([^"]*(ago|pred|hodin|min|sek|dň|týžd|mesiac|rok|Stream|Premi)[^"]*)"""", RegexOption.IGNORE_CASE)
    val matches = timePattern.findAll(jsonString).take(10).toList()
    println("Matches in JSON: " + matches.map { it.groupValues[1] })
} else {
    println("ytInitialData not found!")
}
