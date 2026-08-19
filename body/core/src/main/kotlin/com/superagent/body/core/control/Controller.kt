package com.superagent.body.core.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.superagent.common.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class Controller(
    private val context: Context,
    private val accessibilityService: () -> AccessibilityService?,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * 手势回调线程（P0-16/P0-17 修复，2026-08-19 真机实证）：
     * 回调若走主线程，主线程 latch.await 自等待 = 自死锁恒超时，且超时后排队手势迟发乱点
     * （蓝牙配对弹窗实锤）。回调改到独立 HandlerThread，主线程等待不再堵死自己。
     */
    private val gestureThread = android.os.HandlerThread("gesture-cb").apply { start() }
    private val gestureHandler = android.os.Handler(gestureThread.looper)

    /** 点击像素坐标（与 perceive.screen 返回的 bounds/center 同单位）。带 2px 微位移（Kestrel 实证：部分系统需先移动再抬起）。 */
    suspend fun tap(x: Int, y: Int): ActionResult = dispatchGesture(x, y, x + 2, y + 2, 40L)

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): ActionResult =
        dispatchGesture(x, y, x, y, durationMs)

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 300): ActionResult {
        val svc = accessibilityService() ?: return ActionResult(false, null, "无障碍服务未连接")
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatch(svc, gesture)
    }

    private suspend fun dispatchGesture(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): ActionResult {
        val svc = accessibilityService() ?: return ActionResult(false, null, "无障碍服务未连接")
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatch(svc, gesture)
    }

    private suspend fun dispatch(svc: AccessibilityService, gesture: GestureDescription): ActionResult {
        val completed = java.util.concurrent.CountDownLatch(1)
        var success = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                completed.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.countDown()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 已在主线程（skill.run 回放路径）：直接派发，不得再 launch(Main) 排队到自己身后
            svc.dispatchGesture(gesture, callback, gestureHandler)
        } else {
            scope.launch { svc.dispatchGesture(gesture, callback, gestureHandler) }
        }
        completed.await(2, TimeUnit.SECONDS)
        return ActionResult(success, null, if (success) null else "手势被系统取消")
    }

    /** 向聚焦输入框注入文本（a11y SET_TEXT 优先，失败回退剪贴板）。 */
    suspend fun typeText(text: String): ActionResult {
        val svc = accessibilityService() ?: return ActionResult(false, null, "无障碍服务未连接")
        val focused = findFocused(svc.rootInActiveWindow)
        val target = focused ?: findEditable(svc.rootInActiveWindow)
            ?: return ActionResult(false, null, "没有可用的输入框")
        val bundle = Bundle().apply {
            if (Build.VERSION.SDK_INT >= 21) putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val done = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        return ActionResult(done, null, if (done) null else "SET_TEXT 失败")
    }

    fun back(): ActionResult {
        val svc = accessibilityService() ?: return ActionResult(false, null, "无障碍服务未连接")
        return ActionResult(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    fun home(): ActionResult {
        val svc = accessibilityService() ?: return ActionResult(false, null, "无障碍服务未连接")
        return ActionResult(svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
    }

    /**
     * 启动应用：重置到干净首屏（先强制停止再冷启）。
     * Android 12+ BAL：body 不在前台时 startActivity 会被系统静默拦截（logcat Abort background
     * activity starts）——intent 解析成功不代表切换发生。以前台实际包名为准，不谎报 located。
     */
    suspend fun launch(pkg: String): ActionResult {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return ActionResult(false, null, "包不存在: $pkg")
        return try {
            context.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            delay(800) // 冷启余量后以 a11y 实际前台为准
            val fg = accessibilityService()?.rootInActiveWindow?.packageName?.toString()
            if (fg == pkg) {
                ActionResult(true)
            } else {
                ActionResult(false, null, "后台启动被 Android BAL 限制（body 不在前台），当前前台仍是 $fg")
            }
        } catch (e: Exception) {
            return ActionResult(false, null, "启动失败: ${e.message}")
        }
    }

    private fun findFocused(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isFocused) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }
}