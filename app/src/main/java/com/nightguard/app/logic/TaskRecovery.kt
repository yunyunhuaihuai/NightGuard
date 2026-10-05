package com.nightguard.app.logic

import android.content.Context
import android.util.Log
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.PendingTask
import com.nightguard.app.data.PendingTasks
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store

/**
 * 待响任务的恢复编排：开机（BOOT_COMPLETED）、应用更新（MY_PACKAGE_REPLACED）
 * 与应用启动后调用。精确闹钟在重启/更新后会丢，按持久化任务记录幂等重排；
 * 过期任务、已禁用/已删除规则的任务按明确策略处理并写日志。
 */
object TaskRecovery {
    private const val TAG = "NightGuard"

    suspend fun recover(context: Context) {
        val app = context.applicationContext
        val rules = Store.rules(app)
        val plan = PendingTasks.recover(app, rules)

        plan.rearm.forEach { task -> rearmTask(app, task, "") }
        plan.catchUp.forEach { task -> rearmTask(app, task, "重启期间已到点，15 秒内补响") }
        plan.expired.forEach { task ->
            Store.addLog(
                app,
                LogEntry(
                    System.currentTimeMillis(), ruleName(rules, task.ruleId), task.source,
                    "重启恢复：任务已过期（原定 ${fmt(task.fireAt)}），跳过响铃"
                )
            )
        }
        plan.dropped.forEach { (task, reason) ->
            Store.addLog(
                app,
                LogEntry(
                    System.currentTimeMillis(), ruleName(rules, task.ruleId), task.source,
                    "重启恢复：$reason，丢弃待响任务"
                )
            )
        }
        // 进程重建后重新挂接听监听（任务记录已持久化）
        CallStateMonitor.reRegisterAll(app)
        if (plan.rearm.isNotEmpty() || plan.catchUp.isNotEmpty() || plan.expired.isNotEmpty() || plan.dropped.isNotEmpty()) {
            Log.d(
                TAG,
                "TaskRecovery: rearm=${plan.rearm.size} catchUp=${plan.catchUp.size} " +
                        "expired=${plan.expired.size} dropped=${plan.dropped.size}"
            )
        }
    }

    /** 重排单个任务；调度失败标记 failed；非精确模式记录能力降级 */
    private suspend fun rearmTask(context: Context, task: PendingTask, note: String) {
        val exact = try {
            AlarmScheduler.scheduleExact(
                context, task.fireAt, AlarmScheduler.delayedPI(context, task.ruleId, task.taskId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "rearm scheduleExact failed", e)
            PendingTasks.markFailed(context, task.ruleId, task.taskId)
            Store.addLog(
                context,
                LogEntry(
                    System.currentTimeMillis(), "", task.source,
                    "恢复失败：闹钟重排异常（${e.message ?: e.javaClass.simpleName}）"
                )
            )
            return
        }
        if (!exact || note.isNotEmpty()) {
            val suffix = if (!exact) "（非精确模式，响铃时间不保证准点）" else ""
            val base = if (note.isNotEmpty()) note else "重启恢复：任务已重排"
            Store.addLog(context, LogEntry(System.currentTimeMillis(), "", task.source, base + suffix))
        }
    }

    private fun ruleName(rules: List<Rule>, ruleId: String): String =
        rules.firstOrNull { it.id == ruleId }?.name ?: ""

    private fun fmt(at: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
