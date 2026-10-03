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
import com.nightguard.app.R
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.ui.MainActivity

/**
 * 响铃前台服务（shortService 类型，系统强制分钟级超时，与“响 1 分钟自动停”契合）。
 * 由精确闹钟的 Receiver 以 startForegroundService 拉起（官方允许的后台启动豁免场景）。
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_RING = "com.nightguard.app.action.RING"
        const val ACTION_STOP = "com.nightguard.app.action.STOP"
        private const val NOTIF_ID = 42
        private const val MAX_RING_MS = 60_000L

        fun ringIntent(context: Context): Intent =
            Intent(context, AlarmRingService::class.java).setAction(ACTION_RING)

        fun stopPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context, 7,
                Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopNow() }
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlarmScheduler.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论哪个动作都先进前台，满足 startForegroundService 的合规要求
        startAsForeground()
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        beginPlaying()
        handler.postDelayed(autoStop, MAX_RING_MS)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        stopNow()
    }

    override fun onDestroy() {
        stopNow()
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
        } catch (e: Exception) {
            player = null
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

    private fun stopNow() {
        handler.removeCallbacks(autoStop)
        stopPlaying()
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
