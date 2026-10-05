package com.nightguard.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 动作/恢复模式的常量。
 */
/**
 * 一条触发规则：监听时段内，命中“指定应用的消息”或“指定联系人的来电”时延迟响铃。
 * （原“动作一：关闭勿扰”已移除——ColorOS 15 强制每次人工确认，夜间无人确认无法生效，
 * 详见 技术实现.md §5.1。）
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新规则",
    val enabled: Boolean = true,
    /** 监听窗口开始，分钟数（0..1439），如 22*60 */
    val startMinutes: Int = 22 * 60,
    /** 监听窗口结束，分钟数，如 7*60；start==end 视为全天 */
    val endMinutes: Int = 7 * 60,
    /** 生效日（ISO：1=周一 .. 7=周日） */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    // ---- 来源：消息 ----
    val watchMessages: Boolean = true,
    val packages: Set<String> = emptySet(),
    /** 可选：消息内容（标题/正文拼接）需包含的关键词，空 = 任意消息 */
    val senderKeyword: String = "",
    // ---- 来源：来电 ----
    val watchCalls: Boolean = false,
    /** 联系人号码（归一化数字） */
    val contacts: Set<String> = emptySet(),
    val contactNames: Map<String, String> = emptyMap(),
    // ---- 动作：延迟响铃 ----
    val delayedAlarm: Boolean = true,
    val alarmDelayMinutes: Int = 2,
    /** 同规则两次触发的最小间隔（秒），防连发消息刷爆 */
    val cooldownSeconds: Int = 120,
) {
    fun describeWindow(): String = when {
        startMinutes == endMinutes -> "全天"
        else -> "%02d:%02d-%02d:%02d".format(startMinutes / 60, startMinutes % 60, endMinutes / 60, endMinutes % 60) +
                if (startMinutes > endMinutes) " 跨天" else ""
    }

    /** 星期摘要：空视为每天，让用户在卡片上也能确认 */
    fun describeDays(): String {
        val labels = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
        val ds = days.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) }
        return if (ds.size == 7) "每天"
        else ds.sorted().joinToString("、") { labels[it] ?: "" } + if (days.isEmpty()) "（未选视为每天）" else ""
    }

    fun describeSources(): String {
        val parts = mutableListOf<String>()
        if (watchMessages) {
            val app = when {
                packages.isEmpty() -> "未选应用"
                packages.size == 1 -> packages.first().substringAfterLast('.')
                else -> "${packages.size}个应用"
            }
            val kw = if (senderKeyword.isNotBlank()) "/含“$senderKeyword”" else ""
            parts.add("消息·$app$kw")
        }
        if (watchCalls) {
            val c = if (contacts.isEmpty()) "未选联系人" else "${contacts.size}个联系人"
            parts.add("来电·$c")
        }
        return if (parts.isEmpty()) "未配置来源" else parts.joinToString(" 或 ")
    }

    fun describeActions(): String =
        if (delayedAlarm) "${alarmDelayMinutes}分钟后响铃" else "无动作"

    fun isConfigured(): Boolean =
        (watchMessages && packages.isNotEmpty()) || (watchCalls && contacts.isNotEmpty())
}

data class LogEntry(
    val time: Long,
    val rule: String,
    val source: String,
    val actions: String,
)

object RuleJson {

    fun rulesToJson(rules: List<Rule>): String =
        JSONArray().apply { rules.forEach { put(ruleToJson(it)) } }.toString()

    fun rulesFromJson(s: String): List<Rule> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).map { ruleFromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun ruleToJson(r: Rule): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("enabled", r.enabled)
        put("start", r.startMinutes)
        put("end", r.endMinutes)
        put("days", JSONArray(r.days.sorted()))
        put("watchMessages", r.watchMessages)
        put("packages", JSONArray(r.packages.sorted()))
        put("keyword", r.senderKeyword)
        put("watchCalls", r.watchCalls)
        put("contacts", JSONArray(r.contacts.sorted()))
        put("contactNames", JSONObject().apply { r.contactNames.forEach { (k, v) -> put(k, v) } })
        put("delayedAlarm", r.delayedAlarm)
        put("alarmDelayMinutes", r.alarmDelayMinutes)
        put("cooldownSeconds", r.cooldownSeconds)
    }

    fun ruleFromJson(o: JSONObject): Rule = Rule(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "规则"),
        enabled = o.optBoolean("enabled", true),
        startMinutes = o.optInt("start", 22 * 60),
        endMinutes = o.optInt("end", 7 * 60),
        days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }
            ?: setOf(1, 2, 3, 4, 5, 6, 7),
        watchMessages = o.optBoolean("watchMessages", true),
        packages = o.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            ?: emptySet(),
        senderKeyword = o.optString("keyword", ""),
        watchCalls = o.optBoolean("watchCalls", false),
        contacts = o.optJSONArray("contacts")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            ?: emptySet(),
        contactNames = o.optJSONObject("contactNames")?.let { jo ->
            jo.keys().asSequence().map { it to jo.optString(it) }.toMap()
        } ?: emptyMap(),
        delayedAlarm = o.optBoolean("delayedAlarm", false),
        alarmDelayMinutes = o.optInt("alarmDelayMinutes", 2),
        cooldownSeconds = o.optInt("cooldownSeconds", 120),
    )

    fun logToJson(list: List<LogEntry>): String =
        JSONArray().apply { list.forEach { e ->
            put(JSONObject().apply {
                put("t", e.time); put("r", e.rule); put("s", e.source); put("a", e.actions)
            })
        } }.toString()

    fun logFromJson(s: String): List<LogEntry> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.optLong("t"), o.optString("r"), o.optString("s"), o.optString("a"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun mapToJson(m: Map<String, Long>): String =
        JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()

    fun mapFromJson(s: String): Map<String, Long> = try {
        val o = JSONObject(s)
        o.keys().asSequence().map { it to o.optLong(it) }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 待响任务持久化编解码（DataStore 单键 JSON 数组） */
object PendingTaskJson {

    fun listToJson(list: List<PendingTask>): String =
        JSONArray().apply { list.forEach { t ->
            put(JSONObject().apply {
                put("taskId", t.taskId)
                put("ruleId", t.ruleId)
                put("fireAt", t.fireAt)
                put("source", t.source)
                put("createdAt", t.createdAt)
                put("fromCall", t.fromCall)
                put("status", t.status)
            })
        } }.toString()

    fun listFromJson(s: String): List<PendingTask> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val taskId = o.optString("taskId")
            val ruleId = o.optString("ruleId")
            if (taskId.isBlank() || ruleId.isBlank()) return@mapNotNull null
            PendingTask(
                taskId = taskId,
                ruleId = ruleId,
                fireAt = o.optLong("fireAt"),
                source = o.optString("source"),
                createdAt = o.optLong("createdAt"),
                fromCall = o.optBoolean("fromCall"),
                status = o.optString("status", TaskStatus.ARMED),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
