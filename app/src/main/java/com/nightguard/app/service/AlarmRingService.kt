package com.nightguard.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.nightguard.app.R
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.LogKind
import com.nightguard.app.data.Store
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 响铃前台服务（shortService 类型，系统强制分钟级超时，与“响 1 分钟自动停”契合）。
 * 由精确闹钟的 Receiver 以 startForegroundService 拉起（官方允许的后台启动豁免场景）。
 *
 * 响铃会话：服务用 [sessionActiveFlow] 对外暴露“当前是否有响铃会话”，
 * 自动超时 / 用户停止 / 播放失败 / 服务销毁四条路径都会把会话置为结束，
 * RingActivity 观察到结束即自动退出，避免残留亮屏页面。
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_RING = "com.nightguard.app.action.RING"
        const val ACTION_STOP = "com.nightguard.app.action.STOP"
        private const val NOTIF_ID = 42
        private const val MAX_RING_MS = 60_000L
        private const val TAG = "NightGuard"

        /** 当前是否有响铃会话；RingActivity 据此在会话结束后自动退出 */
        private val sessionActive = MutableStateFlow(false)
        val sessionActiveFlow: StateFlow<Boolean> get() = sessionActive

        fun ringIntent(context: Context, ruleId: String? = null, taskId: String? = null): Intent =
            Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra("ruleId", ruleId)
                .putExtra("taskId", taskId)

        fun stopPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context, 7,
                Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        logStop("响铃 60 秒自动停止")
        stopNow()
    }
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 服务内异步写诊断日志用（响铃失败等），随服务销毁取消 */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmScheduler.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论哪个动作都先进前台，满足 startForegroundService 的合规要求
        startAsForeground()
        if (intent?.action == ACTION_STOP) {
            logStop("响铃已手动停止")
            stopNow()
            return START_NOT_STICKY
        }
        sessionActive.value = true
        beginPlaying()
        handler.postDelayed(autoStop, MAX_RING_MS)
        // 诊断：区分“触发成功”与“实际开始响铃”
        val ruleId = intent?.getStringExtra("ruleId")
        if (ruleId != null) {
            logScope.launch {
                try {
                    val name = Store.ruleById(this@AlarmRingService, ruleId)?.name ?: ""
                    Store.addLog(this@AlarmRingService, LogEntry(System.currentTimeMillis(), name, "", "开始响铃（最长 60 秒）", LogKind.RING_START))
                } catch (e: Exception) {
                    Log.e(TAG, "log ring start failed", e)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        logStop("系统强制超时（shortService），响铃停止")
        stopNow()
    }

    override fun onDestroy() {
        stopNow()
        logScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val fullScreenPI = PendingIntent.getActivity(
            this, 8, Intent(this, RingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentPI = PendingIntent.getActivity(
            this, 9, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, AlarmScheduler.CHANNEL_RING)
            .setContentTitle("关键响铃")
            .setContentText("按规则触发的响铃，最长响 1 分钟")
            .setSmallIcon(R.drawable.ic_app)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(contentPI)
            .addAction(0, "停止", stopPendingIntent(this))
            .setFullScreenIntent(fullScreenPI, true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun beginPlaying() {
        stopPlaying()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        var started = false
        try {
            player = MediaPlayer().apply {
                setDataSource(this@AlarmRingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            started = true
        } catch (e: Exception) {
            player = null
            Log.e(TAG, "响铃播放失败", e)
            logScope.launch {
                Store.addLog(
                    this@AlarmRingService,
                    LogEntry(
                        System.currentTimeMillis(), "", "响铃",
                        "响铃播放失败：${e.message ?: e.javaClass.simpleName}", LogKind.RING_FAILED
                    )
                )
            }
        }
        if (!started) {
            // 播放失败不再靠震动硬撑：立即收尾，退出前台/页面并释放全部资源
            stopNow()
            return
        }
        vibrator = defaultVibrator()?.apply {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), intArrayOf(0, 255, 0), 0))
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightGuard:ring")
            .apply { acquire(MAX_RING_MS + 10_000) }
    }

    private fun defaultVibrator(): Vibrator? =
        getSystemService(VibratorManager::class.java)?.defaultVibrator

    /** 记录停止类日志（异步写；onDestroy 后静默放弃） */
    private fun logStop(text: String) {
        if (!logScope.isActive) return
        logScope.launch {
            try {
                Store.addLog(this@AlarmRingService, LogEntry(System.currentTimeMillis(), "", "", text, LogKind.STOP))
            } catch (e: Exception) {
                Log.e(TAG, "log stop failed", e)
            }
        }
    }

    private fun stopNow() {
        handler.removeCallbacks(autoStop)
        stopPlaying()
        sessionActive.value = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        try {
            stopSelf()
        } catch (e: Exception) {
        }
    }

    private fun stopPlaying() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
        } finally {
            player = null
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        } finally {
            vibrator = null
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        } finally {
            wakeLock = null
        }
    }
}
