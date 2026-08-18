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
}