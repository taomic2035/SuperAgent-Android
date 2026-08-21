package com.superagent.body.core.memory

import com.superagent.common.MemoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ME-1 记忆逻辑层 JVM 单测（docs/15 §4 验收）：
 * 去重合并（重述强化/冲突顶替+软删留痕）、检索排序（覆盖率+置信+命中）、
 * kind 枚举与空值校验、revise/forget 语义。SQLite 胶水由 AndroidSqliteMemoryDb 承担，真机验证。
 */
class MemoryStoreTest {

    /** 内存 fake：行级接口的透明实现（生产实现是 AndroidSqliteMemoryDb） */
    private class FakeDb : MemoryDb {
        val rows = mutableListOf<MemoryEntry>()
        private var nextId = 1L

        override fun insert(entry: MemoryEntry): Long {
            val id = nextId++
            rows.add(entry.copy(id = id))
            return id
        }

        override fun update(entry: MemoryEntry) {
            val i = rows.indexOfFirst { it.id == entry.id }
            if (i >= 0) rows[i] = entry
        }

        override fun delete(id: Long): Boolean = rows.removeIf { it.id == id }

        override fun findById(id: Long): MemoryEntry? = rows.firstOrNull { it.id == id }

        override fun active(): List<MemoryEntry> = rows.filter { !it.revoked }

        override fun all(): List<MemoryEntry> = rows.toList()
    }

    private lateinit var db: FakeDb
    private lateinit var store: MemoryStore

    @BeforeEach
    fun setup() {
        db = FakeDb()
        store = MemoryStore(db)
    }

    @Test
    fun `全新条目写入返回未合并`() {
        val r = store.write("preference", "奶茶口味", "少糖", "user-told", 1.0)
        assertFalse(r.merged)
        assertEquals(1L, r.id)
        assertEquals(1, db.active().size)
        assertEquals("少糖", db.active().first().content)
        assertEquals(1.0, db.active().first().confidence)
    }

    @Test
    fun `G2-01 含身份证或卡号拒绝入库`() {
        assertThrows<IllegalArgumentException> {
            store.write("fact", "证件", "身份证号 110101199003077758", "user-told")
        }
        assertThrows<IllegalArgumentException> {
            store.write("fact", "卡", "银行卡 6222020200112233345", "user-told")
        }
        assertEquals(0, db.active().size)
    }

    @Test
    fun `C-09 revise 路径同样拒绝 PII`() {
        val r = store.write("preference", "奶茶口味", "少糖", "user-told")
        assertThrows<IllegalArgumentException> {
            store.revise(r.id, "改成 6222020200112233345")
        }
        assertEquals("少糖", db.active().first().content, "修订被拒后原内容不变")
    }

    @Test
    fun `G2-01 正常短数字内容不误伤`() {
        val r = store.write("preference", "音量", "播报音量 30", "user-told")
        assertFalse(r.merged)
        assertTrue(MemoryStore.containsPii("卡号 6222020200112233345"))
        assertFalse(MemoryStore.containsPii("音量 30，延迟 1.5s"))
    }

