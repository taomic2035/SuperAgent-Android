package com.superagent.body

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.superagent.body.core.ui.OverlayGate
import com.superagent.body.core.ui.UiBus
import com.superagent.body.core.ui.UiStateController
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * 悬浮交互层（docs/12 §4 三表面，BD-10）：
 * A 侧边控制球——唯一常驻可触摸区域（拖动停靠边缘；点击按状态分发）
 * B 穿透状态条——FLAG_NOT_FOCUSABLE|NOT_TOUCHABLE 只看不可点（alpha 0.7 留系统遮挡阈值余量）
 * C 控制面板——最近 3-5 步 + 停止/关闭（完整"打开前安全暂停"语义随 I3）
 */
class FloatingUiService : android.app.Service() {

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var strip: TextView? = null
    private var panel: LinearLayout? = null
    private lateinit var ui: UiStateController
    private var panelOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bus = UiBus.events ?: run { stopSelf(); return }
        ui = UiStateController(bus)
        ui.start()
        com.superagent.body.core.ui.UiBus.stateController = ui // U2-H04：通知兜底访问
        ui.subscribe { snap -> android.os.Handler(mainLooper).post { render(snap) } }
        addBall()
        addStrip()
        OverlayGate.register(hide = { hideAll() }, restore = { showCurrent() })
    }

    override fun onDestroy() {
        OverlayGate.unregister()
        runCatching { ball?.let { wm.removeView(it) } }
        runCatching { strip?.let { wm.removeView(it) } }
        runCatching { panel?.let { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------- A. 控制球 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBall() {
        val dot = TextView(this).apply {
            text = "◎"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 14)
            background = GradientDrawable().apply { setColor(Color.argb(210, 30, 30, 40)); cornerRadius = 64f }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.START or Gravity.TOP; x = 0; y = 600 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        dot.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(dot, params) }; true
                }
                MotionEvent.ACTION_UP -> {
                    params.x = if (params.x < resources.displayMetrics.widthPixels / 2) 0 else resources.displayMetrics.widthPixels - dot.width
                    runCatching { wm.updateViewLayout(dot, params) }
                    if (!moved) onBallClick()
                    true
                }
                else -> false
            }
        }
        wm.addView(dot, params)
        ball = dot
    }

    private fun onBallClick() {
        when (ui.state) {
            UiStateController.UiState.IDLE, UiStateController.UiState.OFFLINE, UiStateController.UiState.MINI ->
                startActivity(Intent(this, CommandInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> togglePanel()
        }
    }

    // ---------- B. 穿透状态条 ----------

    private fun addStrip() {
        val tv = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.argb(178, 20, 20, 28)); cornerRadius = 24f }
            setPadding(28, 12, 28, 12)
            maxLines = 2
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
        wm.addView(tv, params)
        strip = tv
    }

    // ---------- C. 控制面板 ----------

    private fun togglePanel() {
        if (panelOpen) { closePanel(); return }
        val snap = ui.snapshot()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(225, 24, 24, 32)); cornerRadius = 28f }
            setPadding(40, 32, 40, 32)
        }
        root.addView(TextView(this).apply {
            text = stateLabel(snap.state)
            textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 16)
        })
        if (snap.recentSteps.isEmpty()) {
            root.addView(TextView(this).apply { text = "（尚无步骤）"; textSize = 13f; setTextColor(Color.GRAY) })
        } else {
            snap.recentSteps.takeLast(5).reversed().forEach { s ->
                root.addView(TextView(this).apply {
                    text = "· $s"; textSize = 13f; setTextColor(Color.LTGRAY); maxLines = 2; setPadding(0, 6, 0, 6)
                })
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 0) }
        row.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                UiBus.events?.emit("voice", buildJsonObject { put("kind", "stop_request") })
                closePanel()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener { closePanel() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.8).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(root, params)
        panel = root
        panelOpen = true
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelOpen = false
    }

    // ---------- 渲染 ----------

    private fun render(snap: UiStateController.Snapshot) {
        strip?.let { s ->
            // U2-#33/#34：所有非 MINI 状态都应可见（含 OFFLINE/STOPPED——离线反馈是 UX-11 判据）
            val show = snap.state !in setOf(UiStateController.UiState.MINI)
            s.visibility = if (show) View.VISIBLE else View.GONE
            s.text = "${stateLabel(snap.state)}${if (snap.currentStep.isBlank()) "" else " · ${snap.currentStep}"}"
        }
    }

    private fun stateLabel(s: UiStateController.UiState): String = when (s) {
        UiStateController.UiState.OFFLINE -> "离线"
        UiStateController.UiState.MINI -> "待命"
        UiStateController.UiState.IDLE -> "就绪"
        UiStateController.UiState.THINKING -> "理解中"
        UiStateController.UiState.RUNNING -> "执行中"
        UiStateController.UiState.PAUSING -> "正在暂停…"
        UiStateController.UiState.PAUSED -> "已暂停"
        UiStateController.UiState.STOPPING -> "正在停止…"
        UiStateController.UiState.STOPPED -> "已停止"
        UiStateController.UiState.AWAITING_CONFIRM -> "等待确认"
        UiStateController.UiState.BLOCKED -> "需要处理"
        UiStateController.UiState.COMPLETED -> "已完成"
        UiStateController.UiState.FAILED -> "失败"
    }

    // U2-B06：保存截图前三表面的精确可见状态，采集后按原样恢复（不是只恢复控制球）
    private var savedBallVisible = true
    private var savedStripVisible = false
    private var savedPanelOpen = false

    private fun hideAll() {
        android.os.Handler(mainLooper).post {
            savedBallVisible = ball?.visibility == View.VISIBLE
            savedStripVisible = strip?.visibility == View.VISIBLE
            savedPanelOpen = panelOpen
            ball?.visibility = View.GONE
            strip?.visibility = View.GONE
            if (panelOpen) closePanel()
        }
    }

    private fun showCurrent() {
        android.os.Handler(mainLooper).post {
            ball?.visibility = if (savedBallVisible) View.VISIBLE else View.GONE
            strip?.visibility = if (savedStripVisible) View.VISIBLE else View.GONE
            // 面板不自动恢复（用户可能已在截图期间改变意图；状态条恢复由下一事件驱动）
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, FloatingUiService::class.java))
        }
    }
}
