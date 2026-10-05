package com.nightguard.app.logic

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nightguard.app.receiver.NightReceiver

/**
 * 精确闹钟调度 + 通知渠道。
 * USE_EXACT_ALARM 已自动授予；被 ROM/用户拒绝时退化为 60 秒窗口闹钟——
 * 这是能力降级：窗口闹钟不承诺准点（Doze/ROM 限制下可能更晚），
 * 调用方须把降级显式记入日志并在界面上反映。
 */
object AlarmScheduler {
    const val ACTION_DELAYED_ALARM = "com.nightguard.app.action.DELAYED_ALARM"
    const val ACTION_HEARTBEAT = "com.nightguard.app.action.HEARTBEAT"
    const val ACTION_CANCEL_ALARM = "com.nightguard.app.action.CANCEL_ALARM"
    const val ACTION_SUPPRESS_RULE = "com.nightguard.app.action.SUPPRESS_RULE"

    const val EXTRA_RULE_ID = "ruleId"
    const val EXTRA_TASK_ID = "taskId"

    const val CHANNEL_RING = "ring"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ARM = "arm"

    fun am(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun delayedPI(context: Context, ruleId: String, taskId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_DELAYED_ALARM)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun heartbeatPI(context: Context): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java).setAction(ACTION_HEARTBEAT)
        return PendingIntent.getBroadcast(
            context, 9002, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** “本次不响”：取消该规则当前待响的任务（PI 上带 taskId，过期通知的请求会被忽略） */
    fun cancelAlarmPI(context: Context, ruleId: String, taskId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_CANCEL_ALARM)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context, taskId.hashCode() + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** “今天不再响”：本监听窗口内该规则不再触发（规则级意图，取消当前任务不校验 taskId） */
    fun suppressPI(context: Context, ruleId: String, taskId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_SUPPRESS_RULE)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context, taskId.hashCode() + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 排精确闹钟。
     * @return true=精确调度；false=能力降级（窗口闹钟，不承诺准点）
     * @throws SecurityException 精确闹钟权限在运行期被拒绝且 ROM 不接受降级
     */
    fun scheduleExact(context: Context, at: Long, pi: PendingIntent): Boolean {
        val alarm = am(context) ?: throw IllegalStateException("AlarmManager 不可用")
        return if (alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            true
        } else {
            alarm.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
            false
        }
    }

    fun cancelDelayed(context: Context, ruleId: String, taskId: String) {
        am(context)?.cancel(delayedPI(context, ruleId, taskId))
    }

    /** 每日一次心跳（唯一周期任务）：自检通知监听连接 + 补漏恢复 */
    fun scheduleHeartbeat(context: Context) {
        scheduleExact(context, System.currentTimeMillis() + 24 * 3600_000L, heartbeatPI(context))
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "触发的响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "规则触发的唤醒响铃"
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "运行状态", NotificationManager.IMPORTANCE_DEFAULT)
        )
        // 静默通知渠道：响铃前“已设定的闹钟”提醒，无声不震动不亮屏，醒着的人可取消
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARM, "已设定的闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "响铃前的静默提醒，可在通知上直接取消"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }
}
