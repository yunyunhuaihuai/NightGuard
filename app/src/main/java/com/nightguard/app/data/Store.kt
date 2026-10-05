package com.nightguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// internal：同模块的单元测试需要直接写入旧键验证迁移逻辑
internal val Context.dataStore by preferencesDataStore(name = "nightguard")

/**
 * 所有本地状态：规则、日志、动作一的“现场”、去重表、监听器连接状态。
 * 只存 App 私有目录，不联网、不存消息内容。
 */
object Store {

    private val KEY_RULES = stringPreferencesKey("rules")
    private val KEY_LOG = stringPreferencesKey("log")
    private val KEY_LAST_TRIGGER = stringPreferencesKey("lastTrigger")
    private val KEY_PENDING = stringPreferencesKey("pendingAlarm")
    private val KEY_SUPPRESS = stringPreferencesKey("suppressUntil")
    private val KEY_CONNECTED = booleanPreferencesKey("listenerConnected")
    private val KEY_LAST_CONNECTED = longPreferencesKey("lastConnectedAt")
    private val KEY_LAST_DISCONNECTED = longPreferencesKey("lastDisconnectedAt")
    private val KEY_LAST_REBIND = longPreferencesKey("lastRebindAt")
    private val KEY_TASKS = stringPreferencesKey("pendingTasks")

    /** 历史“来电候选”日志脱敏后的占位文案 */
    private const val REDACTED_PLACEHOLDER = "（旧版本记录的来电通知原文已清除）"

    // ---------- 待响任务（第二阶段统一生命周期，替代 pendingAlarm） ----------

    /**
     * DataStore 任务仓库。read 内完成旧 `pendingAlarm`（Map<ruleId,毫秒>）的一次性迁移：
     * 转成 legacy 任务（未来时刻保持 armed，已过期的直接标记 expired 供诊断），
     * 迁移后删除旧键，不丢用户数据。
     */
    val pendingTaskBacking: PendingTaskBacking = object : PendingTaskBacking {
        override suspend fun read(ctx: TaskCtx): List<PendingTask> {
            val context = ctx as Context
            var out: List<PendingTask> = emptyList()
            context.dataStore.edit { p ->
                val existing = PendingTaskJson.listFromJson(p[KEY_TASKS] ?: "[]")
                val legacy = RuleJson.mapFromJson(p[KEY_PENDING] ?: "{}")
                out = if (legacy.isNotEmpty()) {
                    val n = System.currentTimeMillis()
                    val converted = legacy.map { (ruleId, at) ->
                        PendingTask(
                            taskId = PendingTask.LEGACY_PREFIX + ruleId,
                            ruleId = ruleId,
                            fireAt = at,
                            source = "旧版本迁移",
                            createdAt = n,
                            fromCall = false,
                            status = if (at > n) TaskStatus.ARMED else TaskStatus.EXPIRED,
                        )
                    }
                    p[KEY_TASKS] = PendingTaskJson.listToJson(existing + converted)
                    p.remove(KEY_PENDING)
                    existing + converted
                } else {
                    existing
                }
            }
            return out
        }

        override suspend fun write(ctx: TaskCtx, tasks: List<PendingTask>) {
            (ctx as Context).dataStore.edit { it[KEY_TASKS] = PendingTaskJson.listToJson(tasks) }
        }
    }

    // ---------- 规则 ----------
    suspend fun rules(ctx: Context): List<Rule> =
        RuleJson.rulesFromJson(ctx.dataStore.data.first()[KEY_RULES] ?: "[]")

    /** 规则 Flow：任何写操作（含事务内按 ID 更新）都会推送新列表 */
    fun rulesFlow(ctx: Context): Flow<List<Rule>> =
        ctx.dataStore.data.map { RuleJson.rulesFromJson(it[KEY_RULES] ?: "[]") }

    suspend fun saveRules(ctx: Context, rules: List<Rule>) {
        ctx.dataStore.edit { it[KEY_RULES] = RuleJson.rulesToJson(rules) }
    }

