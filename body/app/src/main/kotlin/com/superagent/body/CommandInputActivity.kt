package com.superagent.body

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.superagent.body.core.ui.UiBus
import com.superagent.body.core.ui.UiStateController

/**
 * 半透明文字输入 Activity（docs/12 §4.C：独立输入表面——只有打字时获取键盘焦点，
 * 执行 HUD 永不抢焦点）。空文本/取消/返回不产生任务（§5.2.3）；发送只入队一次。
 */
class CommandInputActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(32, 0, 32, 64)
            background = GradientDrawable().apply { setColor(Color.argb(120, 10, 10, 16)); cornerRadius = 0f }
        }
        val title = TextView(this).apply {
            text = "指令"
            textSize = 14f; setTextColor(Color.WHITE); setPadding(8, 0, 0, 8)
        }
        val input = EditText(this).apply {
            hint = "想让助手做什么？"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 16f
            background = GradientDrawable().apply { setColor(Color.argb(230, 30, 30, 40)); cornerRadius = 20f }
            setPadding(24, 18, 24, 18)
            maxLines = 4
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        var sent = false
        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "发送"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty() || sent) return@setOnClickListener
                sent = true
                // 权威状态返回结构化原因：拒绝时保留 Activity 与草稿，允许用户处理后重试。
                val result = UiBus.stateController?.submitTextInput(text)
                when (result) {
                    UiStateController.TextInputResult.ACCEPTED -> finish()
                    UiStateController.TextInputResult.REJECTED_OFFLINE -> reject("未发送 · 大脑离线") { sent = false }
                    UiStateController.TextInputResult.REJECTED_CONTROL_PENDING -> reject("未发送 · 请先完成暂停或停止") { sent = false }
                    UiStateController.TextInputResult.REJECTED_PAUSED -> reject("未发送 · 请先继续或停止当前任务") { sent = false }
                    UiStateController.TextInputResult.REJECTED_WAITING_USER -> reject("未发送 · 请先处理当前任务") { sent = false }
                    UiStateController.TextInputResult.REJECTED_EMPTY -> sent = false
                    null -> reject("未发送 · 服务尚未就绪") { sent = false }
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title)
        root.addView(input)
        root.addView(row)
        setContentView(root)
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun reject(message: String, reset: () -> Unit) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        reset()
    }
}
