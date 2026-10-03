package com.nightguard.app.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nightguard.app.data.Store
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.runBlocking

/**
 * 消息监听（事件驱动，零轮询）。
 * v2 自愈：onListenerDisconnected 里立即 requestRebind()；
 * 连接状态落盘，供每日心跳比对（心跳里再兜底 requestRebind + 提醒用户）。
 */
class NightNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected")
        runBlocking {
            Store.setListenerState(this@NightNotificationListener, true, System.currentTimeMillis())
        }
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "onListenerDisconnected")
        runBlocking {
            Store.setListenerState(this@NightNotificationListener, false, System.currentTimeMillis())
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
                runBlocking { RuleEngine.onCallNotification(this@NightNotificationListener, sbn) }
                return
            }
            // 普通消息路径：跳过常驻通知与组摘要
            if (sbn.isOngoing) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            runBlocking { RuleEngine.onMessage(this@NightNotificationListener, sbn) }
            Log.d(TAG, "onMessage returned")
        } catch (t: Throwable) {
            Log.e(TAG, "onNotificationPosted failed", t)
        }
    }

    companion object {
        private const val TAG = "NightGuard"
    }
}
