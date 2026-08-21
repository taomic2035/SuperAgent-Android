package com.superagent.body.core.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * journal 记录（P0 自含定义；正式入契约 Protocol.kt/contract.json 待 GPT P1 定稿后搬迁——
 * 字段面已在 handoff 方案请求中报 GPT）。
 */
data class CommandRecord(
    /** 内部 row_id（cursor/水位用）；业务身份是 commandId */
    val id: Long,
    /** Body 生成的 UUID（不变量：禁 seq/文本推导） */
    val commandId: String,
    /** text | pause | resume | stop */
    val kind: String,
    /** 受保护文本（GPT 裁决：存储禁明文。P0 占位 Base64，P1 契合后换本机保护方案） */
    val protectedText: String,
    val status: CommandStatus,
    val taskId: String?,
    val brainSession: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    /** CLAIMED 租约到点（GPT 裁决：claim 写 lease_until；过期 CLAIMED 可被再领取） */
    val leaseUntil: Long = 0,
)

/** 冻结设计 §2 + GPT 裁决（CLAIMED≠ACCEPTED 分离）。LOCALLY_REJECTED 不入 journal。 */
enum class CommandStatus {
    QUEUED,       // Body 已持久保存（用户可见：已排队·等待大脑确认）
    CLAIMED,      // Brain 领取中（写 brain_session_id + lease_until）——不是用户可见 accepted
    ACCEPTED,     // bindTask 成功（prompt_start 已发）——用户可见「理解中」
    WAITING_USER, // 暂停/阻塞/HITL
    RESOLVED,     // 持久终态
    INTERRUPTED,  // 副作用边界未知（crash/超时放弃）——禁自动重放，人工核对
    REJECTED,     // 过期/策略拒绝（receipt 留痕）
}

/**
 * S6-BR10/CT06 command journal（docs/superpowers/specs/2026-08-21-command-ack-design.md §2/§4，设计 GPT 冻结）：
 * Body SQLite 持久命令日志为唯一权威——「已排队≠已接收」，prompt_start（ACCEPTED）才表示 Brain 领取。
 *
 * 不变量（冻结设计 §5-§6）：
 * - commandId 由 Body 生成 UUID（禁 seq/文本推导），唯一约束
 * - LOCALLY_REJECTED 不入 journal（reserve 本地校验失败仅返回原因，不生成记录）
 * - 状态更新先于用户可见回执（调用方顺序责任，本层保证单条原子写）
 * - 过期惰性判定：claim 时 expires_at 已过 → REJECTED（receipt 记原因），不另起定时任务
 * - ack/journal 路径不触碰 control 动作、HITL nonce、ActionGate（纯记录层）
 */
interface CommandDb {
    fun insert(record: CommandRecord): Long

    fun findByCommandId(commandId: String): CommandRecord?

    /** 原子状态迁移：仅当当前 status=from 时更新为 to（返回是否成功） */
    fun compareAndUpdateStatus(commandId: String, from: CommandStatus, to: CommandStatus, now: Long, taskId: String? = null, brainSession: String? = null): Boolean

    /** 原子迁移 + 租约写入（CLAIMED 路径：leaseUntil/接管） */
    fun compareAndUpdate(commandId: String, from: CommandStatus, to: CommandStatus, now: Long, taskId: String? = null, brainSession: String? = null, leaseUntil: Long = 0): Boolean

    /** 指定水位之后的记录（新在前） */
    fun list(sinceId: Long, limit: Int): List<CommandRecord>
}

/** memory.db 生产实现（与 memories/runs 同库，v3 建 commands 表）。 */
class AndroidSqliteCommandDb(private val helper: SQLiteOpenHelper) : CommandDb {
    override fun insert(record: CommandRecord): Long =
        helper.writableDatabase.insert("commands", null, contentValues(record))

    override fun findByCommandId(commandId: String): CommandRecord? =
        helper.readableDatabase.query("commands", null, "command_id=?", arrayOf(commandId), null, null, null, "1").use { c ->
            if (c.moveToFirst()) rowToRecord(c) else null
        }

    override fun compareAndUpdateStatus(
        commandId: String,
        from: CommandStatus,
        to: CommandStatus,
        now: Long,
        taskId: String?,
        brainSession: String?,
    ): Boolean = helper.writableDatabase.update(
        "commands",
        ContentValues().apply {
            put("status", to.name)
            put("updated_at", now)
            taskId?.let { put("task_id", it) }
            brainSession?.let { put("brain_session", it) }
        },
        "command_id=? AND status=?",
        arrayOf(commandId, from.name),
    ) == 1

    override fun compareAndUpdate(
        commandId: String,
        from: CommandStatus,
        to: CommandStatus,
        now: Long,
        taskId: String?,
        brainSession: String?,
        leaseUntil: Long,
    ): Boolean = helper.writableDatabase.update(
        "commands",
        ContentValues().apply {
            put("status", to.name)
            put("updated_at", now)
            taskId?.let { put("task_id", it) }
            brainSession?.let { put("brain_session", it) }
            if (leaseUntil > 0) put("lease_until", leaseUntil)
        },
        "command_id=? AND status=?",
        arrayOf(commandId, from.name),
    ) == 1