    /** 新增或按 ID 覆盖保存单条规则（单事务，不再整表先读后写） */
    suspend fun upsertRule(ctx: Context, rule: Rule) {
        ctx.dataStore.edit { p ->
            val list = RuleJson.rulesFromJson(p[KEY_RULES] ?: "[]").filterNot { it.id == rule.id } + rule
            p[KEY_RULES] = RuleJson.rulesToJson(list)
        }
    }

    /**
     * 事务内按 ID 更新单条规则：读-改-写在一个 dataStore.edit 中完成，
     * 并发修改不同规则不会互相覆盖（每个事务都基于最新数据）。
     */
    suspend fun updateRule(ctx: Context, ruleId: String, transform: (Rule) -> Rule): Boolean {
        var changed = false
        ctx.dataStore.edit { p ->
            val list = RuleJson.rulesFromJson(p[KEY_RULES] ?: "[]")
            val idx = list.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                val updated = list.toMutableList()
                updated[idx] = transform(list[idx])
                p[KEY_RULES] = RuleJson.rulesToJson(updated)
                changed = true
            }
        }
        return changed
    }

    /** 事务内删除规则并同事务清理其抑制点与冷却记录（待响任务由状态机先行取消） */
    suspend fun deleteRule(ctx: Context, ruleId: String) {
        ctx.dataStore.edit { p ->
            val list = RuleJson.rulesFromJson(p[KEY_RULES] ?: "[]")
            if (list.any { it.id == ruleId }) {
                p[KEY_RULES] = RuleJson.rulesToJson(list.filterNot { it.id == ruleId })
            }
            val sup = RuleJson.mapFromJson(p[KEY_SUPPRESS] ?: "{}")
            if (sup.containsKey(ruleId)) {
                val m = sup.toMutableMap(); m.remove(ruleId)
                p[KEY_SUPPRESS] = RuleJson.mapToJson(m)
            }
            val lt = RuleJson.mapFromJson(p[KEY_LAST_TRIGGER] ?: "{}")
            if (lt.containsKey(ruleId)) {
                val m = lt.toMutableMap(); m.remove(ruleId)
                p[KEY_LAST_TRIGGER] = RuleJson.mapToJson(m)
            }
        }
    }

    suspend fun ruleById(ctx: Context, id: String): Rule? =
        rules(ctx).firstOrNull { it.id == id }

    // ---------- 日志（仅记录触发元数据，不存消息内容） ----------
    suspend fun log(ctx: Context): List<LogEntry> =
        RuleJson.logFromJson(ctx.dataStore.data.first()[KEY_LOG] ?: "[]")

    /** 日志 Flow：新增条目即推送（最新在前） */
    fun logFlow(ctx: Context): Flow<List<LogEntry>> =
        ctx.dataStore.data.map { RuleJson.logFromJson(it[KEY_LOG] ?: "[]") }

    suspend fun addLog(ctx: Context, e: LogEntry) {
        ctx.dataStore.edit { p ->
            val list = RuleJson.logFromJson(p[KEY_LOG] ?: "[]").toMutableList()
            list.add(0, e)
            while (list.size > 200) list.removeAt(list.size - 1)
            p[KEY_LOG] = RuleJson.logToJson(list)
        }
    }

    /**
     * 一次性脱敏历史“来电候选”日志：旧版本会把来电通知原文（可含完整号码/联系人姓名）
     * 写进持久化日志。只替换这类条目的正文部分为脱敏占位，与该缺陷无关的记录原样保留；
     * 幂等，可在每次进程启动时调用。
     */
    suspend fun redactLegacyCallCandidateLogs(ctx: Context) {
        ctx.dataStore.edit { p ->
            val list = RuleJson.logFromJson(p[KEY_LOG] ?: "[]")
            var changed = false
            val fixed = list.map { e ->
                val legacy = e.source.startsWith("来电候选") &&
                        !e.actions.startsWith("通知文本 ") &&
                        !e.actions.startsWith(REDACTED_PLACEHOLDER)
                if (legacy) {
                    changed = true
                    e.copy(actions = REDACTED_PLACEHOLDER)
                } else e
            }
            if (changed) p[KEY_LOG] = RuleJson.logToJson(fixed)
        }
    }

    // ---------- 冷却去重 ----------
    suspend fun lastTrigger(ctx: Context): Map<String, Long> =
        RuleJson.mapFromJson(ctx.dataStore.data.first()[KEY_LAST_TRIGGER] ?: "{}")

    suspend fun markTriggered(ctx: Context, ruleId: String, at: Long) {
        ctx.dataStore.edit { p ->
            val m = RuleJson.mapFromJson(p[KEY_LAST_TRIGGER] ?: "{}").toMutableMap()
            m[ruleId] = at
            p[KEY_LAST_TRIGGER] = RuleJson.mapToJson(m)
        }
    }

    // ---------- 待响任务（第二阶段统一生命周期，替代 pendingAlarm；旧键仅迁移用） ----------
    suspend fun pendingTasks(ctx: Context): List<PendingTask> = pendingTaskBacking.read(ctx)

    /** 禁用规则等场景：不删规则，只清其运行状态（抑制点、冷却记录） */
    suspend fun removeRuleStateKeys(ctx: Context, ruleId: String) {
        ctx.dataStore.edit { p ->
            val sup = RuleJson.mapFromJson(p[KEY_SUPPRESS] ?: "{}")
            if (sup.containsKey(ruleId)) {
                val m = sup.toMutableMap(); m.remove(ruleId)
                p[KEY_SUPPRESS] = RuleJson.mapToJson(m)
            }
            val lt = RuleJson.mapFromJson(p[KEY_LAST_TRIGGER] ?: "{}")
            if (lt.containsKey(ruleId)) {
                val m = lt.toMutableMap(); m.remove(ruleId)
                p[KEY_LAST_TRIGGER] = RuleJson.mapToJson(m)
            }
        }
    }

    // ---------- “今天不再响”抑制（手动在通知上设置，窗口结束自动失效） ----------
    suspend fun suppressUntil(ctx: Context): Map<String, Long> =
        RuleJson.mapFromJson(ctx.dataStore.data.first()[KEY_SUPPRESS] ?: "{}")

    suspend fun setSuppressUntil(ctx: Context, ruleId: String, until: Long) {
        ctx.dataStore.edit { p ->
            val m = RuleJson.mapFromJson(p[KEY_SUPPRESS] ?: "{}").toMutableMap()
            if (until <= 0L) m.remove(ruleId) else m[ruleId] = until
            p[KEY_SUPPRESS] = RuleJson.mapToJson(m)
        }
    }

    // ---------- 通知监听器连接状态（心跳自愈用，v2：落盘时间戳比对） ----------
    suspend fun setListenerState(ctx: Context, connected: Boolean, at: Long) {
        ctx.dataStore.edit {
            it[KEY_CONNECTED] = connected
            if (connected) it[KEY_LAST_CONNECTED] = at else it[KEY_LAST_DISCONNECTED] = at
        }
    }

    suspend fun isConnected(ctx: Context): Boolean =
        ctx.dataStore.data.first()[KEY_CONNECTED] ?: false

    /** 历史连接时间戳，仅用于诊断展示 */
    suspend fun lastConnectedAt(ctx: Context): Long =
        ctx.dataStore.data.first()[KEY_LAST_CONNECTED] ?: 0L

    suspend fun lastDisconnectedAt(ctx: Context): Long =
        ctx.dataStore.data.first()[KEY_LAST_DISCONNECTED] ?: 0L

    suspend fun lastRebindAt(ctx: Context): Long =
        ctx.dataStore.data.first()[KEY_LAST_REBIND] ?: 0L

    suspend fun markRebind(ctx: Context, at: Long) {
        ctx.dataStore.edit { it[KEY_LAST_REBIND] = at }
    }
}
