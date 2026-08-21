package com.superagent.body.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.superagent.common.MemoryEntry
import com.superagent.common.MemoryImportResult
import com.superagent.common.MemorySearchHit
import com.superagent.common.MemorySearchResult
import com.superagent.common.MemoryWriteResult
import com.superagent.common.RunRecord
import com.superagent.common.TraceStep
import com.superagent.common.tokenize

/**
 * ME-1 记忆存储（docs/15 §4）：三层记忆的 body 侧 SQLite 权威实现。
 * Iron Law：不删自己的库——被顶替的旧版走 revoked 软删留痕；forget 仅按用户指令物理删。
 */
interface MemoryDb {
    fun insert(entry: MemoryEntry): Long

    fun update(entry: MemoryEntry)

    fun delete(id: Long): Boolean

    fun findById(id: Long): MemoryEntry?

    /** 全部未撤销条目（设备端规模为百级，检索在内存做，避免 SQL LIKE 转义面） */
    fun active(): List<MemoryEntry>

    /** 全量（含 revoked）——ME-8 备份导出用（Iron Law 留痕） */
    fun all(): List<MemoryEntry>
}

/** 生产实现：files/memory.db（android.database.sqlite，零新依赖；docs/15 §4 建表）。memories + runs 两表（ME-1/ME-3b）。 */
class AndroidSqliteMemoryDb(context: Context, name: String = "memory.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, DB_VERSION), MemoryDb, RunArchiveDb {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS memories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "kind TEXT NOT NULL, topic TEXT NOT NULL, content TEXT NOT NULL," +
                "confidence REAL NOT NULL DEFAULT 0.5, source TEXT NOT NULL," +
                "hits INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_topic ON memories(topic, kind) WHERE revoked=0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS runs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "goal TEXT NOT NULL, outcome TEXT NOT NULL, failure_reason TEXT," +
                "trace_json TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER NOT NULL, archived_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1：无迁移路径
    }

    override fun insert(entry: MemoryEntry): Long =
        writableDatabase.insert("memories", null, contentValues(entry))

    override fun update(entry: MemoryEntry) {
        writableDatabase.update("memories", contentValues(entry), "id=?", arrayOf(entry.id.toString()))
    }

    override fun delete(id: Long): Boolean =
        writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) > 0

    override fun findById(id: Long): MemoryEntry? =
        query("id=?", arrayOf(id.toString())).firstOrNull()

    override fun active(): List<MemoryEntry> = query("revoked=0", emptyArray())

    override fun all(): List<MemoryEntry> = query("1", emptyArray())

    private fun query(where: String, args: Array<String>): List<MemoryEntry> =
        readableDatabase.query("memories", null, where, args, null, null, "updated_at DESC").use { c ->
            val out = mutableListOf<MemoryEntry>()
            while (c.moveToNext()) out.add(rowToEntry(c))
            out
        }

    private fun contentValues(e: MemoryEntry) = ContentValues().apply {
        put("kind", e.kind)
        put("topic", e.topic)
        put("content", e.content)
        put("confidence", e.confidence)
        put("source", e.source)
        put("hits", e.hits)
        put("revoked", if (e.revoked) 1 else 0)
        put("created_at", e.createdAt)
        put("updated_at", e.updatedAt)
    }

    private fun rowToEntry(c: android.database.Cursor): MemoryEntry = MemoryEntry(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        topic = c.getString(c.getColumnIndexOrThrow("topic")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        hits = c.getInt(c.getColumnIndexOrThrow("hits")),
        revoked = c.getInt(c.getColumnIndexOrThrow("revoked")) != 0,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    // ---- RunArchiveDb（ME-3b runs 表）----

    private val runJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun insert(record: RunRecord): Long =
        writableDatabase.insert(
            "runs", null,
            ContentValues().apply {
                put("goal", record.goal)
                put("outcome", record.outcome)
                put("failure_reason", record.failureReason)
                put("trace_json", runJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.superagent.common.TraceStep.serializer()), record.trace))
                put("started_at", record.startedAt)
                put("finished_at", record.finishedAt)
                put("archived_at", record.archivedAt)
            },
        )

    override fun list(limit: Int): List<RunRecord> =
        readableDatabase.query("runs", null, null, null, null, null, "id DESC", "$limit").use { c ->
            val out = mutableListOf<RunRecord>()
            while (c.moveToNext()) {
                val trace = runCatching {
                    runJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(com.superagent.common.TraceStep.serializer()),
                        c.getString(c.getColumnIndexOrThrow("trace_json")),
                    )
                }.getOrDefault(emptyList())
                out.add(
                    RunRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        goal = c.getString(c.getColumnIndexOrThrow("goal")),
                        outcome = c.getString(c.getColumnIndexOrThrow("outcome")),
                        failureReason = c.getString(c.getColumnIndexOrThrow("failure_reason")),
                        trace = trace,
                        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                        finishedAt = c.getLong(c.getColumnIndexOrThrow("finished_at")),
                        archivedAt = c.getLong(c.getColumnIndexOrThrow("archived_at")),
                    ),
                )
            }
            out
        }

    companion object {
        const val DB_VERSION = 1
    }
}