    override fun list(sinceId: Long, limit: Int): List<CommandRecord> =
        helper.readableDatabase.query("commands", null, "id>?", arrayOf("$sinceId"), null, null, "id DESC", "$limit").use { c ->
            val out = mutableListOf<CommandRecord>()
            while (c.moveToNext()) out.add(rowToRecord(c))
            out
        }

    private fun contentValues(r: CommandRecord): ContentValues = ContentValues().apply {
        put("command_id", r.commandId)
        put("kind", r.kind)
        put("protected_text", r.protectedText)
        put("lease_until", r.leaseUntil)
        put("status", r.status.name)
        put("task_id", r.taskId)
        put("brain_session", r.brainSession)
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
        put("expires_at", r.expiresAt)
        put("lease_until", r.leaseUntil)
    }

    private fun rowToRecord(c: android.database.Cursor): CommandRecord = CommandRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        commandId = c.getString(c.getColumnIndexOrThrow("command_id")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        protectedText = c.getString(c.getColumnIndexOrThrow("protected_text")),
        status = CommandStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        brainSession = c.getString(c.getColumnIndexOrThrow("brain_session")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
        leaseUntil = c.getLong(c.getColumnIndexOrThrow("lease_until")),
    )

    companion object {
        fun createTable(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS commands (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "command_id TEXT NOT NULL UNIQUE," +
                    "kind TEXT NOT NULL, protected_text TEXT NOT NULL," +
                    "status TEXT NOT NULL, task_id TEXT, brain_session TEXT," +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL," +
                    "lease_until INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_commands_status ON commands(status)")
        }
    }
}

/** journal 逻辑层：reserve/claim/resolve 状态机（JVM 单测用内存 fake 全覆盖）。 */
class CommandStore(private val db: CommandDb) {

    sealed interface ReserveOutcome {
        /** 已入 journal（QUEUED）——commandId 为 Body 生成 UUID */
        data class Queued(val commandId: String) : ReserveOutcome

        /** 本地校验失败，未入 journal（冻结设计 §2：不生成记录） */
        data class LocallyRejected(val reason: String) : ReserveOutcome
    }

    /** 入 journal 前本地校验（参数级；OFFLINE 等运行态校验在 UI 层 C-07 已前置）。 */
    fun reserve(kind: String, text: String, ttlMs: Long = 10 * 60 * 1000): ReserveOutcome {
        val k = kind.trim()
        if (k !in KINDS) return ReserveOutcome.LocallyRejected("kind 非法：$k（允许 text|pause|resume|stop）")
        val t = text.trim()
        if (t.isEmpty()) return ReserveOutcome.LocallyRejected("text 不能为空")
        if (t.length > MAX_TEXT) return ReserveOutcome.LocallyRejected("text 超长（>${MAX_TEXT}）")
        if ((k == "pause" || k == "resume" || k == "stop") && t.length > MAX_CONTROL_TEXT) {
            return ReserveOutcome.LocallyRejected("控制命令 text 过长")
        }
        val now = System.currentTimeMillis()
        val commandId = UUID.randomUUID().toString() // 不变量：Body 生成 UUID，禁 seq/文本推导
        db.insert(
            CommandRecord(
                id = 0, commandId = commandId, kind = k,
                protectedText = protect(t), // GPT 裁决：存储禁明文（P0 Base64 占位，P1 换本机保护）
                status = CommandStatus.QUEUED, taskId = null, brainSession = null,
                createdAt = now, updatedAt = now, expiresAt = now + ttlMs,
            ),
        )
        return ReserveOutcome.Queued(commandId)
    }

    /**
     * Brain 领取（GPT 裁决：CLAIMED≠ACCEPTED）：
     * - QUEUED 未过期 → CLAIMED（写 brainSession + leaseUntil 租约）
     * - 过期 QUEUED → REJECTED（惰性，receipt=expired）
     * - **租约过期的 CLAIMED**（领取者失联）→ 可被再领取（新租约覆盖）
     * - 租约期内的 CLAIMED（自己续领幂等返回；他方拒绝）
     */
    sealed interface ClaimOutcome {
        data class Claimed(val commandId: String, val text: String) : ClaimOutcome
        data class Rejected(val reason: String) : ClaimOutcome
    }

