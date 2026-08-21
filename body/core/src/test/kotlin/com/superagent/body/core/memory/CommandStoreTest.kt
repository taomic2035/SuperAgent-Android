package com.superagent.body.core.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S6-② command journal 逻辑层单测（冻结设计 docs/superpowers/specs/2026-08-21-command-ack-design.md）：
 * reserve 本地拒绝不入库 / UUID 唯一 / claim 原子与过期 / 状态机合法迁移 / INTERRUPTED 语义。
 */
class CommandStoreTest {

    private class FakeDb : CommandDb {
        val rows = mutableListOf<CommandRecord>()
        private var nextId = 1L

        override fun insert(record: CommandRecord): Long {
            require(rows.none { it.commandId == record.commandId }) { "commandId 唯一约束" }
            val id = nextId++
            rows.add(record.copy(id = id))
            return id
        }

        override fun findByCommandId(commandId: String): CommandRecord? = rows.firstOrNull { it.commandId == commandId }

        override fun compareAndUpdateStatus(
            commandId: String,
            from: CommandStatus,
            to: CommandStatus,
            now: Long,
            taskId: String?,
            brainSession: String?,
        ): Boolean {
            val i = rows.indexOfFirst { it.commandId == commandId && it.status == from }
            if (i < 0) return false
            rows[i] = rows[i].copy(status = to, updatedAt = now, taskId = taskId ?: rows[i].taskId, brainSession = brainSession ?: rows[i].brainSession)
            return true
        }

        override fun list(sinceId: Long, limit: Int): List<CommandRecord> = rows.filter { it.id > sinceId }.sortedByDescending { it.id }.take(limit)
    }

    private lateinit var db: FakeDb
    private lateinit var store: CommandStore

    @BeforeEach
    fun setup() {
        db = FakeDb()
        store = CommandStore(db)
    }

    @Test
    fun `reserve 合法命令入 journal 为 QUEUED 且 UUID 唯一`() {
        val r = store.reserve("text", "帮我点奶茶")
        assertTrue(r is CommandStore.ReserveOutcome.Queued)
        val cid = (r as CommandStore.ReserveOutcome.Queued).commandId
        val rec = db.findByCommandId(cid)!!
        assertEquals(CommandStatus.QUEUED, rec.status)
        assertTrue(cid.length == 36 && cid.contains('-'), "UUID v4 形态")
        // 两次 reserve 生成不同 commandId（禁推导）
        val r2 = store.reserve("text", "帮我点奶茶")
        assertNotEquals(cid, (r2 as CommandStore.ReserveOutcome.Queued).commandId)
    }

    @Test
    fun `reserve 本地拒绝不入 journal（LOCALLY_REJECTED 无记录）`() {
        val bad = store.reserve("bogus", "x")
        assertTrue(bad is CommandStore.ReserveOutcome.LocallyRejected)
        val empty = store.reserve("text", "   ")
        assertTrue(empty is CommandStore.ReserveOutcome.LocallyRejected)
        val long = store.reserve("text", "x".repeat(501))
        assertTrue(long is CommandStore.ReserveOutcome.LocallyRejected)
        assertEquals(0, db.rows.size, "本地拒绝不得生成 journal 记录")
    }

    @Test
    fun `claim 原子领取 QUEUED 到 ACCEPTED 且记录 brainSession`() {
        val cid = (store.reserve("text", "任务A") as CommandStore.ReserveOutcome.Queued).commandId
        val c = store.claim(cid, "boot-111")
        assertTrue(c is CommandStore.ClaimOutcome.Accepted)
        assertEquals("boot-111", db.findByCommandId(cid)!!.brainSession)
        // 重复领取（另一 session）必须拒绝
        val c2 = store.claim(cid, "boot-222")
        assertTrue(c2 is CommandStore.ClaimOutcome.Rejected, "同 commandId 不得映射第二个领取者")
    }

    @Test
    fun `claim 过期惰性判为 REJECTED`() {
        val cid = (store.reserve("text", "旧命令", ttlMs = -1) as CommandStore.ReserveOutcome.Queued).commandId
        val c = store.claim(cid, "boot-1")
        assertTrue(c is CommandStore.ClaimOutcome.Rejected && c.reason.contains("过期"))
        assertEquals(CommandStatus.REJECTED, db.findByCommandId(cid)!!.status)
    }

    @Test
    fun `状态机 合法迁移与非法迁移`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        store.claim(cid, "b1")
        // ACCEPTED → WAITING_USER → ACCEPTED（继续）→ RESOLVED
        assertTrue(store.mark(cid, CommandStatus.WAITING_USER))
        assertTrue(store.mark(cid, CommandStatus.ACCEPTED))
        assertTrue(store.mark(cid, CommandStatus.RESOLVED, taskId = "task-9"))
        assertEquals("task-9", db.findByCommandId(cid)!!.taskId)
        // RESOLVED 后一切迁移拒绝（终态）
        assertFalse(store.mark(cid, CommandStatus.INTERRUPTED))
    }

    @Test
    fun `INTERRUPTED 后不可自动重放（无任何出边到 ACCEPTED）`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        store.claim(cid, "b1")
        assertTrue(store.mark(cid, CommandStatus.INTERRUPTED))
        assertFalse(store.mark(cid, CommandStatus.ACCEPTED), "崩溃边界未知的命令禁止回到执行态")
        assertFalse(store.mark(cid, CommandStatus.WAITING_USER))
        assertTrue(store.mark(cid, CommandStatus.RESOLVED), "仅允许人工核对后 RESOLVED")
    }

    @Test
    fun `QUEUED 不可直接终态（必须先 claim）`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        assertFalse(store.mark(cid, CommandStatus.RESOLVED), "未领取的命令不能被 brain 标终态")
    }

    @Test
    fun `list 水位倒序`() {
        store.reserve("text", "a"); store.reserve("text", "b"); store.reserve("text", "c")
        val all = store.list()
        assertEquals(3, all.size)
        assertEquals("c", all[0].text, "新在前")
        assertEquals(listOf("c"), store.list(sinceId = all[1].id).map { it.text })
    }
}
