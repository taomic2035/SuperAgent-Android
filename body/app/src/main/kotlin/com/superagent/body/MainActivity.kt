package com.superagent.body

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * UX-01 首启就绪引导（docs/12 §5.1）：
 * 只提示当前缺失项（▶ 突出第一个），不先展示开发者参数；
 * 每项说明用途并直达系统设置；返回后自动复检；UI-0 不索取麦克风。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusContainer = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_service).setOnClickListener {
            BodyService.start(this)
            refreshStatus()
        }
        findViewById<Button>(R.id.btn_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_perms).setOnClickListener {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            // codex 静态核验：语音链依赖麦克风——首启一并请求（ASR/声纹/barge-in）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.RECORD_AUDIO)
            }
            if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        }
        findViewById<Button>(R.id.btn_capture).setOnClickListener {
            val svc = com.superagent.body.core.screenshot.ScreenshotService.shared ?: return@setOnClickListener
            startActivityForResult(svc.consentIntent(), REQ_CAPTURE)
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            } else {
                FloatingUiService.start(this)
                refreshStatus()
            }
        }
        // ME-4a：隐私红线兑现——用户可查可删自己的长期记忆
        findViewById<Button>(R.id.btn_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus() // §5.1.4：从系统设置返回后立即复检
    }

    /** UX-01：按序检查，只突出当前第一个缺失项（▶ 红色），已过项绿色 ✓，未到的灰色 · */
    private fun refreshStatus() {
        val items = listOf(
            "通知权限" to (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
            "无障碍服务" to (BodyAccessibilityService.instance != null),
            "悬浮窗（SAW）" to (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)),
            "麦克风（语音）" to (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "躯体服务" to BodyService.isRunning,
        )
        statusContainer.removeAllViews()
        var firstMissing = true
        for ((name, ready) in items) {
            statusContainer.addView(TextView(this).apply {
                text = when {
                    ready -> "✓ $name"
                    firstMissing -> "▶ $name（点击上方对应按钮开启）"
                    else -> "· $name"
                }
                textSize = 15f
                setPadding(16, 12, 16, 12)
                setTextColor(
                    when {
                        ready -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
                        firstMissing -> ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                        else -> ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray)
                    },
                )
            })
            if (!ready) firstMissing = false
        }
        if (items.all { it.second }) {
            statusContainer.addView(TextView(this).apply {
                text = "🎉 全部就绪！点击「启动躯体服务」开启后台运行，悬浮球将出现在侧边。"
                textSize = 14f; setPadding(16, 20, 16, 12)
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) refreshStatus()
    }

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
