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
    private lateinit var statusText: TextView
    private lateinit var btnService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        btnService = findViewById(R.id.btn_service)
        val btnA11y = findViewById<Button>(R.id.btn_a11y)
        val btnPerms = findViewById<Button>(R.id.btn_perms)

        btnService.setOnClickListener {
            BodyService.start(this)
            btnService.text = "服务运行中"
            btnService.isEnabled = false
            refreshStatus()
        }
        btnA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnPerms.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val a11y = BodyAccessibilityService.instance != null
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        statusText.text = buildString {
            append("服务：${if (btnService.isEnabled.not()) "运行中" else "未启动"}\n")
            append("无障碍：${if (a11y) "✓" else "✗"}\n")
            append("麦克风：${if (mic) "✓" else "✗"}")
        }
    }
}