/** 记忆逻辑层：去重合并/软删/检索排序——经 MemoryDb 隔离，JVM 单测用内存 fake 全覆盖。 */
class MemoryStore(private val db: MemoryDb) {

    /**
     * 写入（去重合并语义，docs/15 §4）：
     * - 同 topic+kind 不存在 → 新条目
     * - 同 topic+kind 同 content → confidence +0.1（cap 1.0）+ hits+1（重述强化）
     * - 同 topic+kind 不同 content → 新版入库，旧版 revoked=1（修订留痕）
     */
    fun write(
        kind: String,
        topic: String,
        content: String,
        source: String,
        confidence: Double = 0.5,
    ): MemoryWriteResult {
        val k = kind.trim()
        require(k in KINDS) { "kind 非法：$k（允许 fact|preference|lesson|routine）" }
        val t = topic.trim()
        val c = content.trim()
        require(t.isNotEmpty()) { "topic 不能为空" }
        require(c.isNotEmpty()) { "content 不能为空" }
        val src = source.trim()
        require(src.isNotEmpty()) { "source 不能为空" }
        require(!containsPii(t) && !containsPii(c)) { "内容含疑似身份证/银行卡号，拒绝入库（隐私红线）" }

        val now = System.currentTimeMillis()
        val existing = db.active().firstOrNull { it.kind == k && it.topic == t }
        if (existing == null) {
            val id = db.insert(newEntry(k, t, c, src, confidence.coerceIn(0.0, 1.0), now))
            return MemoryWriteResult(id, merged = false)
        }
        if (existing.content == c) {
            db.update(
                existing.copy(
                    confidence = (existing.confidence + 0.1).coerceAtMost(1.0),
                    hits = existing.hits + 1,
                    updatedAt = now,
                ),
            )
            return MemoryWriteResult(existing.id, merged = true)
        }
        db.update(existing.copy(revoked = true, updatedAt = now))
        val id = db.insert(newEntry(k, t, c, src, confidence.coerceIn(0.0, 1.0), now))
        return MemoryWriteResult(id, merged = true)
    }