    @Test
    fun `ME-6 maintain 衰减旧条目且不动 updatedAt`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        val stale = db.active().first()
        db.update(stale.copy(updatedAt = System.currentTimeMillis() - 91L * 24 * 3600 * 1000, confidence = 0.8))
        val r = store.maintain()
        val after = db.active().first()
        assertEquals(1, r.decayed)
        assertEquals(0, r.archived)
        assertTrue(after.confidence in 0.71..0.73, "0.8×0.9=0.72，实际 ${after.confidence}")
        assertTrue(after.updatedAt < System.currentTimeMillis() - 90L * 24 * 3600 * 1000, "衰减不得重置 updatedAt")
    }

    @Test
    fun `ME-8 exportAll 含 revoked 条目`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        store.write("preference", "奶茶口味", "无糖", "user-told") // 顶替 → 旧条 revoked
        val all = store.exportAll()
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.revoked })
        assertEquals("无糖", all.first { !it.revoked }.content)
    }

    @Test
    fun `ME-8 importEntries 补缺不覆盖不回写 revoked`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        val r = store.importEntries(
            listOf(
                MemoryEntry(99, "preference", "奶茶口味", "全糖", 0.5, "restore", 0, false, 0, 0),   // 同 key：跳过（body 为准）
                MemoryEntry(98, "preference", "快递", "放前台驿站", 0.5, "restore", 0, false, 0, 0), // 新 key：插入
                MemoryEntry(97, "fact", "旧史", "被顶替旧版", 0.5, "restore", 0, true, 0, 0),        // revoked：跳过
                MemoryEntry(96, "fact", "卡号", "6222020200112233345", 0.5, "restore", 0, false, 0, 0), // PII：跳过
                MemoryEntry(95, "bogus", "非法kind", "x", 0.5, "restore", 0, false, 0, 0),           // kind 非法：跳过
            ),
        )
        assertEquals(1, r.inserted)
        assertEquals(4, r.skipped)
        val active = db.active()
        assertEquals(2, active.size)
        assertEquals("少糖", active.first { it.topic == "奶茶口味" }.content) // 未被"全糖"覆盖
        assertEquals("放前台驿站", active.first { it.topic == "快递" }.content)
    }

    @Test
    fun `同内容重述强化 confidence 上限 1 点 0`() {
        store.write("preference", "奶茶口味", "少糖", "run:点奶茶", 0.5)
        val r = store.write("preference", "奶茶口味", "少糖", "run:点奶茶")
        assertTrue(r.merged)
        assertEquals(1, db.active().size, "同内容重述不得新增条目")
        val m = db.active().first()
        assertEquals(0.6, m.confidence, 1e-9)
        assertEquals(1, m.hits)
        repeat(10) { store.write("preference", "奶茶口味", "少糖", "run:点奶茶") }
        assertEquals(1.0, db.active().first().confidence, 1e-9, "confidence 收敛上限 1.0")
    }

    @Test
    fun `冲突内容顶替旧版软删留痕`() {
        val first = store.write("preference", "奶茶口味", "少糖", "user-told")
        val second = store.write("preference", "奶茶口味", "无糖去冰", "user-told")
        assertTrue(second.merged)
        assertEquals(1, db.active().size, "同 topic 只保留一条 active")
        assertEquals("无糖去冰", db.active().first().content)
        assertEquals(first.id, db.rows.first { it.revoked }.id, "旧版 revoked 留痕（Iron Law 不物理删）")
        assertEquals(2, db.rows.size, "软删条目仍在库中")
    }

    @Test
    fun `不同 topic 或不同 kind 不互相合并`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        store.write("fact", "奶茶口味", "少糖", "user-told")
        store.write("preference", "快递", "放前台", "user-told")
        assertEquals(3, db.active().size)
        assertEquals(3, db.rows.size)
    }

    @Test
    fun `检索按覆盖率排序且命中计数递增`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        store.write("preference", "快递", "放前台快递柜", "user-told")
        val r = store.search("点一杯少糖奶茶")
        assertEquals(1, r.hits.size, "只有奶茶条目命中")
        assertTrue(r.hits.first().memory.topic == "奶茶口味")
        assertEquals(1, r.hits.first().memory.hits, "返回条目 hits+1")
        assertTrue(store.search("完全不相关查询词组xyz").hits.isEmpty())
        assertTrue(store.search("   ").hits.isEmpty(), "空查询返回空")
    }

    @Test
    fun `被顶替的旧版检索不可见`() {
        store.write("preference", "奶茶口味", "少糖", "user-told")
        store.write("preference", "奶茶口味", "无糖", "user-told")
        val r = store.search("少糖")
        assertTrue(r.hits.none { it.memory.content == "少糖" }, "revoked 旧版不出现在结果")
        assertTrue(r.hits.all { it.memory.content == "无糖" }, "同 topic 只剩 active 新版（单字交集仍可召回新版）")
    }

    @Test
    fun `limit 生效且上限 20`() {
        repeat(25) { store.write("fact", "topic$it", "内容$it", "run:t") }
        assertEquals(5, store.search("内容", 5).hits.size)
        assertEquals(20, store.search("内容", 999).hits.size, "limit clamp 到 20")
    }

    @Test
    fun `非法 kind 与空字段拒绝`() {
        assertThrows<IllegalArgumentException> { store.write("diary", "t", "c", "user-told") }
        assertThrows<IllegalArgumentException> { store.write("fact", " ", "c", "user-told") }
        assertThrows<IllegalArgumentException> { store.write("fact", "t", "", "user-told") }
        assertThrows<IllegalArgumentException> { store.write("fact", "t", "c", " ") }
        assertEquals(0, db.rows.size, "非法输入不入库")
    }

    @Test
    fun `超长 topic 与 content 截断`() {
        store.write("fact", "t".repeat(100), "c".repeat(500), "run:" + "x".repeat(200))
        val m = db.active().first()
        assertEquals(MemoryStore.MAX_TOPIC, m.topic.length)
        assertEquals(MemoryStore.MAX_CONTENT, m.content.length)
        assertEquals(MemoryStore.MAX_SOURCE, m.source.length)
    }

    @Test
    fun `revise 改写内容并标记来源`() {
        val r = store.write("preference", "奶茶口味", "少糖", "user-told")
        assertTrue(store.revise(r.id, "半糖", "user-corrected"))
        val m = db.findById(r.id)
        assertNotNull(m)
        assertEquals("半糖", m!!.content)
        assertEquals("user-corrected", m.source)
        assertFalse(store.revise(9999L, "不存在"))
        assertThrows<IllegalArgumentException> { store.revise(r.id, "  ") }
    }

    @Test
    fun `forget 物理删`() {
        val r = store.write("fact", "t", "c", "user-told")
        assertTrue(store.forget(r.id))
        assertEquals(0, db.rows.size, "用户删除权 > Iron Law：物理删")
        assertFalse(store.forget(r.id))
    }
}
