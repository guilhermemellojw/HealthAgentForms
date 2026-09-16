package com.antigravity.healthagent.utils

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DateUtils {

    val DASH_DATE: ThreadLocal<SimpleDateFormat> = threadLocal("dd-MM-yyyy")
    val SLASH_DATE: ThreadLocal<SimpleDateFormat> = threadLocal("dd/MM")
    val COMPACT_DATE: ThreadLocal<SimpleDateFormat> = threadLocal("yyyyMMdd")
    val TIMESTAMP_FILE: ThreadLocal<SimpleDateFormat> = threadLocal("yyyyMMdd_HHmm")
    val TIMESTAMP_FILE_EXTENDED: ThreadLocal<SimpleDateFormat> = threadLocal("dd-MM-yyyy_HH-mm")
    val SLASH_DATE_FULL: ThreadLocal<SimpleDateFormat> = threadLocal("dd/MM/yyyy")
    val DATE_TIME_SLASH: ThreadLocal<SimpleDateFormat> = threadLocal("dd/MM/yyyy HH:mm")
    val TIME_SLASH: ThreadLocal<SimpleDateFormat> = threadLocal("HH:mm")
    val DATE_TIME_FULL: ThreadLocal<SimpleDateFormat> = threadLocal("dd/MM/yy HH:mm")
    val TIMESTAMP_DB: ThreadLocal<SimpleDateFormat> = threadLocal("yyyy-MM-dd HH:mm:ss")
    val DASH_DATE_TIME_DB: ThreadLocal<SimpleDateFormat> = threadLocal("dd-MM-yyyy HH:mm:ss")

    private fun threadLocal(pattern: String): ThreadLocal<SimpleDateFormat> {
        return ThreadLocal.withInitial {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
            }
        }
    }

    fun formatDash(date: java.util.Date): String = DASH_DATE.get().format(date)
    fun formatSlash(date: java.util.Date): String = SLASH_DATE.get().format(date)
    fun formatCompact(date: java.util.Date): String = COMPACT_DATE.get().format(date)
    fun formatTimestampFile(date: java.util.Date): String = TIMESTAMP_FILE.get().format(date)
    fun formatTimestampFileExtended(date: java.util.Date): String = TIMESTAMP_FILE_EXTENDED.get().format(date)
    fun formatSlashFull(date: java.util.Date): String = SLASH_DATE_FULL.get().format(date)
    fun formatDateTimeSlash(date: java.util.Date): String = DATE_TIME_SLASH.get().format(date)
    fun formatTimeSlash(date: java.util.Date): String = TIME_SLASH.get().format(date)
    fun formatDateTimeFull(date: java.util.Date): String = DATE_TIME_FULL.get().format(date)
    fun formatTimestampDb(date: java.util.Date): String = TIMESTAMP_DB.get().format(date)
    fun formatDashDateTimeDb(date: java.util.Date): String = DASH_DATE_TIME_DB.get().format(date)

    fun parseDash(dateStr: String): java.util.Date? = try { DASH_DATE.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseCompact(dateStr: String): java.util.Date? = try { COMPACT_DATE.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseSlashFull(dateStr: String): java.util.Date? = try { SLASH_DATE_FULL.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseDateTimeSlash(dateStr: String): java.util.Date? = try { DATE_TIME_SLASH.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseDateTimeFull(dateStr: String): java.util.Date? = try { DATE_TIME_FULL.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseTimestampDb(dateStr: String): java.util.Date? = try { TIMESTAMP_DB.get().parse(dateStr) } catch (_: Exception) { null }
    fun parseDashDateTimeDb(dateStr: String): java.util.Date? = try { DASH_DATE_TIME_DB.get().parse(dateStr) } catch (_: Exception) { null }
}
