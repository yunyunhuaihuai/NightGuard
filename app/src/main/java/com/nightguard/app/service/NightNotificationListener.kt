package com.nightguard.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nightguard.app.data.Store
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 消息监听（事件驱动，零轮询）。
 * 自愈：onListenerDisconnected 里立即 requestRebind()；连接状态落盘供每日心跳比对。
 *
 * 线程模型：回调内不再主线程 runBlocking——落盘与事件处理提交到自有协程作用域
 * （`limitedParallelism(1)` 单通道调度，保持与回调到达一致的事件顺序），
 * 异常在协程内捕获（ColorOS 会吞回调异常，没有这层日志没法排障）。
 * 作用域随 onDestroy 取消。
 */
class NightNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    override fun onCreate() {
        super.onCreate()
        serviceCreatedInProcess = true
    }

    override fun onDestroy() {
        boundInProcess = false
        serviceCreatedInProcess = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        boundInProcess = true
        Log.d(TAG, "onListenerConnected")
        scope.launch {
            try {
                Store.setListenerState(this@NightNotificationListener, true, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.e(TAG, "setListenerState failed", t)
            }
        }
    }

    override fun onListenerDisconnected() {
        boundInProcess = false
        Log.d(TAG, "onListenerDisconnected")
        scope.launch {
            try {
                Store.setListenerState(this@NightNotificationListener, false, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.e(TAG, "setListenerState failed", t)
            }
        }
        try {
            NotificationListenerService.requestRebind(
                ComponentName(applicationContext, NightNotificationListener::class.java)
            )
        } catch (e: Exception) {
            Log.d(TAG, "requestRebind failed: $e")
            // 心跳会再次兜底
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Log.d(TAG, "onNotificationPosted pkg=${sbn.packageName} ongoing=${sbn.isOngoing} cat=${sbn.notification?.category}")
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            // 来电候选：通话类通知或拨号器包名的通知（可能带 ongoing 标志，不能过滤）
            if (RuleEngine.isCallCandidate(sbn.packageName, n.category)) {
                scope.launch {
                    try {
                        RuleEngine.onCallNotification(this@NightNotificationListener, sbn)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onCallNotification failed", t)
                    }
                }
                return
            }
            // 普通消息路径：跳过常驻通知与组摘要
            if (sbn.isOngoing) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            scope.launch {
                try {
                    RuleEngine.onMessage(this@NightNotificationListener, sbn)
                    Log.d(TAG, "onMessage returned")
                } catch (t: Throwable) {
                    Log.e(TAG, "onMessage failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onNotificationPosted failed", t)
        }
    }

    companion object {
        private const val TAG = "NightGuard"

        /** 本进程监听服务的连接状态（界面据此区分“已授权未连接/状态未知”）；历史时间戳仅用于诊断 */
        @Volatile
        var boundInProcess: Boolean = false
            private set

        /** 本进程内监听服务是否至少被系统创建过（false 时连接状态未知） */
        @Volatile
        var serviceCreatedInProcess: Boolean = false
            private set
    }
}
