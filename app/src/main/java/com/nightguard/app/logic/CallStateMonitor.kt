package com.nightguard.app.logic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.LogKind
import com.nightguard.app.data.PendingTaskCore
import com.nightguard.app.data.PendingTasks
import com.nightguard.app.data.Store
import com.nightguard.app.data.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 来电接听事件监控（替代旧版“每 3 秒轮询 callState”的链式精确闹钟）：
 * - 为每条来电触发的待响任务注册 TelephonyCallback（默认线路 + 全部激活 SIM 卡）；
 * - 收到 OFFHOOK（已接听）事件 → 记录“本次来电曾接听”并取消该规则当前任务；
 *   快速接听后立即挂断同样生效（事件不丢，轮询可能漏掉 1 秒内的接听）；
 * - 拒接/未接（RINGING→IDLE，无 OFFHOOK）不取消，闹钟照常；
 * - READ_PHONE_STATE 未授权：功能退化（仅靠响铃前的手动取消），不崩溃；
 * - 进程重建后由恢复流程调 [reRegisterAll] 重新挂监听。
 */
object CallStateMonitor {
    private const val TAG = "NightGuard"

    private class Registration(val tm: TelephonyManager, val callback: TelephonyCallback)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registered = HashMap<String, List<Registration>>()

    private fun permissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    /** 为规则的待响任务注册接听事件（全部激活的 SIM；重复调用会先注销旧监听） */
    fun startForTask(context: Context, ruleId: String) {
        if (Build.VERSION.SDK_INT < 31) return // TelephonyCallback 需 API 31+；minSdk 34 不会走到
        if (!permissionGranted(context)) {
            Log.d(TAG, "CallStateMonitor: 未授权 READ_PHONE_STATE，接听自动取消退化")
            return
        }
        stopForTask(context, ruleId)
        val app = context.applicationContext
        val regs = mutableListOf<Registration>()
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (state != TelephonyManager.CALL_STATE_OFFHOOK) return
                scope.launch { onAnswered(app, ruleId) }
            }
        }
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val executor = context.mainExecutor
        tryRegister(context, tm, executor, callback, regs)
        // 双卡：逐个激活的 subscription 再挂一份（默认线路只覆盖默认卡）
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val subs = sm?.activeSubscriptionInfoList
            subs?.forEach { info ->
                try {
                    tryRegister(context, tm.createForSubscriptionId(info.subscriptionId), executor, callback, regs)
                } catch (e: Exception) {
                    Log.d(TAG, "CallStateMonitor: sub ${info.subscriptionId} 注册失败", e)
                }
            }
        } catch (e: SecurityException) {
            Log.d(TAG, "CallStateMonitor: 读取激活 SIM 失败（权限被撤销）", e)
        }
        synchronized(registered) { registered[ruleId] = regs }
        Log.d(TAG, "CallStateMonitor: registered ruleId=$ruleId lines=${regs.size}")
    }

    private fun tryRegister(
        context: Context,
        tm: TelephonyManager,
        executor: java.util.concurrent.Executor,
        callback: TelephonyCallback,
        out: MutableList<Registration>,
    ) {
        try {
            tm.registerTelephonyCallback(executor, callback)
            out.add(Registration(tm, callback))
        } catch (e: Exception) {
            Log.d(TAG, "CallStateMonitor: register failed", e)
        }
    }

    fun stopForTask(context: Context, ruleId: String) {
        val regs = synchronized(registered) { registered.remove(ruleId) } ?: return
        for (r in regs) {
            try {
                if (Build.VERSION.SDK_INT >= 31) r.tm.unregisterTelephonyCallback(r.callback)
            } catch (e: Exception) {
                Log.d(TAG, "CallStateMonitor: unregister failed", e)
            }
        }
    }

    /** 进程重建/应用启动后：为所有仍然 armed 的来电任务重新挂监听（任务记录已持久化） */
    fun reRegisterAll(context: Context) {
        scope.launch {
            try {
                val tasks = Store.pendingTasks(context.applicationContext)
                    .filter { it.status == TaskStatus.ARMED && it.fromCall }
                tasks.forEach { startForTask(context.applicationContext, it.ruleId) }
            } catch (e: Exception) {
                Log.e(TAG, "CallStateMonitor: reRegisterAll failed", e)
            }
        }
    }

    /** 到期最终校验：任一激活线路当前处于通话中（已接听）即视为本次来电曾接听 */
    fun anyCallOffhook(context: Context): Boolean {
        if (!permissionGranted(context)) return false
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        fun TelephonyManager.offhook(): Boolean = try {
            callState == TelephonyManager.CALL_STATE_OFFHOOK
        } catch (e: Exception) {
            false
        }
        if (tm.offhook()) return true
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm?.activeSubscriptionInfoList?.any { info ->
                try {
                    tm.createForSubscriptionId(info.subscriptionId).offhook()
                } catch (e: Exception) {
                    false
                }
            } == true
        } catch (e: SecurityException) {
            false
        }
    }

    /** OFFHOOK 事件落地：取消该规则当前任务并记录“来电已接听” */
    private suspend fun onAnswered(context: Context, ruleId: String) {
        val decision = PendingTasks.cancel(context, ruleId, null)
        if (decision !is PendingTaskCore.CancelDecision.Cancelled) return
        val task = decision.task
        AlarmScheduler.cancelDelayed(context, ruleId, task.taskId)
        try {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(task.taskId.hashCode())
        } catch (e: SecurityException) {
        }
        val ruleName = Store.ruleById(context, ruleId)?.name ?: ""
        Store.addLog(context, LogEntry(System.currentTimeMillis(), ruleName, task.source, "来电已接听，闹钟自动取消", LogKind.CANCEL))
        Log.d(TAG, "CallStateMonitor: call answered, task cancelled ruleId=$ruleId")
    }
}
