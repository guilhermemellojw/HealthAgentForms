import java.util.*
import java.text.SimpleDateFormat

fun main() {
    val dateFilter: (String) -> Boolean = { dateStr ->
        try {
            val sdfPattern = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            val normalizedDate = dateStr.replace("/", "-")
            val date = sdfPattern.parse(normalizedDate)
            val time = date?.time ?: 0L
            val startT = sdfPattern.parse("01-05-2026")?.time ?: 0L
            val endT = sdfPattern.parse("07-05-2026")?.time ?: 0L
            time in startT..endT
        } catch (e: Exception) { false }
    }
    println(dateFilter("05-05-2026"))
}
