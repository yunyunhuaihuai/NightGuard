package com.nightguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "nightguard")

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

    // ---------- 规则 ----------
    suspend fun rules(ctx: Context): List<Rule> =
        RuleJson.rulesFromJson(ctx.dataStore.data.first()[KEY_RULES] ?: "[]")

    suspend fun saveRules(ctx: Context, rules: List<Rule>) {
        ctx.dataStore.edit { it[KEY_RULES] = RuleJson.rulesToJson(rules) }
    }

    fun rulesSync(ctx: Context): List<Rule> = runBlocking { rules(ctx) }

    fun ruleByIdSync(ctx: Context, id: String): Rule? =
        rulesSync(ctx).firstOrNull { it.id == id }

    // ---------- 日志（仅记录触发元数据，不存消息内容） ----------
    suspend fun log(ctx: Context): List<LogEntry> =
        RuleJson.logFromJson(ctx.dataStore.data.first()[KEY_LOG] ?: "[]")

    suspend fun addLog(ctx: Context, e: LogEntry) {
        ctx.dataStore.edit { p ->
            val list = RuleJson.logFromJson(p[KEY_LOG] ?: "[]").toMutableList()
            list.add(0, e)
            while (list.size > 200) list.removeAt(list.size - 1)
            p[KEY_LOG] = RuleJson.logToJson(list)
        }
    }

    fun logSync(ctx: Context): List<LogEntry> = runBlocking { log(ctx) }

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

    // ---------- 待响闹钟（防重复定时） ----------
    suspend fun pendingAlarms(ctx: Context): Map<String, Long> =
        RuleJson.mapFromJson(ctx.dataStore.data.first()[KEY_PENDING] ?: "{}")

    suspend fun setPendingAlarm(ctx: Context, ruleId: String, at: Long) {
        ctx.dataStore.edit { p ->
            val m = RuleJson.mapFromJson(p[KEY_PENDING] ?: "{}").toMutableMap()
            if (at <= 0L) m.remove(ruleId) else m[ruleId] = at
            p[KEY_PENDING] = RuleJson.mapToJson(m)
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

    suspend fun lastRebindAt(ctx: Context): Long =
        ctx.dataStore.data.first()[KEY_LAST_REBIND] ?: 0L

    suspend fun markRebind(ctx: Context, at: Long) {
        ctx.dataStore.edit { it[KEY_LAST_REBIND] = at }
    }
}
