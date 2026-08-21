package com.superagent.body

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ME-4a 记忆管理入口（docs/15 §6 隐私红线兑现：「用户可查可删」）。
 * 数据走本地 RPC（127.0.0.1:8765 + files/token——同进程读自己的 token，复用全部鉴权与契约）。
 * 删除 = memory.forget（body 物理删，用户删除权 > Iron Law）。
 */
class MemoryActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply { text = "加载中…"; textSize = 14f; setPadding(0, 16, 0, 16) }
        root.addView(TextView(this).apply {
            text = "我的记忆"; textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "助手对你的长期记忆（本地存储）。删除即彻底移除。"
            textSize = 13f; setPadding(0, 8, 0, 16); setTextColor(Color.GRAY)
        })
        root.addView(hint)
        root.addView(listContainer)
        scroll.addView(root)
        setContentView(scroll)
        refresh()
    }

    private fun token(): String? =
        File(filesDir, "token").takeIf { it.isFile }?.readText()?.trim()

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val t = token() ?: return null
        val conn = (URL("http://127.0.0.1:8765/rpc").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $t")
            doOutput = true
        }
        conn.outputStream.use { it.write(JSONObject(mapOf("id" to 1, "method" to method, "params" to params)).toString().toByteArray()) }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(body)
    }

    private fun refresh() {
        thread {
            val entries = try {
                val resp = rpc("memory.export", JSONObject()) ?: return@thread runOnUiThread { hint.text = "躯体服务未运行" }
                if (!resp.optBoolean("ok")) return@thread runOnUiThread { hint.text = "读取失败：${resp.optJSONObject("error")?.optString("message")}" }
                resp.optJSONArray("result") ?: return@thread runOnUiThread { hint.text = "无记忆" }
            } catch (e: Exception) {
                return@thread runOnUiThread { hint.text = "躯体服务未运行（${e.message}）" }
            }
            val active = (0 until entries.length()).map { entries.optJSONObject(it)!! }.filter { !it.optBoolean("revoked") }
            runOnUiThread {
                listContainer.removeAllViews()
                if (active.isEmpty()) {
                    hint.text = "暂无记忆——对助手说「记住……」即会出现在这里"
                    return@runOnUiThread
                }
                hint.text = "共 ${active.size} 条"
                for (e in active) addRow(e)
            }
        }
    }

    private fun addRow(e: JSONObject) {
        val id = e.optLong("id")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 12) }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "【${e.optString("kind")}】${e.optString("topic")}\n${e.optString("content")}\n信度 ${"%.1f".format(e.optDouble("confidence"))} · 命中 ${e.optInt("hits")} 次"
            textSize = 14f
            setPadding(0, 0, 16, 0)
        }
        val del = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = "删除"; textSize = 12f
            setOnClickListener {
                isEnabled = false
                thread {
                    val ok = try {
                        rpc("memory.forget", JSONObject().put("id", id))?.optBoolean("ok") == true
                    } catch (_: Exception) { false }
                    runOnUiThread {
                        Toast.makeText(this@MemoryActivity, if (ok) "已删除" else "删除失败（服务未运行？）", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
        }
        row.addView(label); row.addView(del)
        listContainer.addView(row)
        listContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#22000000"))
        })
    }
}
