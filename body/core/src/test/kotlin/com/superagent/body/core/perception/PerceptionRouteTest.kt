package com.superagent.body.core.perception

import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import com.superagent.body.core.security.SensitiveSessionTracker
import com.superagent.body.core.screenshot.captureGeometryMatches
import com.superagent.body.core.screenshot.captureGeometryRemainedStable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerceptionRouteTest {

    @Test
    fun `capture geometry rejects rotation or resize after projection attach`() {
        assertTrue(captureGeometryMatches(1080, 2400, 1080, 2400))
        assertFalse(captureGeometryMatches(1080, 2400, 2400, 1080))
        assertFalse(captureGeometryMatches(1080, 2400, 900, 2000))
    }

    @Test
    fun `capture geometry rejects rotation or resize while waiting for a frame`() {
        assertTrue(captureGeometryRemainedStable(1080, 2400, 1080, 2400, 1080, 2400))
        assertFalse(captureGeometryRemainedStable(1080, 2400, 1080, 2400, 2400, 1080))
        assertFalse(captureGeometryRemainedStable(1080, 2400, 1080, 2400, 900, 2000))
    }

    @Test
    fun `explicit vision is blocked in synchronized sensitive session`() {
        val route = perceptionRoute("vision", screen(markCount = 1), hasWebView = false, inSensitiveSession = true)

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `degraded auto is blocked in synchronized sensitive session`() {
        val route = perceptionRoute("auto", screen(markCount = 1), hasWebView = false, inSensitiveSession = true)

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `healthy auto uses accessibility`() {
        val route = perceptionRoute("auto", screen(markCount = 5), hasWebView = false, inSensitiveSession = false)

        assertEquals(PerceptionRoute.UseA11y, route)
    }

    @Test
    fun `degraded auto falls back to vision`() {
        val route = perceptionRoute("auto", screen(markCount = 4), hasWebView = false, inSensitiveSession = false)

        assertEquals(PerceptionRoute.UseVision, route)
    }

    @Test
    fun `WebView auto falls back to vision`() {
        val route = perceptionRoute("auto", screen(markCount = 5), hasWebView = true, inSensitiveSession = false)

        assertEquals(PerceptionRoute.UseVision, route)
    }

    @Test
    fun `explicit vision fails closed when foreground package is unknown`() {
        val route = perceptionRoute(
            "vision",
            screen(markCount = 1, appPackage = null),
            hasWebView = false,
            inSensitiveSession = false,
        )

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `degraded auto fails closed when foreground package is unknown`() {
        val route = perceptionRoute(
            "auto",
            screen(markCount = 1, appPackage = null),
            hasWebView = false,
            inSensitiveSession = false,
        )

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `explicit vision fails closed when foreground package is blank`() {
        val route = perceptionRoute(
            "vision",
            screen(markCount = 1, appPackage = "  "),
            hasWebView = false,
            inSensitiveSession = false,
        )

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `degraded auto fails closed when foreground package is blank`() {
        val route = perceptionRoute(
            "auto",
            screen(markCount = 1, appPackage = ""),
            hasWebView = false,
            inSensitiveSession = false,
        )

        assertEquals(PerceptionRoute.VisionBlocked, route)
    }

    @Test
    fun `capture gate synchronizes switched sensitive app and performs no capture`() {
        var sensitive = false
        var captureCount = 0
        val events = mutableListOf<String>()

        val result = guardedVisionCapture(
            freshScan = {
                events += "scan"
                screen(markCount = 1, appPackage = "com.tencent.mm")
            },
            synchronizeForeground = {
                events += "sync"
                sensitive = true
            },
            isSensitive = { sensitive },
            capture = {
                events += "capture"
                captureCount++
                "shot.jpg"
            },
        )

        assertTrue(result is VisionCaptureResult.Blocked)
        assertEquals(listOf("scan", "sync"), events)
        assertEquals(0, captureCount)
    }

    @Test
    fun `capture gate fails closed on unknown foreground with zero export`() {
        var captureCount = 0

        val result = guardedVisionCapture(
            freshScan = { screen(markCount = 1, appPackage = null) },
            synchronizeForeground = {},
            isSensitive = { false },
            capture = {
                captureCount++
                "shot.jpg"
            },
        )

        assertTrue(result is VisionCaptureResult.Blocked)
        assertEquals(0, captureCount)
    }

    @Test
    fun `blank foreground cannot clear prior sensitive state and trigger capture`() {
        val tracker = SensitiveSessionTracker().also { it.onForeground("com.tencent.mm") }
        var captureCount = 0

        val result = guardedVisionCapture(
            freshScan = { screen(markCount = 1, appPackage = " ") },
            synchronizeForeground = tracker::onForeground,
            isSensitive = { tracker.inSensitiveSession },
            capture = {
                captureCount++
                "shot.jpg"
            },
        )

        assertTrue(result is VisionCaptureResult.Blocked)
        assertEquals(0, captureCount)
    }

    @Test
    fun `capture gate orders fresh scan sync and one allowed capture`() {
        val events = mutableListOf<String>()

        val result = guardedVisionCapture(
            freshScan = {
                events += "scan"
                screen(markCount = 1)
            },
            synchronizeForeground = { events += "sync" },
            isSensitive = { false },
            capture = {
                events += "capture"
                "shot.jpg"
            },
        )

        val completed = result as VisionCaptureResult.Completed
        assertEquals(listOf("scan", "sync", "capture"), events)
        assertEquals("shot.jpg", completed.capture)
        assertFalse(completed.screen.sensitiveSession)
    }

    @Test
    fun `force refresh bypasses otherwise valid 300ms cache entry`() {
        assertFalse(
            shouldUsePerceptionCache(
                forceRefresh = true,
                keyMatches = true,
                cacheAgeMs = 100,
                cachedBlank = false,
            ),
        )
    }

    @Test
    fun `ordinary perception retains valid 300ms cache entry`() {
        assertTrue(
            shouldUsePerceptionCache(
                forceRefresh = false,
                keyMatches = true,
                cacheAgeMs = 100,
                cachedBlank = false,
            ),
        )
    }

    private fun screen(markCount: Int, appPackage: String? = "com.example.safe"): ScreenResult = ScreenResult(
        signature = "screen",
        kind = "a11y",
        blank = false,
        marks = List(markCount) { index -> Mark(index, "item-$index", Point(index, index)) },
        appPackage = appPackage,
    )
}
