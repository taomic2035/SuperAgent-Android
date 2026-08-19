package com.superagent.body.core.skills

import com.superagent.body.core.control.Controller
import com.superagent.body.core.control.OptionSelector
import com.superagent.body.core.events.EventBus
import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.CommitBoundaryGuard
import com.superagent.common.SkillLearnResult
import com.superagent.common.SkillListResult
import com.superagent.common.SkillMeta
import com.superagent.common.SkillSearchHit
import com.superagent.common.SkillSearchResult
import com.superagent.common.SkillIndex
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
    private val sensitive: com.superagent.body.core.security.SensitiveSessionTracker,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): SkillListResult {
        val visible = loadAll().filter { it.state != "deprecated" }
        val sorted = visible.sortedByDescending { statePriority(it.state) }
        return SkillListResult(sorted.map { SkillMeta(it.name, it.description, it.appPackage, it.tags) })
    }

    /**
     * 技能检索（AD-05 R3，从 brain skills/index.ts 下沉）。
     * 委托 body/common SkillIndex（中文 2-gram TF-IDF），检索是设备端数据业务（技能库在 filesDir），归 body。
     */
    fun search(query: String, threshold: Double = SkillIndex.MIN_SCORE): SkillSearchResult {
        val visible = loadAll().filter { it.state != "deprecated" }
        val metas = visible.map { SkillMeta(it.name, it.description, it.appPackage, it.tags) }
        val index = SkillIndex()
        index.rebuild(metas)
        val hits = index.retrieve(query, threshold).map { SkillSearchHit(it.skill, it.score) }
        return SkillSearchResult(hits)
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
        // #13：纯 take(8) 前缀会撞名（"点一杯奶茶"/"点一杯咖啡"同前缀互相覆盖）——
        // 前缀保可读性 + goal 哈希后缀防碰撞；同 goal 重复学习仍同 slug（复活语义不变）
        val slug = "skill-$appPackage-${goal.take(12)}-${Integer.toHexString(goal.hashCode()).take(8)}"
        // 同 slug 覆盖式保存 = ADR-5 复活语义：stale 后现场续走成功 → 以新轨迹重固化，
        // 状态归零为 candidate（旧统计作废——旧轨迹已被证明失配，不值得保留）
        val existing = loadAll().firstOrNull { it.name == slug }
        val skill = StoredSkill(name = slug, description = goal, appPackage = appPackage, tags = listOf("learned"), state = "candidate", steps = steps)
        dir.mkdirs()
        save(skill)
        events.emit("log", buildJsonObject {
            put("kind", if (existing != null) "skill.revive" else "skill.learn")
            put("slug", slug)
            put("steps", steps.size)
            if (existing != null) put("previousState", existing.state)
        })
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
            // 敏感会话内的确认类动作词（发送/删除/转账…）与 control.* RPC 路径同闸：
            // 回放不得绕过 extra-confirm（无人在环的自动执行）→ 停手转人工。
            if (label != null && sensitive.needsExtraConfirm(label)) {
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
