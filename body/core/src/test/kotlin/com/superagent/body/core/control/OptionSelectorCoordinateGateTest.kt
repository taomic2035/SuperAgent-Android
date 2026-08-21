package com.superagent.body.core.control

import com.superagent.body.core.security.ActionGate
import com.superagent.common.ActionResult
import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OptionSelectorCoordinateGateTest {

    @Test
    fun `verifySelected false blocks benign label when final coordinate gate detects commit`() = runBlocking {
        var tapCount = 0
        var gatedAt: Pair<Int, Int>? = null
        val violation = ActionGate.Violation.Commit("提交订单")
        val selector = OptionSelector(
            perceive = { screen("配送方式", 120, 240) },
            coordinateGate = { x, y ->
                gatedAt = x to y
                violation
            },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("配送方式", verifySelected = false)

        assertSame(violation, (result as SelectionResult.GateBlocked).violation)
        assertEquals(120 to 240, gatedAt)
        assertEquals(0, tapCount)
    }

    @Test
    fun `verifySelected true blocks before tapping when final coordinate gate detects commit`() = runBlocking {
        var tapCount = 0
        var gatedAt: Pair<Int, Int>? = null
        val violation = ActionGate.Violation.Commit("提交订单")
        val selector = OptionSelector(
            perceive = { screen("配送方式", 120, 240) },
            coordinateGate = { x, y ->
                gatedAt = x to y
                violation
            },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("配送方式", verifySelected = true)

        assertSame(violation, (result as SelectionResult.GateBlocked).violation)
        assertEquals(120 to 240, gatedAt)
        assertEquals(0, tapCount)
    }

    @Test
    fun `sensitive session violation preserves reason label and nonce`() = runBlocking {
        var tapCount = 0
        val violation = ActionGate.Violation.SensitiveSession("发送", "nonce-123")
        val selector = OptionSelector(
            perceive = { screen("普通选项", 120, 240) },
            coordinateGate = { _, _ -> violation },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("普通选项")

        val blocked = result as SelectionResult.GateBlocked
        assertSame(violation, blocked.violation)
        assertEquals("sensitive_session", blocked.violation.reason)
        assertEquals("发送", blocked.violation.label)
        assertEquals("nonce-123", (blocked.violation as ActionGate.Violation.SensitiveSession).nonce)
        assertEquals(0, tapCount)
    }

    @Test
    fun `commit label returns typed gate violation before perception`() = runBlocking {
        var perceiveCount = 0
        var tapCount = 0
        val selector = OptionSelector(
            perceive = {
                perceiveCount++
                screen("提交订单", 120, 240)
            },
            coordinateGate = { _, _ -> null },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("提交订单")

        val violation = (result as SelectionResult.GateBlocked).violation
        assertTrue(violation is ActionGate.Violation.Commit)
        assertEquals("提交订单", violation.label)
        assertEquals(0, perceiveCount)
        assertEquals(0, tapCount)
    }

    @Test
    fun `verifySelected false taps exactly once when final coordinate gate allows`() = runBlocking {
        var tapCount = 0
        val selector = OptionSelector(
            perceive = { screen("配送方式", 120, 240) },
            coordinateGate = { _, _ -> null },
            tap = { x, y ->
                tapCount++
                assertEquals(120, x)
                assertEquals(240, y)
                ActionResult(true)
            },
        )

        val result = selector.select("配送方式", verifySelected = false)

        assertTrue((result as SelectionResult.Completed).actionResult.located)
        assertEquals(1, tapCount)
    }

    private fun screen(label: String, x: Int, y: Int): ScreenResult = ScreenResult(
        signature = "screen-signature",
        kind = "a11y",
        blank = false,
        marks = listOf(Mark(0, label, Point(x, y))),
    )
}
