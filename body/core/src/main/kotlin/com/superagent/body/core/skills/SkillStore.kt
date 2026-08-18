package com.superagent.body.core.skills

import com.superagent.body.core.control.Controller
import com.superagent.body.core.control.OptionSelector
import com.superagent.body.core.events.EventBus
import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.SkillLearnResult
import com.superagent.common.SkillListResult
import com.superagent.common.SkillMeta
import com.superagent.common.SkillRunResult
import com.superagent.common.TraceStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class StoredSkill(
    val name: String,
    val description: String,
    val appPackage: String,
    val tags: List<String>,
    val steps: List<SkillStep>,
)

@Serializable
data class SkillStep(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
)

/**
 * 技能固化与回放：
 * - learn：brain 成功完成任务后把轨迹固化成步骤列表
 * - run：逐步骤回放，每步先验证目标仍可见；支付词永不回放（转人工）
 * - 任何一步失配即 stale，禁止盲走
 */
class SkillStore(
    private val dir: File,
    private val perceiver: ScreenPerceiver,
    private val selector: OptionSelector,
    private val controller: Controller,
    private val events: EventBus,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): SkillListResult {
        return SkillListResult(loadAll().map { SkillMeta(it.name, it.description, it.appPackage, it.tags) })
    }

    fun learn(goal: String, appPackage: String, trace: List<TraceStep>): SkillLearnResult {
        val steps = trace.filter { it.located }
            .filter { it.tool in REPLAYABLE_TOOLS }
            .map { t ->
                SkillStep(t.tool, (t.args ?: emptyMap()).mapValues { (_, v) -> v.toString().trim('"') })
            }
        if (steps.isEmpty()) throw IllegalArgumentException("轨迹无可回放步骤")
        val slug = "skill-$appPackage-${goal.take(8)}"
        val skill = StoredSkill(
            name = slug,
            description = goal,
            appPackage = appPackage,
            tags = listOf("learned"),
            steps = steps,
        )
        dir.mkdirs()
        File(dir, "$slug.json").writeText(json.encodeToString(skill))
        events.emit("log", buildJsonObject {
            put("kind", "skill.learn")
            put("slug", slug)
            put("steps", steps.size)
        })
        return SkillLearnResult(slug)
    }

    suspend fun run(name: String): SkillRunResult {
        val skill = loadAll().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("技能不存在: $name")
        var completed = 0
        for (step in skill.steps) {
            val label = step.args["label"]
            if (label != null && CommitBoundaryGuard.isCommitBoundary(label)) {
                return SkillRunResult("sensitive_handoff", completed)
            }
            val ok = executeStep(step)
            if (!ok) return SkillRunResult("stale", completed)
            completed++
        }
        return SkillRunResult("success", completed)
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
            "control.typeText" -> {
                val text = step.args["text"] ?: return@withContext false
                controller.typeText(text).located
            }
            "control.selectOption", "control.selectSpec" -> {
                val label = step.args["label"] ?: return@withContext false
                val screen = perceiver.perceive("a11y")
                if (screen.blank || screen.marks.orEmpty().none {
                        it.text.contains(label) || label.contains(it.text)
                    }) {
                    return@withContext false
                }
                selector.select(label).located
            }
            else -> false
        }
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