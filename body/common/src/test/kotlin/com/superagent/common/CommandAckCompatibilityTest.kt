package com.superagent.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommandAckCompatibilityTest {

    @Test
    fun `旧事件缺少关联字段时解码为 null`() {
        val body = json.decodeFromString<BodyEvent>("""{"seq":1,"type":"voice"}""")
        val brain = json.decodeFromString<BrainEvent>(
            """{"taskId":"task-1","seq":2,"state":"prompt_start","timestamp":3}""",
        )

        assertNull(body.payload)
        assertNull(body.sourceSessionId)
        assertNull(body.commandId)
        assertEquals("", brain.displayText)
        assertEquals(BrainEventRequiresUser.NONE, brain.requiresUser)
        assertNull(brain.sourceSessionId)
        assertNull(brain.commandId)
        assertNull(brain.resultKind)
    }

    @Test
    fun `未来额外字段保持向前解码兼容`() {
        val body = json.decodeFromString<BodyEvent>(
            """{"seq":1,"type":"voice","payload":null,"future":"ignored"}""",
        )
        val brain = json.decodeFromString<BrainEvent>(
            """{"taskId":"task-1","seq":2,"state":"act","displayText":"执行中","requiresUser":"control","timestamp":3,"future":"ignored"}""",
        )

        assertEquals(1, body.seq)
        assertEquals(BrainEventState.ACT, brain.state)
    }

    @Test
    fun `新关联字段可以往返序列化`() {
        val body = BodyEvent(1, "voice", sourceSessionId = "body-session", commandId = "command-1")
        val brain = BrainEvent(
            taskId = "task-1",
            seq = 2,
            state = BrainEventState.FINISH,
            displayText = "已完成",
            requiresUser = BrainEventRequiresUser.NONE,
            resultKind = BrainEventResultKind.SUCCESS,
            timestamp = 3,
            sourceSessionId = "brain-session",
            commandId = "command-1",
        )

        assertEquals(body, json.decodeFromString<BodyEvent>(json.encodeToString(body)))
        assertEquals(brain, json.decodeFromString<BrainEvent>(json.encodeToString(brain)))
    }

    @Test
    fun `全部合法 BrainEvent 枚举值均可解码`() {
        for (state in BrainEventState.entries) {
            val decoded = json.decodeFromString<BrainEvent>(brainJson(state = state.wireValue))
            assertEquals(state, decoded.state)
        }
        for (requiresUser in BrainEventRequiresUser.entries) {
            val decoded = json.decodeFromString<BrainEvent>(brainJson(requiresUser = requiresUser.wireValue))
            assertEquals(requiresUser, decoded.requiresUser)
        }
        for (resultKind in BrainEventResultKind.entries) {
            val decoded = json.decodeFromString<BrainEvent>(brainJson(resultKind = resultKind.wireValue))
            assertEquals(resultKind, decoded.resultKind)
        }
    }

    @Test
    fun `未知 BrainEvent 枚举值拒绝解码`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<BrainEvent>(brainJson(state = "future_state"))
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<BrainEvent>(brainJson(requiresUser = "future_user"))
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<BrainEvent>(brainJson(resultKind = "future_result"))
        }
    }

    private fun brainJson(
        state: String = "act",
        requiresUser: String = "none",
        resultKind: String? = null,
    ): String {
        val result = resultKind?.let { ",\"resultKind\":\"$it\"" }.orEmpty()
        return """{"taskId":"task-1","seq":1,"state":"$state","displayText":"x","requiresUser":"$requiresUser","timestamp":2$result}"""
    }
}
