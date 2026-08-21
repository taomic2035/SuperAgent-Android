package com.superagent.body.core.memory

import com.superagent.common.CommandRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S6-② command journal（GPT 裁决版：CLAIMED≠ACCEPTED / lease 租约 / bindTask / settle 校验 / 惰性清扫）。
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
            commandId: String, from: CommandStatus, to: CommandStatus, now: Long,
            taskId: String?, brainSession: String?,
        ): Boolean = update(commandId, from, to, now, taskId, brainSession, 0)

        override fun compareAndUpdate(
            commandId: String, from: CommandStatus, to: CommandStatus, now: Long,
            taskId: String?, brainSession: String?, leaseUntil: Long,
        ): Boolean = update(commandId, from, to, now, taskId, brainSession, leaseUntil)

        private fun update(commandId: String, from: CommandStatus, to: CommandStatus, now: Long, taskId: String?, brainSession: String?, leaseUntil: Long): Boolean {
            val i = rows.indexOfFirst { it.commandId == commandId && it.status == from.wire }
            if (i < 0) return false
            rows[i] = rows[i].copy(
                status = to.wire, updatedAt = now,
                taskId = taskId ?: rows[i].taskId,
                brainSession = brainSession ?: rows[i].brainSession,
                leaseUntil = if (leaseUntil > 0) leaseUntil else rows[i].leaseUntil,
            )
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
    fun `reserve 入 journal 且文本保护存储（禁明文）`() {
        val r = store.reserve("text", "帮我点奶茶")
        val cid = (r as CommandStore.ReserveOutcome.Queued).commandId
        val rec = db.findByCommandId(cid)!!
        assertEquals("QUEUED", rec.status)
        assertNotEquals("帮我点奶茶", rec.protectedText, "存储禁明文")
        assertEquals("帮我点奶茶", CommandStore.unprotect(rec.protectedText), "可逆保护（P0 Base64 占位）")
    }

    @Test
    fun `reserve 本地拒绝不入 journal`() {
        assertTrue(store.reserve("bogus", "x") is CommandStore.ReserveOutcome.LocallyRejected)
        assertTrue(store.reserve("text", "  ") is CommandStore.ReserveOutcome.LocallyRejected)
        assertEquals(0, db.rows.size)
    }

    @Test
    fun `claimNext 置 CLAIMED 写租约——bindTask 才 ACCEPTED（CLAIMED≠ACCEPTED）`() {
        val cid = (store.reserve("text", "任务A") as CommandStore.ReserveOutcome.Queued).commandId
        val c = store.claimNext(cid, "boot-1")
        assertTrue(c is CommandStore.ClaimOutcome.Claimed)
        val rec = db.findByCommandId(cid)!!
        assertEquals("CLAIMED", rec.status, "claim 后是 CLAIMED 不是 ACCEPTED")
        assertEquals("boot-1", rec.brainSession)
        assertTrue(rec.leaseUntil > System.currentTimeMillis(), "租约在位")
        // bindTask：正确 session 绑 taskId → ACCEPTED；错误 session 拒
        assertFalse(store.bindTask(cid, "task-9", "boot-2"))
        assertTrue(store.bindTask(cid, "task-9", "boot-1"))
        assertEquals("ACCEPTED", db.findByCommandId(cid)!!.status)
    }

    @Test
    fun `租约期内的 CLAIMED 他方不可抢，租约过期可接管`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        store.claimNext(cid, "boot-1", leaseMs = 50)
        // 租约期内他方
        assertTrue(store.claimNext(cid, "boot-2") is CommandStore.ClaimOutcome.Rejected, "租约期内他方持有")
        // 同 session 幂等续租（短租约，保持过期可测）
        assertTrue(store.claimNext(cid, "boot-1", leaseMs = 50) is CommandStore.ClaimOutcome.Claimed)
        // 等租约过 → 他方可接管
        Thread.sleep(80)
        val takeover = store.claimNext(cid, "boot-2")
        assertTrue(takeover is CommandStore.ClaimOutcome.Claimed, "租约过期可被接管")
        assertEquals("boot-2", db.findByCommandId(cid)!!.brainSession)
    }

    @Test
    fun `settle 校验 session 与 taskId——串改拒绝`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        store.claimNext(cid, "boot-1")
        store.bindTask(cid, "task-1", "boot-1")
        assertFalse(store.settle(cid, "task-1", "boot-2", CommandStatus.RESOLVED), "错 session 拒绝")
        assertFalse(store.settle(cid, "task-2", "boot-1", CommandStatus.RESOLVED), "错 taskId 拒绝")
        assertTrue(store.settle(cid, "task-1", "boot-1", CommandStatus.RESOLVED))
        assertEquals("RESOLVED", db.findByCommandId(cid)!!.status)
    }

    @Test
    fun `INTERRUPTED 仅人工核对后 RESOLVED（禁自动重放）`() {
        val cid = (store.reserve("text", "任务") as CommandStore.ReserveOutcome.Queued).commandId
        store.claimNext(cid, "b1"); store.bindTask(cid, "t1", "b1")
        assertTrue(store.settle(cid, "t1", "b1", CommandStatus.INTERRUPTED))
        assertFalse(store.settle(cid, "t1", "b1", CommandStatus.ACCEPTED), "禁回执行态")
        assertTrue(store.settle(cid, "t1", "b1", CommandStatus.RESOLVED), "人工核对后可 RESOLVED")
    }

    @Test
    fun `sweepExpired 惰性清扫——QUEUED 过期转 REJECTED，CLAIMED 过期转 INTERRUPTED`() {
        val q = (store.reserve("text", "排队过期", ttlMs = -1) as CommandStore.ReserveOutcome.Queued).commandId
        val c = (store.reserve("text", "领取后过期") as CommandStore.ReserveOutcome.Queued).commandId
        store.claimNext(c, "boot-1")
        db.rows.first { it.commandId == c }.let { db.rows[db.rows.indexOf(it)] = it.copy(expiresAt = System.currentTimeMillis() - 1) }
        val swept = store.sweepExpired()
        assertEquals(2, swept)
        assertEquals("REJECTED", db.findByCommandId(q)!!.status)
        assertEquals("INTERRUPTED", db.findByCommandId(c)!!.status, "已领取过期=副作用边界未知禁重放")
    }

    @Test
    fun `claimNext 过期 QUEUED 惰性转 REJECTED`() {
        val cid = (store.reserve("text", "旧", ttlMs = -1) as CommandStore.ReserveOutcome.Queued).commandId
        assertTrue(store.claimNext(cid, "b") is CommandStore.ClaimOutcome.Rejected)
        assertEquals("REJECTED", db.findByCommandId(cid)!!.status)
    }
}
