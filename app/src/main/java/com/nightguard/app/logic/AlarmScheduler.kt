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
 * USE_EXACT_ALARM 已自动授予；万一被 ROM 拒绝则退化为 1 分钟窗口闹钟。
 */
object AlarmScheduler {
    const val ACTION_DELAYED_ALARM = "com.nightguard.app.action.DELAYED_ALARM"
    const val ACTION_HEARTBEAT = "com.nightguard.app.action.HEARTBEAT"
    const val ACTION_CANCEL_ALARM = "com.nightguard.app.action.CANCEL_ALARM"
    const val ACTION_SUPPRESS_RULE = "com.nightguard.app.action.SUPPRESS_RULE"
    const val ACTION_CHECK_CALL_STATE = "com.nightguard.app.action.CHECK_CALL_STATE"

    const val CHANNEL_RING = "ring"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ARM = "arm"

    fun am(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun delayedPI(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_DELAYED_ALARM)
            .putExtra("ruleId", ruleId)
        return PendingIntent.getBroadcast(
            context, ruleId.hashCode(), intent,
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

    /** “本次不响”：取消该规则当前待响的闹钟 */
    fun cancelAlarmPI(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_CANCEL_ALARM)
            .putExtra("ruleId", ruleId)
        return PendingIntent.getBroadcast(
            context, ruleId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** “今天不再响”：本监听窗口内该规则不再触发 */
    fun suppressPI(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_SUPPRESS_RULE)
            .putExtra("ruleId", ruleId)
        return PendingIntent.getBroadcast(
            context, ruleId.hashCode() + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 延迟中途检查通话状态：已接听则自动取消闹钟 */
    fun checkCallPI(context: Context, ruleId: String): PendingIntent {
        val intent = Intent(context, NightReceiver::class.java)
            .setAction(ACTION_CHECK_CALL_STATE)
            .putExtra("ruleId", ruleId)
        return PendingIntent.getBroadcast(
            context, ruleId.hashCode() + 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelCheckCall(context: Context, ruleId: String) {
        am(context)?.cancel(checkCallPI(context, ruleId))
    }

    fun scheduleExact(context: Context, at: Long, pi: PendingIntent) {
        val alarm = am(context) ?: return
        if (alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarm.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
        }
    }

    fun cancelDelayed(context: Context, ruleId: String) {
        am(context)?.cancel(delayedPI(context, ruleId))
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
