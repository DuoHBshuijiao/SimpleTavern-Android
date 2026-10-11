package com.simpletavern.core.api

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.Settings
import com.simpletavern.core.model.*
import java.time.*
import java.time.format.DateTimeParseException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Actual device providers only; no permission prompts or fabricated empty results. */
internal class AssistantDeviceTools(private val context: Context) {
    fun permissions(): DeviceToolPermissions {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return DeviceToolPermissions(has(Manifest.permission.READ_CALENDAR), has(Manifest.permission.WRITE_CALENDAR),
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT && has(Manifest.permission.PACKAGE_USAGE_STATS),
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}", Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun error(code: String, message: String) = buildJsonObject { put("error", code); put("message", message) }

    suspend fun dispatch(name: String, args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "calendar_query" -> calendarQuery(args)
                "calendar_create" -> calendarCreate(args)
                "get_screen_time" -> screenTime(args)
                else -> throw StError.Unsupported("unknown device tool")
            }
        } catch (_: SecurityException) {
            error("NO_PERMISSION", "Android 拒绝访问设备服务；请用户检查权限。未自动申请权限或启动设置页。")
        }
    }

    private fun time(raw: String, zone: ZoneId): ZonedDateTime {
        val value = raw.trim()
        value.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
        try { return OffsetDateTime.parse(value).atZoneSameInstant(zone) } catch (_: DateTimeParseException) { }
        try { return Instant.parse(value).atZone(zone) } catch (_: DateTimeParseException) { }
        try { return LocalDateTime.parse(value).atZone(zone) } catch (_: DateTimeParseException) { }
        try { return LocalDate.parse(value).atStartOfDay(zone) } catch (_: DateTimeParseException) { }
        throw StError.Validation("time must be an ISO date/date-time or epoch milliseconds")
    }
    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content
    private fun JsonObject.limit(key: String, default: Int, max: Int) = get(key)?.jsonPrimitive?.longOrNull?.coerceIn(1, max.toLong())?.toInt() ?: default

    private suspend fun <T> query(uri: Uri, projection: Array<String>, selection: String? = null,
        arguments: Array<String>? = null, sort: String? = null, read: suspend (Cursor) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel() } }
        try {
            currentCoroutineContext().ensureActive()
            val cursor = context.contentResolver.query(uri, projection, selection, arguments, sort, signal)
                ?: throw StError.Unsupported("device calendar provider returned no cursor")
            cursor.use { read(it) }
        } finally { watch.cancel() }
    }

    private suspend fun calendarQuery(args: JsonObject): JsonObject {
        if (!has(Manifest.permission.READ_CALENDAR)) return error("NO_PERMISSION", "请用户授予日历读取权限后发起新调用。")
        val now = ZonedDateTime.now()
        val zone = now.zone
        val range = args.text("range") ?: "today"
        if (args.text("begin") == null && range !in setOf("today", "week", "month")) throw StError.Validation("calendar range must be today/week/month")
        val start = args.text("begin")?.let { time(it, zone) } ?: when (range) {
            "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
            "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            else -> now.toLocalDate().atStartOfDay(zone)
        }
        val end = args.text("end")?.let { time(it, zone) } ?: when (range) {
            "week" -> start.plusDays(7)
            "month" -> start.plusMonths(1)
            else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
        }
        if (!start.isBefore(end)) throw StError.Validation("calendar begin must be earlier than end")
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toInstant().toEpochMilli().toString()).appendPath(end.toInstant().toEpochMilli().toString()).build()
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val limit = args.limit("limit", 20, 100)
        val keyword = args.text("query")
        val events = query(uri, projection, sort = "${CalendarContract.Instances.BEGIN} ASC") { cursor ->
            val found = mutableListOf<JsonElement>()
            while (found.size < limit && cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val title = cursor.getString(1).orEmpty()
                if (keyword != null && !title.contains(keyword, ignoreCase = true)) continue
                val beginMs = cursor.getLong(4)
                val endMs = cursor.getLong(5)
                if (beginMs >= end.toInstant().toEpochMilli() || endMs <= start.toInstant().toEpochMilli()) continue
                val allDay = cursor.getInt(6) == 1
                fun display(ms: Long) = if (allDay) Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    else Instant.ofEpochMilli(ms).atZone(zone).withNano(0).toString()
                found += buildJsonObject {
                    put("id", cursor.getLong(0)); put("title", title); put("description", cursor.getString(2).orEmpty())
                    put("location", cursor.getString(3).orEmpty()); put("start", display(beginMs)); put("end", display(endMs))
                    put("all_day", allDay); put("calendar", cursor.getString(7).orEmpty())
                }
            }
            JsonArray(found)
        }
        return buildJsonObject { put("range_start", start.withNano(0).toString()); put("range_end", end.withNano(0).toString())
            put("count", events.size); put("events", events) }
    }

    private suspend fun calendarCreate(args: JsonObject): JsonObject {
        if (!has(Manifest.permission.READ_CALENDAR) || !has(Manifest.permission.WRITE_CALENDAR))
            return error("NO_PERMISSION", "请用户授予日历读取和写入权限后发起新调用。")
        val title = args.text("title")!!.takeIf { it.isNotBlank() } ?: throw StError.Validation("calendar title must not be blank")
        val zone = ZoneId.systemDefault()
        val start = time(args.text("start")!!, zone)
        val allDay = args["all_day"]?.jsonPrimitive?.booleanOrNull ?: false
        val end = args.text("end")?.let { time(it, zone) } ?: if (allDay) start.toLocalDate().plusDays(1).atStartOfDay(zone) else start.plusHours(1)
        if (!start.isBefore(end) || allDay && !start.toLocalDate().isBefore(end.toLocalDate()))
            throw StError.Validation("calendar end must be later than start; all-day end date is exclusive")
        val calendar = query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.VISIBLE} DESC, ${CalendarContract.Calendars._ID} ASC") {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return error("NO_CALENDAR", "设备没有可写入且启用的日历；未创建日历账户。")
        val beginMs = if (allDay) start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.toInstant().toEpochMilli()
        val endMs = if (allDay) end.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else end.toInstant().toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar); put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, args.text("description").orEmpty()); put(CalendarContract.Events.EVENT_LOCATION, args.text("location").orEmpty())
            put(CalendarContract.Events.DTSTART, beginMs); put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id); put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }
        currentCoroutineContext().ensureActive()
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return error("INSERT_FAILED", "设备日历服务未返回创建结果；不自动重试。")
        return buildJsonObject {
            put("success", true); put("event_id", ContentUris.parseId(uri)); put("title", title)
            put("start", if (allDay) start.toLocalDate().toString() else start.withNano(0).toString())
            put("end", if (allDay) end.toLocalDate().toString() else end.withNano(0).toString())
            put("all_day", allDay); put("location", args.text("location").orEmpty())
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun screenTime(args: JsonObject): JsonObject {
        val access = permissions()
        if (!access.usageAccess) return error("NO_PERMISSION", "需要用户在系统设置中授予 Usage access；未自动打开设置页。")
        if (!access.userUnlocked) return error("USER_LOCKED", "用户尚未完成开机后的解锁，系统使用事件不可用。")
        val now = ZonedDateTime.now()
        val range = args.text("range") ?: "today"
        if (args.text("begin") == null && range !in setOf("today", "week")) throw StError.Validation("screen time range must be today/week")
        val start = args.text("begin")?.let { time(it, now.zone) }
            ?: if (range == "week") now.minusDays(7) else now.toLocalDate().atStartOfDay(now.zone)
        val requestedEnd = args.text("end")?.let { time(it, now.zone) } ?: now
        val end = if (requestedEnd.isAfter(now)) now else requestedEnd
        if (!start.isBefore(end)) throw StError.Validation("screen time begin must precede available end")
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return error("UNAVAILABLE", "设备没有使用事件服务。")
        val startMs = start.toInstant().toEpochMilli()
        if (startMs < 0) throw StError.Validation("usage events require an epoch time at or after 1970")
        val endMs = end.toInstant().toEpochMilli()
        val events = manager.queryEvents(maxOf(0, startMs - 12 * 60 * 60 * 1000L), endMs)
            ?: return error("UNAVAILABLE", "系统未提供使用事件；不将不可用误报为零使用时间。")
        val excluded = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
        val totals = linkedMapOf<String, Long>()
        var current: String? = null
        var from = 0L
        fun settle(until: Long) {
            val pkg = current
            current = null
            if (pkg == null || pkg in excluded) return
            val duration = minOf(until, endMs) - maxOf(from, startMs)
            if (duration > 0) totals[pkg] = (totals[pkg] ?: 0) + duration
        }
        val event = UsageEvents.Event()
        var eventCount = 0L
        while (events.hasNextEvent()) {
            currentCoroutineContext().ensureActive()
            events.getNextEvent(event)
            eventCount++
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (event.packageName != current) {
                    settle(event.timeStamp); current = event.packageName; from = event.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName == current) settle(event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> settle(event.timeStamp)
            }
        }
        settle(endMs)
        val sorted = totals.entries.sortedByDescending { it.value }
        val total = sorted.sumOf { it.value }
        return buildJsonObject {
            put("range", if (args.text("begin") != null || args.text("end") != null) "custom" else range)
            put("start", start.withNano(0).toString()); put("end", end.withNano(0).toString())
            put("total_ms", total); put("total_minutes", total / 60_000); put("observed_events", eventCount)
            put("note", "仅按系统保留的事件计算；系统通常只保留数天。采用单一前台口径，排除桌面，不保证完整历史或多窗口计时。")
            put("apps", buildJsonArray {
                for ((pkg, ms) in sorted.take(args.limit("top", 10, 50))) {
                    currentCoroutineContext().ensureActive()
                    val label = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                        catch (_: PackageManager.NameNotFoundException) { pkg }
                    add(buildJsonObject { put("package", pkg); put("app_name", label); put("total_ms", ms); put("total_minutes", ms / 60_000) })
                }
            })
        }
    }

    companion object {
        val names = setOf("calendar_query", "calendar_create", "get_screen_time")
        val definitions = JsonArray(names.map { name -> buildJsonObject {
            put("type", "function"); put("function", buildJsonObject {
                put("name", name)
                put("description", when (name) {
                    "calendar_query" -> "读取设备实际日历事件（含重复实例），按标题关键词筛选。range=today/week/month；日期/无偏移时间按设备时区解释，或提供 ISO 带偏移时间/epoch毫秒。需要用户授予日历读取权限，不自动申请。"
                    "calendar_create" -> "创建实际设备日历事件，必须由用户逐项确认。提供 title/start；end 默认一小时后，全天默认下一天且结束日期不包含。不会删除或修改旧事件。需要日历读写权限，不自动申请。"
                    else -> "按设备使用事件计算单一前台应用时间，排除桌面。range=today/week或begin/end，top默认10最多50。时间格式同日历；需用户授予 Usage access，系统仅保留数天，不自动启动设置页。"
                })
                put("parameters", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        val fields = if (name == "calendar_create") listOf("title", "start", "end", "description", "location")
                            else listOf("begin", "end", "range") + if (name == "calendar_query") listOf("query") else emptyList()
                        for (key in fields) put(key, buildJsonObject { put("type", "string") })
                        if (name == "calendar_create") put("all_day", buildJsonObject { put("type", "boolean") })
                        else put(if (name == "calendar_query") "limit" else "top", buildJsonObject { put("type", "integer") })
                    })
                    put("required", JsonArray((if (name == "calendar_create") listOf("title", "start") else emptyList()).map(::JsonPrimitive)))
                })
            })
        } })
    }
}
