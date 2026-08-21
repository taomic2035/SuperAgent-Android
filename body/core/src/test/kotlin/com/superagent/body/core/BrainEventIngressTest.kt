package com.superagent.body.core

import com.superagent.common.BrainEvent
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrainEventIngressTest {

    @Test
    fun `未知 state 被拒且不 emit`() {
        assertRejected(eventJson(state = "future_state"))
    }

    @Test
    fun `未知 requiresUser 被拒且不 emit`() {
        assertRejected(eventJson(requiresUser = "future_user"))
    }

    @Test
    fun `未知 resultKind 被拒且不 emit`() {
        assertRejected(eventJson(resultKind = "future_result"))
    }

    @Test
    fun `空白 taskId 被拒且不 emit`() {
        assertRejected(eventJson(taskId = "   "))
    }

    @Test
    fun `负 seq 被拒且不 emit`() {
        assertRejected(eventJson(seq = -1))
    }

    @Test
    fun `超过四十字符的 displayText 被拒且不 emit`() {
        assertRejected(eventJson(displayText = "x".repeat(41)))
    }

    @Test
    fun `finish 缺少 resultKind 被拒且不 emit`() {
        assertRejected(eventJson(resultKind = null))
    }

    @Test
    fun `非 finish 携带 resultKind 被拒且不 emit`() {
        assertRejected(eventJson(state = "act", resultKind = "success"))
    }

    @Test
    fun `hitl_wait requires confirm semantics`() {
        assertRejected(eventJson(state = "hitl_wait", requiresUser = "none", resultKind = null))
        assertAccepted(eventJson(state = "hitl_wait", requiresUser = "confirm", resultKind = null))
    }

    @Test
    fun `ordinary states reject user-control semantics`() {
        assertRejected(eventJson(state = "act", requiresUser = "confirm", resultKind = null))
        assertRejected(eventJson(state = "prompt_start", requiresUser = "handoff", resultKind = null))
        assertAccepted(eventJson(state = "act", requiresUser = "none", resultKind = null))
    }

    @Test
    fun `blocked accepts legacy none and explicit control or handoff`() {
        for (requiresUser in listOf("none", "control", "handoff")) {
            assertAccepted(eventJson(state = "blocked", requiresUser = requiresUser, resultKind = null))
        }
        assertRejected(eventJson(state = "blocked", requiresUser = "confirm", resultKind = null))
    }

    @Test
    fun `合法事件只 emit 一次`() {
        var emitted = 0
        var acceptedEvent: BrainEvent? = null

        val accepted = BrainEventIngress.accept(Json.parseToJsonElement(eventJson())) {
            emitted += 1
            acceptedEvent = it
        }

        assertTrue(accepted)
        assertEquals(1, emitted)
        assertEquals("task-1", acceptedEvent?.taskId)
    }

    private fun assertRejected(raw: String) {
        var emitted = 0

        val accepted = BrainEventIngress.accept(Json.parseToJsonElement(raw)) {
            emitted += 1
        }

        assertFalse(accepted)
        assertEquals(0, emitted)
    }

    private fun assertAccepted(raw: String) {
        var emitted = 0

        val accepted = BrainEventIngress.accept(Json.parseToJsonElement(raw)) {
            emitted += 1
        }

        assertTrue(accepted)
        assertEquals(1, emitted)
    }

    private fun eventJson(
        taskId: String = "task-1",
        seq: Long = 1,
        state: String = "finish",
        displayText: String = "完成",
        requiresUser: String = "none",
        resultKind: String? = "success",
    ): String {
        val result = resultKind?.let { ",\"resultKind\":\"$it\"" }.orEmpty()
        return """{"taskId":"$taskId","seq":$seq,"state":"$state","displayText":"$displayText","requiresUser":"$requiresUser","timestamp":2$result}"""
    }
}
