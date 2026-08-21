package com.superagent.body.core.vision

import com.superagent.common.ScreenResult

fun bindVisionActionContext(
    screen: ScreenResult,
    screenshotRef: String,
    screenWidth: Int,
    screenHeight: Int,
    registry: VisionActionContextRegistry,
): ScreenResult {
    val appPackage = screen.appPackage ?: return screen.copy(visionActionToken = null)
    val token = registry.issue(
        ref = screenshotRef,
        pkg = appPackage,
        signature = screen.signature,
        width = screenWidth,
        height = screenHeight,
    )
    return screen.copy(visionActionToken = token)
}
