package com.nightguard.app.logic

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nightguard.app.R
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import com.nightguard.app.ui.MainActivity

/**
 * 动作：延迟响铃的装载与取消。
 *
 * 设定闹钟时同步发一条**静默**通知（无声不震动不亮屏）：“已设定 N 分钟后的闹钟”，
 * 醒着的人可在通知上选“本次不响”或“今天不再响”；不响应则到点正常响铃。
 * 来电触发且授权了电话状态时，延迟中途检查一次通话状态，已接听则自动取消。
 *
 * 注：原“动作一（关闭勿扰+响铃）”已于 v0.4.0 移除——ColorOS 15 强制每次人工确认，
 * 夜间无人确认时无法生效。详见 技术实现.md §5.1。
 */
object ActionExecutor {

    /** 定“延迟闹钟”（同一规则去重），并发静默提醒 + （来电触发时）排接听检查 */
    suspend fun armDelayedAlarm(
        context: Context,
        rule: Rule,
        at: Long,
        source: String,
        isCall: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val pending = Store.pendingAlarms(context)
        if ((pending[rule.id] ?: 0L) > now) return

        Store.setPendingAlarm(context, rule.id, at)
        AlarmScheduler.scheduleExact(context, at, AlarmScheduler.delayedPI(context, rule.id))
        postArmedNotification(context, rule, source)

        // 来电触发：每 3 秒读一次通话状态，已接听（OFFHOOK）则自动取消（链式精确闹钟）。
        // 间隔下限 3 秒；延迟很长时自动拉大间隔，总轮询次数不超过约 40 次。
        if (isCall && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val intervalMs = maxOf(3000L, (at - now) / 40)
            if (at - now > intervalMs / 2) {
                AlarmScheduler.scheduleExact(context, now + intervalMs, AlarmScheduler.checkCallPI(context, rule.id))
            }
        }
    }

    /** 静默提醒：无声、不震动、不亮屏；醒着的人可直接取消，睡着的人不受影响 */
    fun postArmedNotification(context: Context, rule: Rule, source: String) {
        AlarmScheduler.ensureChannels(context)
        val contentPI = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, AlarmScheduler.CHANNEL_ARM)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("已设定 ${rule.alarmDelayMinutes} 分钟后的闹钟")
            .setContentText("$source · 无响应将准时响铃")
            .setContentIntent(contentPI)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(0, "本次不响", AlarmScheduler.cancelAlarmPI(context, rule.id))
            .addAction(0, "今天不再响", AlarmScheduler.suppressPI(context, rule.id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(rule.id.hashCode(), notif)
        } catch (e: SecurityException) {
        }
    }

    /** 取消某规则当前待响的闹钟 + 静默提醒，并记日志 */
    suspend fun cancelRuleAlarm(context: Context, rule: Rule, logText: String) {
        AlarmScheduler.cancelDelayed(context, rule.id)
        AlarmScheduler.cancelCheckCall(context, rule.id)
        Store.setPendingAlarm(context, rule.id, 0L)
        NotificationManagerCompat.from(context).cancel(rule.id.hashCode())
        Store.addLog(context, LogEntry(System.currentTimeMillis(), rule.name, "", logText))
    }

    /** “今天不再响”：抑制到当前监听窗口结束 */
    suspend fun suppressRuleToday(context: Context, rule: Rule) {
        val now = System.currentTimeMillis()
        val until = RuleEngine.nextWindowEnd(rule, now) ?: (now + 24 * 3600_000L)
        Store.setSuppressUntil(context, rule.id, until)
        cancelRuleAlarm(context, rule, "已设为今天不再响（至 " + fmtTime(until) + "）")
    }

    private fun fmtTime(until: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = until }
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
