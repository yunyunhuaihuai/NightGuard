package com.nightguard.app.receiver

import android.service.notification.NotificationListenerService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nightguard.app.R
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.Store
import com.nightguard.app.logic.ActionExecutor
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.logic.RuleEngine
import com.nightguard.app.service.AlarmRingService
import com.nightguard.app.service.NightNotificationListener
import kotlinx.coroutines.runBlocking

/**
 * 统一广播入口：
 * - 延迟闹钟（动作二到期）→ 拉起响铃前台服务
 * - 恢复闹钟（动作一到期）→ 还原勿扰/铃声
 * - 每日心跳 → 监听连接自检（requestRebind 兜底 + 提醒）+ 补漏恢复
 * - 开机/应用更新 → 重排心跳与恢复闹钟（精确闹钟重启后会丢）
 */
class NightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AlarmScheduler.ACTION_DELAYED_ALARM ->
                runBlocking { handleDelayedAlarm(app, intent.getStringExtra("ruleId")) }
            AlarmScheduler.ACTION_HEARTBEAT ->
                runBlocking { handleHeartbeat(app) }
            AlarmScheduler.ACTION_CANCEL_ALARM ->
                runBlocking { handleCancelAlarm(app, intent.getStringExtra("ruleId"), "已手动取消本次闹钟（本次不响）") }
            AlarmScheduler.ACTION_SUPPRESS_RULE ->
                runBlocking { handleSuppressRule(app, intent.getStringExtra("ruleId")) }
            AlarmScheduler.ACTION_CHECK_CALL_STATE ->
                runBlocking { handleCheckCallState(app, intent.getStringExtra("ruleId")) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runBlocking { handleBoot(app) }
        }
    }

    private suspend fun handleDelayedAlarm(context: Context, ruleId: String?) {
        if (ruleId == null) return
        // 到点响铃：撤掉“已设定的闹钟”静默提醒，停止接听轮询
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ruleId.hashCode())
        AlarmScheduler.cancelCheckCall(context, ruleId)
        Store.setPendingAlarm(context, ruleId, 0L)
        val rule = Store.ruleByIdSync(context, ruleId) ?: return
        if (!rule.enabled) return
        AlarmScheduler.ensureChannels(context)
        try {
            context.startForegroundService(AlarmRingService.ringIntent(context))
        } catch (e: Exception) {
            Log.w("NightReceiver", "ring start failed", e)
            Store.addLog(
                context,
                LogEntry(System.currentTimeMillis(), rule.name, "", "响铃启动失败：" + e.message)
            )
        }
    }

    /** “本次不响”与“来电已接听自动取消”共用：撤闹钟+撤提醒+记日志 */
    private suspend fun handleCancelAlarm(context: Context, ruleId: String?, logText: String) {
        if (ruleId == null) return
        val rule = Store.ruleByIdSync(context, ruleId) ?: return
        ActionExecutor.cancelRuleAlarm(context, rule, logText)
    }

    /** “今天不再响”：本监听窗口内该规则不再触发 */
    private suspend fun handleSuppressRule(context: Context, ruleId: String?) {
        if (ruleId == null) return
        val rule = Store.ruleByIdSync(context, ruleId) ?: return
        ActionExecutor.suppressRuleToday(context, rule)
    }

    /** 每 3 秒的通话状态轮询：已接听（OFFHOOK）则自动取消本次闹钟，否则继续下一轮 */
    private suspend fun handleCheckCallState(context: Context, ruleId: String?) {
        if (ruleId == null) return
        val now = System.currentTimeMillis()
        val fireAt = Store.pendingAlarms(context)[ruleId] ?: return
        if (fireAt <= now) return // 已响或已取消
        val rule = Store.ruleByIdSync(context, ruleId) ?: return
        if (!rule.enabled) return
        if (RuleEngine.isInCall(context)) {
            ActionExecutor.cancelRuleAlarm(context, rule, "来电已接听，闹钟自动取消")
            return
        }
        AlarmScheduler.scheduleExact(context, now + 3000L, AlarmScheduler.checkCallPI(context, ruleId))
    }

    private suspend fun handleHeartbeat(context: Context) {
        val now = System.currentTimeMillis()
        if (!Store.isConnected(context)) {
            val lastRebind = Store.lastRebindAt(context)
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, NightNotificationListener::class.java)
                )
            } catch (e: Exception) {
                Log.w("NightReceiver", "requestRebind failed", e)
            }
            if (lastRebind == 0L) {
                Store.markRebind(context, now)
            } else if (now - lastRebind > 24 * 3600_000L) {
                notifyListenerDown(context)
                Store.markRebind(context, now)
            }
        } else {
            Store.markRebind(context, 0L)
        }
        AlarmScheduler.scheduleHeartbeat(context)
    }

    private fun notifyListenerDown(context: Context) {
        AlarmScheduler.ensureChannels(context)
        val notif = android.app.Notification.Builder(context, AlarmScheduler.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("通知唤醒：消息监听已断开")
            .setContentText("系统未重新连接通知监听，请打开应用检查“通知使用权”")
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(1001, notif)
        } catch (e: SecurityException) {
        }
    }

    private suspend fun handleBoot(context: Context) {
        AlarmScheduler.scheduleHeartbeat(context)
    }
}
