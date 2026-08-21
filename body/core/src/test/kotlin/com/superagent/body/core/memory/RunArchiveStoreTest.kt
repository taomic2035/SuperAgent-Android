package com.superagent.body.core.memory

import com.superagent.common.RunRecord
import com.superagent.common.TraceStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ME-3b runs 全量归档 JVM 单测（docs/15 §3 验收）：
 * outcome 枚举校验、字段截断、全量不淘汰（Iron Law）、list 新在前 + limit clamp。
 */
class RunArchiveStoreTest {

    private class FakeRunDb : RunArchiveDb {
        val rows = mutableListOf<RunRecord>()
        private var nextId = 1L

        override fun insert(record: RunRecord): Long {
            val id = nextId++
            rows.add(record.copy(id = id))
            return id
        }

        override fun list(limit: Int): List<RunRecord> = rows.sortedByDescending { it.id }.take(limit)
    }

    private lateinit var db: FakeRunDb
    private lateinit var store: RunArchiveStore

    @BeforeEach
    fun setup() {
        db = FakeRunDb()
        store = RunArchiveStore(db)
    }

    private fun step(tool: String) = TraceStep(tool = tool, args = null, located = true, signature = "s", timestamp = 1L)

    @Test
    fun `归档插入并返回自增 id`() {
        val r = store.archive("点一杯奶茶", "success", null, listOf(step("control.tap")), 100L, 200L)
        assertEquals(1L, r.id)
        assertEquals("点一杯奶茶", db.rows.first().goal)
        assertEquals("success", db.rows.first().outcome)
        assertNull(db.rows.first().failureReason)
        assertEquals(1, db.rows.first().trace.size)
    }

    @Test
    fun `用户停止终态可归档且保持不可续语义`() {
        store.archive("停止任务", "stopped", "用户停止", emptyList(), 100L, 200L)

        assertEquals("stopped", db.rows.single().outcome)
        assertEquals("用户停止", db.rows.single().failureReason)
    }

    @Test
    fun `全量留存不淘汰`() {
        repeat(120) { store.archive("任务$it", "closed", null, emptyList(), it.toLong(), it.toLong() + 1) }
        assertEquals(120, db.rows.size, "Iron Law：环形 30 条会丢，全量归档一条不丢")
    }

    @Test
    fun `list 新在前且 limit clamp`() {
        repeat(150) { store.archive("g$it", "success", null, emptyList(), 0, 1) }
        assertEquals("g149", store.list(1).runs.first().goal, "新归档在前")
        assertEquals(30, store.list().runs.size, "默认 30")
        assertEquals(100, store.list(999).runs.size, "上限 100")
        assertEquals(1, store.list(0).runs.size, "下限 1")
    }

    @Test
    fun `非法 outcome 与空 goal 拒绝`() {
        assertThrows<IllegalArgumentException> { store.archive("g", "unknown", null, emptyList(), 0, 1) }
        assertThrows<IllegalArgumentException> { store.archive("  ", "success", null, emptyList(), 0, 1) }
        assertEquals(0, db.rows.size)
    }

    @Test
    fun `超长字段截断 trace 截尾`() {
        val longReason = "r".repeat(500)
        val manySteps = (1..80).map { step("tool$it") }
        store.archive("g".repeat(300), "failed", longReason, manySteps, 0, 1)
        val rec = db.rows.first()
        assertEquals(RunArchiveStore.MAX_GOAL, rec.goal.length)
        assertEquals(RunArchiveStore.MAX_REASON, rec.failureReason!!.length)
        assertEquals(RunArchiveStore.MAX_TRACE, rec.trace.size)
        assertTrue(rec.trace.last().tool == "tool${RunArchiveStore.MAX_TRACE}", "保留前缀截尾")
    }
}
