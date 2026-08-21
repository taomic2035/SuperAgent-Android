package com.superagent.body.core.control

import com.superagent.body.core.security.ActionGate
import com.superagent.common.ActionResult
import com.superagent.common.Mark
import com.superagent.common.Point
import com.superagent.common.ScreenResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ActionExecutorSelectionMappingTest {

    @Test
    fun `selection gate mapping preserves exact sensitive session violation from selector`() = runBlocking {
        val violation = ActionGate.Violation.SensitiveSession("发送", "nonce-123")
        val selector = OptionSelector(
            perceive = {
                ScreenResult(
                    signature = "screen-signature",
                    kind = "a11y",
                    blank = false,
                    marks = listOf(Mark(0, "普通选项", Point(120, 240))),
                )
            },
            coordinateGate = { _, _ -> violation },
            tap = { _, _ -> ActionResult(true) },
        )

        val selection = selector.select("普通选项")
        val result = ActionExecutor.selectionGateResult(selection)

        assertSame(violation, (result as ActionExecutor.Result.GateBlocked).violation)
    }

    @Test
    fun `completed selection has no gate result`() {
        val selection = SelectionResult.Completed(ActionResult(true))

        assertNull(ActionExecutor.selectionGateResult(selection))
    }
}
