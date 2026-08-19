package com.superagent.body.core.ui

/**
 * OverlayGate（docs/12 §3.1 复用 Kestrel）：截图/视觉感知前隐藏全部 overlay，
 * 采集后恢复——UI 不污染视觉感知，也不遮住模型要找的控件（UX-10 判据）。
 * 悬浮层服务注册 hide/restore 回调；无注册时为空操作（通知兜底场景）。
 */
object OverlayGate {
    @Volatile
    private var hideAction: (() -> Unit)? = null
    @Volatile
    private var restoreAction: (() -> Unit)? = null

    fun register(hide: () -> Unit, restore: () -> Unit) {
        hideAction = hide
        restoreAction = restore
    }

    fun unregister() {
        hideAction = null
        restoreAction = null
    }

    /** 截图前调用；调用方应在 hide 后留一帧余量（视图生效需 post 到主线程）。 */
    fun hide() {
        runCatching { hideAction?.invoke() }
    }

    fun restore() {
        runCatching { restoreAction?.invoke() }
    }
}
