package com.superagent.body.core.skills

import com.superagent.body.core.control.Controller
import com.superagent.body.core.control.OptionSelector
import com.superagent.body.core.events.EventBus
import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.SkillLearnResult
import com.superagent.common.SkillListResult
import com.superagent.common.SkillMeta
import com.superagent.common.TraceStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class SkillStats(
    var runCount: Int = 0,
    var successCount: Int = 0,
    var staleCount: Int = 0,
)

@Serializable
data class StoredSkill(
    val name: String,
    val description: String,
    val appPackage: String,
    val tags: List<String>,
    var state: String = "candidate",
    var stats: SkillStats = SkillStats(),
    val steps: List<SkillStep>,
)

@Serializable
data class SkillStep(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val expectedSignature: String? = null,
)

sealed class SkillRunOutcome {
    data class Success(val completedSteps: Int) : SkillRunOutcome()
    data class Stale(val completedSteps: Int, val failedStepIndex: Int, val failedStep: SkillStep) : SkillRunOutcome()
    data class SensitiveHandoff(val completedSteps: Int) : SkillRunOutcome()
}

class SkillStore(
    private val dir: File,
    private val perceiver: ScreenPerceiver,
    private val selector: OptionSelector,
    private val controller: Controller,
    private val events: EventBus,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): SkillListResult {
        val visible = loadAll().filter { it.state != "deprecated" }
        val sorted = visible.sortedByDescending { statePriority(it.state) }
        return SkillListResult(sorted.map { SkillMeta(it.name, it.description, it.appPackage, it.tags) })
    }

    fun learn(goal: String, appPackage: String, trace: List<TraceStep>): SkillLearnResult {
        val steps = trace.filter { it.located }
            .filter { it.tool in REPLAYABLE_TOOLS }
            .map { t ->
                SkillStep(
                    tool = t.tool,
                    args = (t.args ?: emptyMap()).mapValues { (_, v) -> v.toString().trim('"') },
                    expectedSignature = t.signature,
                )
            }
        if (steps.isEmpty()) throw IllegalArgumentException("轨迹无可回放步骤")
        val slug = "skill-$appPackage-${goal.take(8)}"
        val skill = StoredSkill(name = slug, description = goal, appPackage = appPackage, tags = listOf("learned"), state = "candidate", steps = steps)
        dir.mkdirs()
        save(skill)
        events.emit("log", buildJsonObject { put("kind", "skill.learn"); put("slug", slug); put("steps", steps.size) })
        return SkillLearnResult(slug)
    }

    suspend fun run(name: String): SkillRunOutcome {
        val skill = loadAll().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("技能不存在: $name")
        var completed = 0
        for ((index, step) in skill.steps.withIndex()) {
            val label = step.args["label"]
            if (label != null && CommitBoundaryGuard.isCommitBoundary(label)) {
                recordRun(skill, stale = false)
                return SkillRunOutcome.SensitiveHandoff(completed)
            }
            val ok = executeStep(step)
            if (!ok) {
                recordRun(skill, stale = true)
                return SkillRunOutcome.Stale(completed, index, step)
            }
            completed++
        }
        recordRun(skill, stale = false)
        return SkillRunOutcome.Success(completed)
    }

    fun feedback(name: String, success: Boolean) {
        val skill = loadAll().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("技能不存在: $name")
        skill.stats.runCount++
        if (success) skill.stats.successCount++ else skill.stats.staleCount++
        transitionState(skill)
        save(skill)
        events.emit("log", buildJsonObject { put("kind", "skill.feedback"); put("name", name); put("success", success); put("state", skill.state) })
    }

    private fun recordRun(skill: StoredSkill, stale: Boolean) {
        skill.stats.runCount++
        if (stale) skill.stats.staleCount++ else skill.stats.successCount++
        transitionState(skill)
        save(skill)
    }

    private fun transitionState(skill: StoredSkill) {
        val s = skill.stats
        val staleRate = if (s.runCount > 0) s.staleCount.toDouble() / s.runCount else 0.0
        skill.state = when {
            s.runCount >= 20 && staleRate > 0.30 -> "deprecated"
            s.successCount >= 5 -> "active"
            s.successCount >= 2 -> "verified"
            else -> skill.state
        }
    }

    private fun statePriority(state: String): Int = when (state) {
        "active" -> 3; "verified" -> 2; "candidate" -> 1; else -> 0
    }

    private suspend fun executeStep(step: SkillStep): Boolean = withContext(Dispatchers.Main) {
        when (step.tool) {
            "control.launch" -> controller.launch(step.args["pkg"] ?: "").located
            "control.back" -> controller.back().located
            "control.home" -> controller.home().located
            "control.tap" -> {
                val x = step.args["x"]?.toIntOrNull() ?: return@withContext false
                val y = step.args["y"]?.toIntOrNull() ?: return@withContext false
                controller.tap(x, y).located
            }
            "control.typeText" -> controller.typeText(step.args["text"] ?: "").located
            "control.selectOption", "control.selectSpec" -> {
                val label = step.args["label"] ?: return@withContext false
                val screen = perceiver.perceive("a11y")
                if (screen.blank || screen.marks.orEmpty().none { it.text.contains(label) || label.contains(it.text) }) return@withContext false
                selector.select(label).located
            }
            else -> false
        }
    }

    private fun save(skill: StoredSkill) {
        File(dir, "${skill.name}.json").writeText(json.encodeToString(skill))
    }

    private fun loadAll(): List<StoredSkill> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<StoredSkill>(f.readText()) }.getOrNull() }
    }

    companion object {
        val REPLAYABLE_TOOLS = setOf(
            "control.launch", "control.back", "control.home",
            "control.tap", "control.typeText", "control.selectOption", "control.selectSpec",
        )
    }
}
