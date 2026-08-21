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
                // C-07：由权威 UI 状态在事件发布前决定受理/拒绝；离线指令不进 EventBus。
                val accepted = UiBus.stateController?.submitTextInput(text) == true
                if (!accepted) {
                    Toast.makeText(this@CommandInputActivity, "未发送·大脑离线", Toast.LENGTH_SHORT).show()
                }
                finish()
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
}
