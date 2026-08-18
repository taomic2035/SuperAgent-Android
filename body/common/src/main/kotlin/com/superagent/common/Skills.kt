package com.superagent.common

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 中文技能索引（与 brain 侧算法一致，保证两端召回结果可对齐）。
 * tokenize：汉字单字 + 相邻汉字 2-gram + 拉丁/数字连续串，去重。
 * 相似度：点积 / 文档 TF-IDF 模长（不对称余弦），阈值 0.30。
 */
class SkillIndex {
    data class Hit(val skill: SkillMeta, val score: Double)

    private class IndexedSkill(val skill: SkillMeta, val docFreq: Map<String, Double>, val norm: Double)

    private val skills = mutableListOf<IndexedSkill>()

    fun rebuild(list: List<SkillMeta>) {
        skills.clear()
        val docs = list.map { s ->
            Pair(s, tokenize(listOf(s.name, s.description, s.tags.joinToString(" ")).joinToString(" ")))
        }
        val df = mutableMapOf<String, Int>()
        for ((_, tokens) in docs) {
            for (t in tokens.toSet()) df[t] = (df[t] ?: 0) + 1
        }
        val total = if (docs.isEmpty()) 1 else docs.size
        for ((skill, tokens) in docs) {
            val tf = mutableMapOf<String, Int>()
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            val tfidf = mutableMapOf<String, Double>()
            var norm = 0.0
            for ((t, f) in tf) {
                val w = f * ln((total + 1.0) / ((df[t] ?: 0) + 1.0))
                tfidf[t] = w
                norm += w * w
            }
            skills.add(IndexedSkill(skill, tfidf, sqrt(norm)))
        }
    }

    fun retrieve(query: String, threshold: Double = 0.30, top: Int = 3): List<Hit> {
        val q = tokenize(query)
        val qf = mutableMapOf<String, Int>()
        for (t in q) qf[t] = (qf[t] ?: 0) + 1
        val results = mutableListOf<Hit>()
        for (s in skills) {
            if (s.norm == 0.0) continue
            var dot = 0.0
            for ((t, v) in qf) {
                val other = s.docFreq[t]
                if (other != null) dot += v * other
            }
            val score = dot / s.norm
            if (score >= threshold) results.add(Hit(s.skill, score))
        }
        return results.sortedByDescending { it.score }.take(top)
    }

    companion object {
        const val MIN_SCORE = 0.30
    }
}

/** 汉字单字 + 相邻汉字 2-gram + 拉丁/数字连续串，输出唯一集合。 */
fun tokenize(text: String): List<String> {
    val lower = text.lowercase()
    val tokens = mutableListOf<String>()
    val buffer = StringBuilder()
    for (ch in lower) {
        if (ch.isHan()) {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString())
                buffer.clear()
            }
            tokens.add(ch.toString())
        } else if (ch.isAsciiAlphaNum()) {
            buffer.append(ch)
        } else {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString())
                buffer.clear()
            }
        }
    }
    if (buffer.isNotEmpty()) tokens.add(buffer.toString())
    val grams = linkedSetOf<String>()
    for (i in tokens.indices) {
        grams.add(tokens[i])
        if (i > 0 && tokens[i - 1].isSingleHan() && tokens[i].isSingleHan()) {
            grams.add(tokens[i - 1] + tokens[i])
        }
    }
    return grams.toList()
}

private fun Char.isHan(): Boolean = this in '\u4e00'..'\u9fff'
private fun Char.isAsciiAlphaNum(): Boolean = this in 'a'..'z' || this in '0'..'9'
private fun String.isSingleHan(): Boolean = length == 1 && this[0].isHan()