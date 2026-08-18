package com.superagent.body

import android.accessibilityservice.AccessibilityService

/** 供 core 读取当前活动窗口根节点。 */
class BodyAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BodyAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}