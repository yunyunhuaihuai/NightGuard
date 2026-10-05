package com.nightguard.app.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log
import com.nightguard.app.R
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.PendingTasks
import com.nightguard.app.data.Store
import com.nightguard.app.logic.ActionExecutor
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.logic.CallStateMonitor
import com.nightguard.app.logic.TaskRecovery
import com.nightguard.app.service.AlarmRingService
import com.nightguard.app.service.NightNotificationListener
import kotlinx.coroutines.runBlocking

/**
 * 统一广播入口：
 * - 延迟任务到期 → 验证任务仍有效 → 拉起响铃前台服务（旧广播直接忽略）
 * - “本次不响” → 按 taskId 精确取消当前任务
 * - “今天不再响” → 抑制到统一窗口边界 + 规则级取消
 * - 每日心跳 → 监听连接自检（requestRebind 兜底 + 提醒）
 * - 开机/应用更新 → 心跳重排 + 待响任务幂等恢复
 */
class NightReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val ruleId = intent.getStringExtra(AlarmScheduler.EXTRA_RULE_ID)
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID)
        when (intent.action) {
            AlarmScheduler.ACTION_DELAYED_ALARM ->
                runBlocking { handleExpired(app, ruleId, taskId) }
            AlarmScheduler.ACTION_HEARTBEAT ->
                runBlocking { handleHeartbeat(app) }
            AlarmScheduler.ACTION_CANCEL_ALARM ->
                runBlocking {
                    ActionExecutor.cancelRuleAlarm(app, ruleId, taskId, "已手动取消本次闹钟（本次不响）")
                }
            AlarmScheduler.ACTION_SUPPRESS_RULE ->
                runBlocking { handleSuppressRule(app, ruleId) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                runBlocking {
                    AlarmScheduler.scheduleHeartbeat(app)
                    TaskRecovery.recover(app)
                }
        }
    }

    /**
     * 任务到期：先经状态机验收（taskId 与当前任务一致才放行），
     * 旧广播/过期广播在这里被忽略——不会触发新任务、清除新任务或复活已取消任务。
     */
    private suspend fun handleExpired(context: Context, ruleId: String?, taskId: String?) {
        if (ruleId == null) return
        val task = PendingTasks.consumeForFire(context, ruleId, taskId)
        if (task == null) {
            Log.w("NightGuard", "handleExpired: 忽略过期/无效广播 ruleId=$ruleId taskId=$taskId")
            return
        }
        // 撤掉“已设定的闹钟”静默提醒与接听监听
        try {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(task.taskId.hashCode())
        } catch (e: SecurityException) {
        }
        CallStateMonitor.stopForTask(context, ruleId)
        // 来电触发任务的到期最终校验：任一线路通话中（已接听）则不响
        if (task.fromCall && CallStateMonitor.anyCallOffhook(context)) {
            Store.addLog(
                context,
                LogEntry(System.currentTimeMillis(), Store.ruleByIdSync(context, ruleId)?.name ?: "", task.source, "来电已接听，闹钟自动取消")
            )
            return
        }
        val rule = Store.ruleByIdSync(context, ruleId)
        if (rule == null) {
            Store.addLog(context, LogEntry(System.currentTimeMillis(), "", task.source, "任务对应的规则已删除，跳过响铃"))
            return
        }
        if (!rule.enabled) {
            Store.addLog(context, LogEntry(System.currentTimeMillis(), rule.name, task.source, "规则已禁用，跳过响铃"))
            return
        }
        AlarmScheduler.ensureChannels(context)
        try {
            context.startForegroundService(AlarmRingService.ringIntent(context, rule.id, task.taskId))
        } catch (e: Exception) {
            Log.w("NightReceiver", "ring start failed", e)
            Store.addLog(
                context,
                LogEntry(System.currentTimeMillis(), rule.name, task.source, "响铃启动失败：${e.message ?: e.javaClass.simpleName}")
            )
        }
    }

    /** “今天不再响”：规则级抑制到统一窗口边界 + 取消当前任务 */
    private suspend fun handleSuppressRule(context: Context, ruleId: String?) {
        val rule = ruleId?.let { Store.ruleByIdSync(context, it) } ?: return
        ActionExecutor.suppressRuleToday(context, rule)
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
}
