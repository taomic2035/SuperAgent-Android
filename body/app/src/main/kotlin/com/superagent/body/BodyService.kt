package com.superagent.body

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.superagent.body.core.BodyContext
import com.superagent.body.core.BodyCore
import com.superagent.body.core.BodySettings
import com.superagent.body.core.TokenSecurity

class BodyService : Service() {
    private var core: BodyCore? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val token = TokenSecurity.loadOrGenerate(this)
        BodyContext.init(this, BodySettings(token = token))
        core = BodyCore(this) { BodyAccessibilityService.instance }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        core?.start()
        // UI-0：SAW 已授权则常驻悬浮层（控制球+状态条）；未授权由主界面引导
        FloatingUiService.start(this)
        return START_STICKY
    }

    private val running: Boolean get() = (core != null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        core?.stop()
        core = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "躯体服务", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = android.app.PendingIntent.getService(
            this, 1,
            Intent(this, BodyService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerIntent = android.app.PendingIntent.getBroadcast(
            this, 2,
            com.superagent.body.core.voice.VoiceLoop.triggerIntent(this),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        // U2-H04：通知兜底显示用户状态（非开发者 RPC 地址）；有状态控制器时动态取
        val statusText = com.superagent.body.core.ui.UiBus.stateController?.notificationText()
            ?: "就绪 · 点击悬浮球或通知"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("超级AI助手")
            .setContentText(statusText)
            .setOngoing(true)
            .addAction(0, "说话", triggerIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL = "body-service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.superagent.body.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BodyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}