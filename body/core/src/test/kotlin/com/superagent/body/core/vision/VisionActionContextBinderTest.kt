package com.superagent.body.core.vision

import com.superagent.common.ScreenResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VisionActionContextBinderTest {
    @Test
    fun `screen with app package receives issued token`() {
        var issueCount = 0
        val registry = VisionActionContextRegistry(
            tokenFactory = {
                issueCount++
                "issued-token"
            },
        )

        val result = bindVisionActionContext(screen(appPackage = "com.example.safe"), "shot.jpg", 1080, 2400, registry)

        assertEquals("issued-token", result.visionActionToken)
        assertEquals(1, issueCount)
        assertEquals(
            VisionActionContextRegistry.Validation.Valid,
            registry.validate(result.visionActionToken, "com.example.safe", "screen-signature"),
        )
    }

    @Test
    fun `screen without app package receives no token and does not issue`() {
        var issueCount = 0
        val registry = VisionActionContextRegistry(
            tokenFactory = {
                issueCount++
                "must-not-be-issued"
            },
        )

        val result = bindVisionActionContext(screen(appPackage = null), "shot.jpg", 1080, 2400, registry)

        assertNull(result.visionActionToken)
        assertEquals(0, issueCount)
    }

    private fun screen(appPackage: String?): ScreenResult = ScreenResult(
        signature = "screen-signature",
        kind = "vision",
        blank = false,
        appPackage = appPackage,
    )
}
