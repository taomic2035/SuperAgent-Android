package com.superagent.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillIndexTest {
    private val skills = listOf(
        SkillMeta("order-milk-tea", "在示例商城下单奶茶", "com.example.shop", listOf("购物", "奶茶")),
        SkillMeta("open-weather", "打开天气应用查看天气", "com.example.weather", listOf("天气")),
        SkillMeta("set-alarm", "设置闹钟提醒", "com.example.clock", listOf("闹钟")),
    )

    @Test
    fun `retrieve milk tea`() {
        val index = SkillIndex()
        index.rebuild(skills)
        val hits = index.retrieve("帮我点一杯奶茶")
        assertTrue(hits.isNotEmpty())
        assertEquals("order-milk-tea", hits.first().skill.name)
    }

    @Test
    fun `retrieve weather`() {
        val index = SkillIndex()
        index.rebuild(skills)
        val hits = index.retrieve("今天天气怎么样")
        assertEquals("open-weather", hits.first().skill.name)
    }

    @Test
    fun `unrelated query returns empty`() {
        val index = SkillIndex()
        index.rebuild(skills)
        assertTrue(index.retrieve("背诵一首古诗").isEmpty())
    }

    @Test
    fun `tokenize splits han bigrams`() {
        assertEquals(listOf("奶", "茶", "奶茶"), tokenize("奶茶"))
        assertEquals(listOf("open", "weather"), tokenize("open weather"))
    }

    // ---- 以下为 2026-08-19 文档一致性任务 T6 新增（只增不改）----

    @Test
    fun `empty or blank query returns empty`() {
        val index = SkillIndex()
        index.rebuild(skills)
        assertTrue(index.retrieve("").isEmpty())
        assertTrue(index.retrieve("   ").isEmpty())
    }

    @Test
    fun `pure english query hits latin tokens`() {
        val index = SkillIndex()
        index.rebuild(skills)
        // 查询纯英文（tokenize 后为 latin token），命中技能名 order-milk-tea 的拉丁 token
        val hits = index.retrieve("MILK TEA")
        assertTrue(hits.isNotEmpty())
        assertEquals("order-milk-tea", hits.first().skill.name)
    }

    @Test
    fun `below threshold returns empty`() {
        val index = SkillIndex()
        index.rebuild(skills)
        // 自然低分：仅与文档共享一个单字 token，得分 1/sqrt(23)≈0.21 < 0.30
        assertTrue(index.retrieve("单").isEmpty())
        // 显式抬高阈值同样不返回
        assertTrue(index.retrieve("帮我点一杯奶茶", threshold = 99.0).isEmpty())
    }
}