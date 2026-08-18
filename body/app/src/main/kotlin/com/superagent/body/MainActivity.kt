package com.superagent.body

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(androidx.appcompat.R.layout.abc_screen_simple)
        val root = findViewById<android.widget.FrameLayout>(androidx.appcompat.R.id.action_bar_root)
        root.removeAllViews()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(container)

        val title = TextView(this).apply {
            text = "超级AI助手 · 躯体"
            textSize = 22f
        }
        val status = TextView(this).apply {
            text = statusText()
            textSize = 14f
        }
        val btnStart = Button(this).apply { text = "启动躯体服务（前台）" }
        val btnA11y = Button(this).apply { text = "打开无障碍设置" }
        val btnPerms = Button(this).apply { text = "授权麦克风/通知" }

        btnStart.setOnClickListener {
            BodyService.start(this)
            status.text = statusText()
        }
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnPerms.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        }

        container.addView(title)
        container.addView(status)
        container.addView(btnStart)
        container.addView(btnA11y)
        container.addView(btnPerms)
    }

    private fun statusText(): String {
        val a11y = BodyAccessibilityService.instance != null
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return "无障碍服务：${if (a11y) "已连接" else "未开启"}\n麦克风：${if (mic) "已授权" else "未授权"}"
    }
}