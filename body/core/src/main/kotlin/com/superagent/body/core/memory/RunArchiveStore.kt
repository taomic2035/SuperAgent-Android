package com.superagent.body.core.memory

import com.superagent.common.RunArchiveResult
import com.superagent.common.RunListResult
import com.superagent.common.RunRecord
import com.superagent.common.TraceStep

/** ME-3b runs 表行级接口（与 MemoryDb 同模式：SQLite 胶水在生产实现，JVM 测试用内存 fake） */
interface RunArchiveDb {
    fun insert(record: RunRecord): Long

    /** 最近归档（新在前） */
    fun list(limit: Int): List<RunRecord>
}

/**
 * ME-3b 情景层全量归档（docs/15 §3）：run 快照入 SQLite 全量留存，不环形淘汰。
 * brain 侧本地 runstate.json 环形 30 条照旧（断点续跑路径不变），本库是审计/复盘/记忆提取的完整事实源。
 */
class RunArchiveStore(private val db: RunArchiveDb) {

    fun archive(
        goal: String,
        outcome: String,
        failureReason: String?,
        trace: List<TraceStep>,
        startedAt: Long,
        finishedAt: Long,
    ): RunArchiveResult {
        val g = goal.trim()
        require(g.isNotEmpty()) { "goal 不能为空" }
        require(outcome in OUTCOMES) { "outcome 非法：$outcome（允许 success|failed|crashed|needs_human|closed）" }
        val now = System.currentTimeMillis()
        val record = RunRecord(
            id = 0,
            goal = g.take(MAX_GOAL),
            outcome = outcome,
            failureReason = failureReason?.trim()?.take(MAX_REASON)?.ifEmpty { null },
            trace = trace.take(MAX_TRACE),
            startedAt = startedAt,
            finishedAt = finishedAt,
            archivedAt = now,
        )
        return RunArchiveResult(db.insert(record))
    }

    fun list(limit: Int = 30): RunListResult = RunListResult(db.list(limit.coerceIn(1, 100)))

    companion object {
        private val OUTCOMES = setOf("success", "failed", "crashed", "needs_human", "closed")
        const val MAX_GOAL = 128
        const val MAX_REASON = 160
        const val MAX_TRACE = 60
    }
}
