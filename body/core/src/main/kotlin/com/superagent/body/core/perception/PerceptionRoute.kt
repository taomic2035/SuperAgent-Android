package com.superagent.body.core.perception

import com.superagent.common.ScreenResult

internal sealed interface PerceptionRoute {
    data object UseA11y : PerceptionRoute
    data object UseVision : PerceptionRoute
    data object VisionBlocked : PerceptionRoute
}

internal sealed interface VisionCaptureResult<out T> {
    val screen: ScreenResult

    data class Completed<T>(
        override val screen: ScreenResult,
        val capture: T?,
    ) : VisionCaptureResult<T>

    data class Blocked(override val screen: ScreenResult) : VisionCaptureResult<Nothing>
}

/** 纯路由策略；调用方负责先同步 fresh accessibility 前台状态。 */
internal fun perceptionRoute(
    mode: String,
    a11yScreen: ScreenResult,
    hasWebView: Boolean,
    inSensitiveSession: Boolean,
): PerceptionRoute = when {
    mode == "vision" && (a11yScreen.appPackage.isNullOrBlank() || inSensitiveSession) -> PerceptionRoute.VisionBlocked
    mode == "vision" -> PerceptionRoute.UseVision
    mode != "auto" -> PerceptionRoute.UseA11y
    (a11yScreen.marks?.size ?: 0) >= 5 && !hasWebView -> PerceptionRoute.UseA11y
    a11yScreen.appPackage.isNullOrBlank() || inSensitiveSession -> PerceptionRoute.VisionBlocked
    else -> PerceptionRoute.UseVision
}

/**
 * 不可逆截图操作的唯一窄闸门：fresh scan → 同步前台 → 判定 → capture。
 * 未知前台与敏感会话均 fail-closed，且不会调用 [capture]。
 */
internal fun <T> guardedVisionCapture(
    freshScan: () -> ScreenResult,
    synchronizeForeground: (String?) -> Unit,
    isSensitive: () -> Boolean,
    capture: () -> T?,
): VisionCaptureResult<T> {
    val scanned = freshScan()
    val foregroundPackage = scanned.appPackage?.trim()?.takeIf { it.isNotEmpty() }
    val normalized = scanned.copy(appPackage = foregroundPackage)
    synchronizeForeground(foregroundPackage)
    val sensitive = isSensitive()
    val synchronized = normalized.copy(sensitiveSession = sensitive)
    if (foregroundPackage == null || sensitive) {
        return VisionCaptureResult.Blocked(synchronized)
    }
    return VisionCaptureResult.Completed(synchronized, capture())
}
