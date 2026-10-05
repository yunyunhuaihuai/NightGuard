package com.nightguard.app.logic

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.LogKind
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import java.util.Calendar

/**
 * 规则引擎：窗口判断、来源匹配、触发流水线。
 * 全部纯内存判断（微秒级），由系统推送事件驱动，零轮询。
 */
object RuleEngine {
    private const val TAG = "NightGuard"


    // ---------- 时间窗口 ----------

    /** Calendar.DAY_OF_WEEK(1=周日..7=周六) 转 ISO 日（1=周一..7=周日） */
    fun isoDay(cal: Calendar): Int {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return ((d + 5) % 7) + 1
    }

    fun prevIsoDay(cal: Calendar): Int {
        val i = isoDay(cal)
        return if (i == 1) 7 else i - 1
    }

    /** 当前时刻是否落在规则的监听窗口内（支持跨午夜；星期留空视为每天） */
    fun inWindow(rule: Rule, cal: Calendar = Calendar.getInstance()): Boolean {
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val day = isoDay(cal)
        val prev = prevIsoDay(cal)
        val days = rule.days.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) }
        return when {
            rule.startMinutes == rule.endMinutes -> days.contains(day)
            rule.startMinutes < rule.endMinutes ->
                mins >= rule.startMinutes && mins < rule.endMinutes && days.contains(day)
            else -> // 跨午夜
                (mins >= rule.startMinutes && days.contains(day)) ||
                        (mins < rule.endMinutes && days.contains(prev))
        }
    }

    /** 若此刻在窗口内，返回窗口结束时刻（跨午夜时结束点在次日） */
    fun nextWindowEnd(rule: Rule, now: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (!inWindow(rule, cal)) return null
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return when {
            rule.startMinutes == rule.endMinutes -> now + 24 * 3600_000L
            rule.startMinutes < rule.endMinutes -> todayAt(cal, rule.endMinutes)
            else ->
                if (mins >= rule.startMinutes) todayAt(cal, rule.endMinutes) + 24 * 3600_000L
                else todayAt(cal, rule.endMinutes)
        }
    }

    /**
     * “本窗口不再响”的统一边界（抑制截止时刻）：
     * - 普通窗口：当日结束分钟；
     * - 跨午夜：当前窗口的结束时刻（晚间触发到次日 end，凌晨触发到当日 end）；
     * - 全天（start==end）：当前时刻 + 24h；
     * - 此刻不在窗口内：+24h 兜底（窗口外本就不触发，抑制点此时无实际意义）。
     */
    fun suppressUntilFor(rule: Rule, now: Long): Long {
        val end = nextWindowEnd(rule, now)
        return if (end != null && end > now) end else now + 24 * 3600_000L
    }

    private fun todayAt(cal: Calendar, minutes: Int): Long {
        val c = (cal.clone() as Calendar)
        c.set(Calendar.HOUR_OF_DAY, minutes / 60)
        c.set(Calendar.MINUTE, minutes % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------- 匹配 ----------

    fun matchesMessage(rule: Rule, pkg: String, text: String): Boolean {
        if (!rule.watchMessages || rule.packages.isEmpty()) return false
        if (!rule.packages.contains(pkg)) return false
        val kw = rule.senderKeyword.trim()
        return kw.isEmpty() || text.contains(kw, ignoreCase = true)
    }

    fun normalizeNumber(raw: String): String = raw.filter { it.isDigit() }

    /**
     * 来电通知候选：系统通话类通知（category=CALL）或拨号器/通话界面包名的通知。
     * ColorOS 上“勿扰也拦不住”的来电提醒、通话中常驻通知都走这里。
     */
    fun isCallCandidate(pkg: String, category: String?): Boolean {
        if (category == Notification.CATEGORY_CALL) return true
        val p = pkg.lowercase()
        return p.contains("dialer") || p.contains("incallui") || p.contains("telecom")
    }

    /**
     * 来电通知匹配：通知文本（姓名/号码）命中规则里任一联系人的姓名或号码即触发。
     */
    fun matchesCallNotification(rule: Rule, rawText: String): Boolean {
        if (!rule.watchCalls || rule.contacts.isEmpty()) return false
        val lower = rawText.lowercase()
        val normText = normalizeNumber(rawText)
        return rule.contacts.any { c ->
            val name = rule.contactNames[c]
            (!name.isNullOrBlank() && lower.contains(name.lowercase())) ||
                    (normalizeNumber(c).length >= 7 && normText.contains(normalizeNumber(c)))
        }
    }

    fun shortPkg(pkg: String): String = pkg.substringAfterLast('.', pkg)

    fun maskNumber(n: String): String {
        val a = normalizeNumber(n)
        return if (a.length <= 4) "****" else "****" + a.takeLast(4)
    }

    // ---------- 事件入口 ----------

    /** NLS 收到通知（已过滤自身/常驻/组摘要） */
    suspend fun onMessage(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val text = extractText(sbn)
        val rules = Store.rules(context)
        android.util.Log.d(TAG, "onMessage pkg=$pkg textLen=${text.length} rules=${rules.size}")
        for (rule in rules) {
            if (matchesMessage(rule, pkg, text)) {
                android.util.Log.d(TAG, "onMessage matched rule=${rule.id}")
                onEventTriggered(context, rule, "消息·${shortPkg(pkg)}", isCall = false)
            }
        }
    }

    /** NLS 收到“疑似来电”通知：先记候选元数据日志（不落原文），再匹配规则 */
    suspend fun onCallNotification(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val text = extractText(sbn)
        // 隐私：来电通知正文常含完整号码/姓名，持久化日志只记长度等元数据；
        // 校准各 ROM 格式用 Log.d（仅设备内存 ring buffer，几分钟即翻转，不落盘）
        android.util.Log.d(TAG, "onCallNotification pkg=$pkg cat=${sbn.notification?.category} textLen=${text.length} text=$text")
        Store.addLog(
            context,
            LogEntry(
                System.currentTimeMillis(), "",
                "来电候选·${shortPkg(pkg)}",
                callCandidateMeta(text)
            )
        )
        // 未接来电通知不作为触发源：原始来电通知已经触发过，未接是对它的重复播报
        if (text.contains("未接") || text.contains("漏接") || text.lowercase().contains("missed")) return
        // 通话进行中（已接听）不重复触发：正在打电话的人不需要闹钟
        if (isInCall(context)) {
            android.util.Log.d(TAG, "onCallNotification skipped: in call")
            return
        }
        for (rule in Store.rules(context)) {
            if (!matchesCallNotification(rule, text)) continue
            val name = rule.contactNames.values.firstOrNull { text.contains(it, ignoreCase = true) }
            val source = "来电·" + (name ?: maskNumber(text))
            onEventTriggered(context, rule, source, isCall = true)
        }
    }

    /**
     * 当前是否正在通话中、已接听（OFFHOOK；需要 READ_PHONE_STATE，未授权时返回 false，
     * 功能退化为仅依赖静默通知手动取消）。注意 RINGING 不算——来电触发本身就发生在响铃时。
     */
    fun isInCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        @Suppress("DEPRECATION")
        val state = tm?.callState ?: return false
        return state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
    }

    /**
     * 触发流水线：窗口判断 → 冷却去重 → 装载延迟响铃任务。
     * 装载结果（成功/去重跳过/失败）由 ActionExecutor 统一记日志，
     * “触发成功”不等于“实际响铃成功”——开始响铃另有独立日志。
     */
    suspend fun onEventTriggered(context: Context, rule: Rule, source: String, isCall: Boolean = false): Boolean {
        android.util.Log.d(TAG, "onEventTriggered rule=${rule.id} enabled=${rule.enabled}")
        if (!rule.enabled) return false
        val now = System.currentTimeMillis()
        // “今天不再响”抑制期内直接跳过（记一条日志便于确认，不响铃）
        val suppressUntil = Store.suppressUntil(context)[rule.id] ?: 0L
        if (now < suppressUntil) {
            Store.addLog(context, LogEntry(now, rule.name, source, "已抑制（手动设置了今天不再响）", LogKind.SUPPRESS))
            return false
        }
        if (!inWindow(rule)) return false
        val last = Store.lastTrigger(context)[rule.id] ?: 0L
        if (rule.cooldownSeconds > 0 && now - last < rule.cooldownSeconds * 1000L) return false
        Store.markTriggered(context, rule.id, now)

        if (!rule.delayedAlarm) return false
        val outcome = ActionExecutor.armDelayedAlarm(context, rule, now + rule.alarmDelayMinutes * 60_000L, source, isCall)
        return outcome is ActionExecutor.ArmOutcome.Armed
    }

    // ---------- 通知文本解析 ----------

    /** “来电候选”持久化日志的脱敏元数据文案（新格式，供历史日志脱敏时识别） */
    fun callCandidateMeta(text: String): String = "通知文本 ${text.length} 字（隐私不记录原文）"

    /**
     * 提取标题/正文/大文本/会话消息，仅供内存中匹配关键词，不落盘。
     *
     * EXTRA_MESSAGES 里装的是 Bundle[]（跨进程已消息化），不能直接强转 MessagingStyle.Message——
     * 必须走 `Message.getMessagesFromBundleArray()` 解包，否则标准 MessagingStyle 通知
     * （微信/QQ/短信等）的发送者与正文全部丢失，关键词匹配失效。
     */
    fun extractText(sbn: StatusBarNotification): String {
        val ex = sbn.notification.extras
        val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        )
        return composeExtractedText(
            title = ex.getCharSequence(Notification.EXTRA_TITLE),
            text = ex.getCharSequence(Notification.EXTRA_TEXT),
            bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            messages = msgs.map { it.senderPerson?.name?.toString() to it.text },
        )
    }

    /**
     * 纯字符串组装（JVM 可测）：标题/正文/大文本各一段，会话消息每条一段“发送者:正文”。
     * 与旧实现一致地过滤空白段；发送者与正文都为空的消息段直接跳过。
     */
    fun composeExtractedText(
        title: CharSequence?,
        text: CharSequence?,
        bigText: CharSequence?,
        messages: List<Pair<String?, CharSequence?>>,
    ): String {
        val parts = mutableListOf<String>()
        title?.let { parts.add(it.toString()) }
        text?.let { parts.add(it.toString()) }
        bigText?.let { parts.add(it.toString()) }
        messages.forEach { (sender, body) ->
            val s = sender?.toString() ?: ""
            val b = body?.toString() ?: ""
            if (s.isNotBlank() || b.isNotBlank()) parts.add("$s:$b")
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }
}