    fun claimNext(commandId: String, brainSession: String, leaseMs: Long = 60_000): ClaimOutcome {
        val rec = db.findByCommandId(commandId) ?: return ClaimOutcome.Rejected("commandId 不存在")
        val now = System.currentTimeMillis()
        val expired = rec.expiresAt in 1 until now
        when (rec.status) {
            CommandStatus.QUEUED -> {
                if (expired) {
                    db.compareAndUpdateStatus(commandId, CommandStatus.QUEUED, CommandStatus.REJECTED, now)
                    return ClaimOutcome.Rejected("已过期（入队 ${now - rec.createdAt}ms 前）")
                }
                if (db.compareAndUpdate(commandId, CommandStatus.QUEUED, CommandStatus.CLAIMED, now, brainSession = brainSession, leaseUntil = now + leaseMs)) {
                    return ClaimOutcome.Claimed(commandId, rec.protectedText)
                }
                return ClaimOutcome.Rejected("并发领取竞争失败（他方已领）")
            }
            CommandStatus.CLAIMED -> {
                if (rec.brainSession == brainSession) {
                    if (expired) {
                        db.compareAndUpdateStatus(commandId, CommandStatus.CLAIMED, CommandStatus.REJECTED, now)
                        return ClaimOutcome.Rejected("已过期")
                    }
                    // 幂等续租
                    db.compareAndUpdate(commandId, CommandStatus.CLAIMED, CommandStatus.CLAIMED, now, brainSession = brainSession, leaseUntil = now + leaseMs)
                    return ClaimOutcome.Claimed(commandId, rec.protectedText)
                }
                if (rec.leaseUntil in 1..now) {
                    // 租约过期：可被新领取者接管
                    if (db.compareAndUpdate(commandId, CommandStatus.CLAIMED, CommandStatus.CLAIMED, now, brainSession = brainSession, leaseUntil = now + leaseMs)) {
                        return ClaimOutcome.Claimed(commandId, rec.protectedText)
                    }
                }
                return ClaimOutcome.Rejected("租约期内他方持有")
            }
            else -> return ClaimOutcome.Rejected("当前状态 ${rec.status} 不可领取")
        }
    }

    /** bindTask（GPT 裁决）：CLAIMED→ACCEPTED 原子绑定 taskId——此后 prompt_start 才用户可见。 */
    fun bindTask(commandId: String, taskId: String, brainSession: String): Boolean {
        val rec = db.findByCommandId(commandId) ?: return false
        if (rec.status != CommandStatus.CLAIMED || rec.brainSession != brainSession) return false
        return db.compareAndUpdateStatus(commandId, CommandStatus.CLAIMED, CommandStatus.ACCEPTED, System.currentTimeMillis(), taskId = taskId)
    }

    /** settle（GPT 裁决）：terminal/中间态必须同时校验 commandId+taskId+brainSession+来源状态。 */
    fun settle(commandId: String, taskId: String?, brainSession: String, to: CommandStatus): Boolean {
        val rec = db.findByCommandId(commandId) ?: return false
        if (rec.brainSession != null && rec.brainSession != brainSession) return false
        if (rec.taskId != null && taskId != null && rec.taskId != taskId) return false
        val now = System.currentTimeMillis()
        val legal = (rec.status == CommandStatus.ACCEPTED && to in ACCEPTED_NEXTS) ||
            (rec.status == CommandStatus.WAITING_USER && to in WAITING_NEXTS) ||
            (rec.status == CommandStatus.INTERRUPTED && to == CommandStatus.RESOLVED)
        if (!legal) return false
        return db.compareAndUpdateStatus(commandId, rec.status, to, now)
    }

    /** 惰性清扫（GPT 裁决 D2：入口含 claim 与 list/reconcile）——返回清扫数。 */
    fun sweepExpired(): Int {
        var swept = 0
        val now = System.currentTimeMillis()
        for (rec in db.list(0, 200)) {
            val expired = rec.expiresAt in 1 until now
            when {
                rec.status == CommandStatus.QUEUED && expired -> {
                    if (db.compareAndUpdateStatus(rec.commandId, CommandStatus.QUEUED, CommandStatus.REJECTED, now)) swept++
                }
                rec.status == CommandStatus.CLAIMED && expired -> {
                    // 已领取但过期：副作用边界可能未知（brain 失联）——INTERRUPTED 禁自动重放
                    if (db.compareAndUpdateStatus(rec.commandId, CommandStatus.CLAIMED, CommandStatus.INTERRUPTED, now)) swept++
                }
            }
        }
        return swept
    }

    fun list(sinceId: Long = 0, limit: Int = 50): List<CommandRecord> = db.list(sinceId, limit.coerceIn(1, 200))

    companion object {
        val KINDS = setOf("text", "pause", "resume", "stop")
        const val MAX_TEXT = 500
        const val MAX_CONTROL_TEXT = 40
        private val ACCEPTED_NEXTS = setOf(CommandStatus.WAITING_USER, CommandStatus.RESOLVED, CommandStatus.INTERRUPTED)
        private val WAITING_NEXTS = setOf(CommandStatus.ACCEPTED, CommandStatus.RESOLVED)

        /** P0 保护占位：Base64（P1 契合后换本机加密方案——密钥/算法由 GPT P1 定） */
        fun protect(text: String): String = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        fun unprotect(protectedText: String): String = String(java.util.Base64.getDecoder().decode(protectedText))
    }
}
