package com.example.senar.breath

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class BreathService : Service() {

    companion object {
        const val ACTION_START = "breath_start"
        const val ACTION_STOP  = "breath_stop"
        private const val CH_ID = "breath_monitor"
        private const val NOTI_ID = 3001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: BreathRealtimeManager? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNotification("呼吸监测运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInternal()
            ACTION_STOP  -> stopInternal()
            else -> startInternal()
        }
        return START_STICKY
    }
    private fun startInternal() {
        if (manager != null) return

        // ✅ 双模型引擎：实时=uosas_mobile.ts，离线=mobile_uosas_distill.ts
        val engine = BreathInferenceEngine(ctx = this)

        manager = BreathRealtimeManager(
            ctx = this,
            engine = engine,
            scope = serviceScope
        ).also { it.start() }

        BreathRealtimeStore.update {
            it.copy(
                running = true,
                status = "服务运行中",
                statusText = "服务运行中"
            )
        }
    }


    private fun stopInternal() {
        manager?.stop()
        manager = null

        BreathRealtimeStore.update {
            it.copy(
                running = false,
                status = "服务已停止",
                statusText = "服务已停止"
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        manager?.stop()
        manager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "Breath Monitor", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Senar 呼吸监测")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
