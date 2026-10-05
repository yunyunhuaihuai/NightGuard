package com.nightguard.app.logic

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nightguard.app.R
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.PendingTaskCore
import com.nightguard.app.data.PendingTasks
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import com.nightguard.app.ui.MainActivity

/**
 * 动作：延迟响铃的装载与取消（第二阶段统一走待响任务生命周期）。
 *
 * 设定任务时同步发一条**静默**通知（无声不震动不亮屏）：“已设定 N 分钟后的闹钟”，
 * 醒着的人可在通知上选“本次不响”或“今天不再响”；不响应则到点正常响铃。
 * 来电触发改为注册通话状态事件监听（CallStateMonitor），已接听自动取消；
 * 取消电话状态的秒级轮询。
 *
 * 注：原“动作一（关闭勿扰+响铃）”已于 v0.4.0 移除——ColorOS 15 强制每次人工确认，
 * 夜间无人确认时无法生效。详见 技术实现.md §5.1。
 */
object ActionExecutor {
    private const val TAG = "NightGuard"

    sealed interface ArmOutcome {
        /** 新任务装载成功（含非精确降级标记） */
        data class Armed(val taskId: String, val exact: Boolean) : ArmOutcome
        /** 去重跳过：同规则已有未到期任务（不得记作装载成功） */
        data class Deduped(val existingFireAt: Long) : ArmOutcome
        /** 调度失败，任务已标记 failed */
        data class Failed(val reason: String) : ArmOutcome
    }

    /**
     * 装载延迟响铃任务：状态机去重 → 排闹钟（失败标记 failed）→ 静默提醒 →
     * 来电触发时挂接听事件监听。所有结果都返回明确的 [ArmOutcome]。
     */
    suspend fun armDelayedAlarm(
        context: Context,
        rule: Rule,
        at: Long,
        source: String,
        isCall: Boolean,
    ): ArmOutcome {
        val now = System.currentTimeMillis()
        val decision = PendingTasks.arm(context, rule.id, at, source, fromCall = isCall)
        return when (decision) {
            is PendingTaskCore.ArmDecision.Deduped -> {
                Store.addLog(
                    context,
                    LogEntry(now, rule.name, source, "已有待响任务（${fmtTime(decision.existing.fireAt)} 到点），跳过重复装载")
                )
                ArmOutcome.Deduped(decision.existing.fireAt)
            }
            is PendingTaskCore.ArmDecision.Write -> {
                val task = decision.task
                val exact = try {
                    AlarmScheduler.scheduleExact(context, at, AlarmScheduler.delayedPI(context, rule.id, task.taskId))
                } catch (e: Exception) {
                    Log.w(TAG, "scheduleExact failed", e)
                    PendingTasks.markFailed(context, rule.id, task.taskId)
                    Store.addLog(
                        context,
                        LogEntry(now, rule.name, source, "闹钟装载失败：${e.message ?: e.javaClass.simpleName}")
                    )
                    return ArmOutcome.Failed(e.message ?: e.javaClass.simpleName)
                }
                postArmedNotification(context, rule, task.taskId)
                if (isCall && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    CallStateMonitor.startForTask(context, rule.id)
                }
                Store.addLog(
                    context,
                    LogEntry(
                        now, rule.name, source,
                        rule.describeActions() + if (exact) "" else "（非精确模式，响铃时间不保证准点）"
                    )
                )
                ArmOutcome.Armed(task.taskId, exact)
            }
        }
    }

    /** 静默提醒：无声、不震动、不亮屏；醒着的人可直接取消，睡着的人不受影响 */
    fun postArmedNotification(context: Context, rule: Rule, taskId: String) {
        AlarmScheduler.ensureChannels(context)
        val contentPI = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, AlarmScheduler.CHANNEL_ARM)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("已设定 ${rule.alarmDelayMinutes} 分钟后的闹钟")
            .setContentText("${rule.name} · 无响应将准时响铃")
            .setContentIntent(contentPI)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(0, "本次不响", AlarmScheduler.cancelAlarmPI(context, rule.id, taskId))
            .addAction(0, "今天不再响", AlarmScheduler.suppressPI(context, rule.id, taskId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(taskId.hashCode(), notif)
        } catch (e: SecurityException) {
        }
    }

    /**
     * 取消规则的当前任务（可带 taskId 精确校验）：
     * 撤闹钟 + 撤静默提醒 + 停接听监听 + 记日志。
     * @return true=确实取消了任务；false=没有任务或请求来自过期通知（已忽略）
     */
    suspend fun cancelRuleAlarm(context: Context, ruleId: String?, taskId: String?, logText: String): Boolean {
        if (ruleId == null) return false
        val decision = PendingTasks.cancel(context, ruleId, taskId)
        when (decision) {
            is PendingTaskCore.CancelDecision.Cancelled -> {
                val task = decision.task
                AlarmScheduler.cancelDelayed(context, ruleId, task.taskId)
                CallStateMonitor.stopForTask(context, ruleId)
                try {
                    NotificationManagerCompat.from(context).cancel(task.taskId.hashCode())
                } catch (e: SecurityException) {
                }
                Store.addLog(
                    context,
                    LogEntry(System.currentTimeMillis(), Store.ruleByIdSync(context, ruleId)?.name ?: "", task.source, logText)
                )
                return true
            }
            PendingTaskCore.CancelDecision.StaleRequest -> {
                Log.d(TAG, "cancelRuleAlarm: 忽略过期通知的取消请求 ruleId=$ruleId taskId=$taskId")
                return false
            }
            PendingTaskCore.CancelDecision.NotFound -> return false
        }
    }

    /** “今天不再响”：抑制到统一窗口边界（suppressUntilFor），并取消当前任务（规则级意图） */
    suspend fun suppressRuleToday(context: Context, rule: Rule) {
        val now = System.currentTimeMillis()
        val until = RuleEngine.suppressUntilFor(rule, now)
        Store.setSuppressUntil(context, rule.id, until)
        cancelRuleAlarm(context, rule.id, null, "已设为今天不再响（至 ${fmtTime(until)}）")
    }

    /**
     * 规则被禁用或删除时统一清理其运行状态：
     * 取消待响任务（撤闹钟/提醒/接听监听），清抑制点与冷却记录，
     * 并撤掉旧版本按 ruleId 记录编号的遗留提醒。
     */
    suspend fun cleanupRuleState(context: Context, ruleId: String) {
        cancelRuleAlarm(context, ruleId, null, "规则已停用，待响任务已取消")
        Store.removeRuleStateKeys(context, ruleId)
        try {
            NotificationManagerCompat.from(context).cancel(ruleId.hashCode())
        } catch (e: SecurityException) {
        }
    }

    private fun fmtTime(until: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = until }
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
