package com.superagent.body.core

import com.superagent.common.BrainEvent
import com.superagent.common.BrainEventRequiresUser
import com.superagent.common.BrainEventState
import com.superagent.common.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Fail-closed boundary for untrusted brain.event RPC payloads. */
internal object BrainEventIngress {
    fun accept(params: JsonElement?, emit: (BrainEvent) -> Unit): Boolean {
        val event = params?.let {
            runCatching { json.decodeFromJsonElement<BrainEvent>(it) }.getOrNull()
        } ?: return false

        if (event.taskId.isBlank() || event.seq < 0 || event.displayText.length > 40) return false
        val isFinish = event.state == BrainEventState.FINISH
        if (isFinish != (event.resultKind != null)) return false
        val validRequiresUser = when (event.state) {
            BrainEventState.HITL_WAIT -> event.requiresUser == BrainEventRequiresUser.CONFIRM
            BrainEventState.BLOCKED -> event.requiresUser == BrainEventRequiresUser.NONE ||
                event.requiresUser == BrainEventRequiresUser.CONTROL ||
                event.requiresUser == BrainEventRequiresUser.HANDOFF
            else -> event.requiresUser == BrainEventRequiresUser.NONE
        }
        if (!validRequiresUser) return false

        emit(event)
        return true
    }
}