    /**
     * 检索（docs/15 §4：关键词 + recency/hits 排序）：
     * 复用 body/common tokenize（汉字 1+2gram+拉丁串），score = 查询覆盖率 + 置信/命中微调；
     * 返回条目 hits+1（命中加权）。
     */
    fun search(query: String, limit: Int = 5): MemorySearchResult {
        val n = limit.coerceIn(1, 20)
        val qTokens = tokenize(query).toSet()
        if (qTokens.isEmpty()) return MemorySearchResult(emptyList())
        val ranked = db.active().mapNotNull { m ->
            val overlap = qTokens.intersect(tokenize("${m.topic} ${m.content}").toSet())
            if (overlap.isEmpty()) return@mapNotNull null
            val score = overlap.size.toDouble() / qTokens.size + 0.05 * m.confidence + 0.001 * m.hits.coerceAtMost(50)
            m to score
        }.sortedWith(
            compareByDescending<Pair<MemoryEntry, Double>> { it.second }.thenByDescending { it.first.updatedAt },
        ).take(n)
        // 命中计数（docs/15 §4 hits 语义）：返回条目即自增后的值，与库内一致
        val result = ranked.map { (m, score) ->
            val bumped = m.copy(hits = m.hits + 1)
            db.update(bumped)
            MemorySearchHit(bumped, score)
        }
        return MemorySearchResult(result)
    }

    /** 修订（用户纠正"不对"路径）：改写内容，source 可标 user-corrected。 */
    fun revise(id: Long, content: String, source: String? = null): Boolean {
        val c = content.trim()
        require(c.isNotEmpty()) { "content 不能为空" }
        val m = db.findById(id) ?: return false
        db.update(
            m.copy(
                content = c.take(MAX_CONTENT),
                source = source?.trim()?.take(MAX_SOURCE)?.ifEmpty { m.source } ?: m.source,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /** 用户删除权 > Iron Law：物理删（docs/15 §4——操作日志不含内容）。 */
    fun forget(id: Long): Boolean = db.delete(id)

    /** ME-8 备份导出：全量（含 revoked 留痕）。 */
    fun exportAll(): List<MemoryEntry> = db.all()

    /**
     * ME-8 恢复（补缺语义，docs/15 §7 ME-8 行）：
     * - 只插入 body 当前缺失的 topic+kind active 组合（不覆盖现有——body 为准）
     * - revoked 历史不回写（快照文件本身留档即可，Iron Law 不要求重建历史）
     * - PII 纵深同 write（快照可能来自旧版本/外部，同样不信任）
     */
    fun importEntries(entries: List<MemoryEntry>): MemoryImportResult {
        val activeKeys = db.active().map { it.kind to it.topic }.toSet()
        var inserted = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        for (e in entries) {
            val kind = e.kind.trim()
            val topic = e.topic.trim()
            if (e.revoked || kind !in KINDS || topic.isEmpty() || containsPii(topic) || containsPii(e.content)) {
                skipped++
                continue
            }
            if (kind to topic in activeKeys) {
                skipped++
                continue
            }
            db.insert(newEntry(kind, topic, e.content.trim(), e.source.trim(), e.confidence, now))
            inserted++
        }
        return MemoryImportResult(inserted, skipped)
    }

    private fun newEntry(kind: String, topic: String, content: String, source: String, confidence: Double, now: Long) =
        MemoryEntry(
            id = 0,
            kind = kind,
            topic = topic.take(MAX_TOPIC),
            content = content.take(MAX_CONTENT),
            confidence = confidence,
            source = source.take(MAX_SOURCE),
            hits = 0,
            revoked = false,
            createdAt = now,
            updatedAt = now,
        )

    companion object {
        val KINDS = setOf("fact", "preference", "lesson", "routine")
        const val MAX_TOPIC = 32
        const val MAX_CONTENT = 200
        const val MAX_SOURCE = 80

        // G2-01 纵深防御（BR-04.4 首块）：写入侧不信任调用方——brain 侧 reflect/lessons/remember
        // 已有 redact 前置，此处兜底拦截 PII 直接入库（身份证 18 位 / 银行卡 15-19 位数字串）
        private val ID_CARD = Regex("""\d{17}[\dXx]""")
        private val CARD_NUMBER = Regex("""\b\d{15,19}\b""")
        fun containsPii(text: String): Boolean = ID_CARD.containsMatchIn(text) || CARD_NUMBER.containsMatchIn(text)
    }
}
