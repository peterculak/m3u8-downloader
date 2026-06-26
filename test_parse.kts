import java.util.Calendar
import java.text.SimpleDateFormat

fun parseRelativeDate(relativeStr: String): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    if (relativeStr.isBlank()) return "1970-01-01" 
    
    val cal = Calendar.getInstance()
    val text = relativeStr.lowercase()
    
    val num = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    
    if (text.contains("day") || text.contains("deň") || text.contains("dňom") ||
        text.contains("dňami") || text.contains("dní") || text.contains("dnem")) {
        cal.add(Calendar.DAY_OF_YEAR, -num)
    } else if (text.contains("week") || text.contains("týždeň") || text.contains("týždňami") ||
        text.contains("týždne") || text.contains("týždňom")) {
        cal.add(Calendar.DAY_OF_YEAR, -(num * 7))
    } else if (text.contains("month") || text.contains("mesiac") || text.contains("mesiacmi") || text.contains("mesiace")) {
        cal.add(Calendar.MONTH, -num)
    } else if (text.contains("year") || text.contains("rok") || text.contains("rokmi") || text.contains("roky")) {
        cal.add(Calendar.YEAR, -num)
    } else if (text.contains("hour") || text.contains("hodin") || text.contains("minute") ||
        text.contains("minút") || text.contains("second") || text.contains("sekund")) {
        // Keep as today
    } else if (text.contains("live") || text.contains("naživo") || text.contains("premiéra") ||
        text.contains("premiere") || text.contains("streamed") || text.contains("streamované")) {
        // Keep as today
    } else {
        return "1970-01-01"
    }
    
    return sdf.format(cal.time)
}

println("Streamed 2 days ago -> " + parseRelativeDate("Streamed 2 days ago"))
println("15k views -> " + parseRelativeDate("15k views"))
println("Streamed 1 month ago -> " + parseRelativeDate("Streamed 1 month ago"))
