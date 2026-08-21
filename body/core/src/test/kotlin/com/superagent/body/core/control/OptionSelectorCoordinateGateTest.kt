package com.superagent.body.core.control

import com.superagent.body.core.security.ActionGate
import com.superagent.common.ActionResult
import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OptionSelectorCoordinateGateTest {

    @Test
    fun `verifySelected false blocks benign label when final coordinate gate detects commit`() = runBlocking {
        var tapCount = 0
        val selector = OptionSelector(
            perceive = { screen("配送方式", 120, 240) },
            coordinateGate = { _, _ -> ActionGate.Violation.Commit("提交订单") },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("配送方式", verifySelected = false)

        assertFalse(result.located)
        assertEquals("GATE_BLOCKED:提交订单", result.note)
        assertEquals(0, tapCount)
    }

    @Test
    fun `verifySelected true blocks before tapping when final coordinate gate detects commit`() = runBlocking {
        var tapCount = 0
        val selector = OptionSelector(
            perceive = { screen("配送方式", 120, 240) },
            coordinateGate = { _, _ -> ActionGate.Violation.Commit("提交订单") },
            tap = { _, _ ->
                tapCount++
                ActionResult(true)
            },
        )

        val result = selector.select("配送方式", verifySelected = true)

        assertFalse(result.located)
        assertEquals("GATE_BLOCKED:提交订单", result.note)
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

        assertTrue(result.located)
        assertEquals(1, tapCount)
    }

    private fun screen(label: String, x: Int, y: Int): ScreenResult = ScreenResult(
        signature = "screen-signature",
        kind = "a11y",
        blank = false,
        marks = listOf(Mark(0, label, Point(x, y))),
    )
}
