package com.nightguard.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.LogKind
import com.nightguard.app.data.PendingTask
import com.nightguard.app.data.PendingTaskJson
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import com.nightguard.app.data.TaskStatus
import com.nightguard.app.data.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第三阶段回归：Store 事务内按 ID 更新（并发修改不同规则不互相覆盖）、
 * 删除规则统一清理、Flow 推送、旧 pendingAlarm 迁移、日志 kind 兼容旧记录。
 * Robolectric 相同 @Config 的测试类共享 DataStore 单例：所有断言只看本方法写入的数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreTxTest {

    @Test
    fun `并发修改不同规则不会互相覆盖`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.upsertRule(ctx, Rule(id = "tx-a", name = "A"))
        Store.upsertRule(ctx, Rule(id = "tx-b", name = "B"))
        listOf(
            launch(Dispatchers.IO) { Store.updateRule(ctx, "tx-a") { it.copy(name = "A2") } },
            launch(Dispatchers.IO) { Store.updateRule(ctx, "tx-b") { it.copy(name = "B2") } },
        ).joinAll()
        val rules = Store.rules(ctx).associateBy { it.id }
        assertEquals("A2", rules["tx-a"]?.name)
        assertEquals("B2", rules["tx-b"]?.name)
    }

    @Test
    fun `updateRule 不存在的规则返回 false`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(Store.updateRule(ctx, "tx-missing") { it.copy(name = "x") })
    }

    @Test
    fun `deleteRule 同事务清理抑制点与冷却记录`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.upsertRule(ctx, Rule(id = "tx-del", name = "D"))
        Store.setSuppressUntil(ctx, "tx-del", 123L)
        Store.markTriggered(ctx, "tx-del", 456L)
        Store.deleteRule(ctx, "tx-del")
        assertFalse(Store.rules(ctx).any { it.id == "tx-del" })
        assertFalse(Store.suppressUntil(ctx).containsKey("tx-del"))
        assertFalse(Store.lastTrigger(ctx).containsKey("tx-del"))
    }

    @Test
    fun `rulesFlow 反映最新规则列表`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val flow = Store.rulesFlow(ctx)
        Store.upsertRule(ctx, Rule(id = "tx-flow", name = "F"))
        val updated = withTimeoutOrNull(5000) {
            flow.first { it.any { r -> r.id == "tx-flow" } }
        }
        assertTrue("Flow 应能读到包含新规则的最新列表", updated.orEmpty().any { it.id == "tx-flow" })
    }

    @Test
    fun `旧 pendingAlarm 键一次性迁移为任务且不重复`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val legacyKey = stringPreferencesKey("pendingAlarm")
        val future = System.currentTimeMillis() + 60_000L
        ctx.dataStore.edit { it[legacyKey] = """{"mig-r":$future}""" }

        val tasks = Store.pendingTasks(ctx)
        val migrated = tasks.filter { it.taskId == "legacy-mig-r" }
        assertEquals(1, migrated.size)
        assertEquals(TaskStatus.ARMED, migrated.single().status)
        assertEquals(future, migrated.single().fireAt)

        // 再读：旧键已清除，不重复迁移
        assertEquals(1, Store.pendingTasks(ctx).count { it.taskId == "legacy-mig-r" })
    }

    @Test
    fun `日志 kind 写入与旧记录兼容`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.addLog(ctx, LogEntry(991L, "r", "s", "a", LogKind.RING_START))
        Store.addLog(ctx, LogEntry(992L, "r", "s", "旧记录无 kind"))
        val mine = Store.log(ctx).filter { it.time == 991L || it.time == 992L }
        assertEquals(LogKind.RING_START, mine.first { it.time == 991L }.kind)
        assertEquals("", mine.first { it.time == 992L }.kind)
    }

    @Test
    fun `PendingTaskJson 编解码往返`() {
        val t = PendingTask(
            taskId = "t-1", ruleId = "r-1", fireAt = 123L, source = "消息·shell",
            createdAt = 100L, fromCall = true, status = TaskStatus.FAILED,
        )
        val parsed = PendingTaskJson.listFromJson(PendingTaskJson.listToJson(listOf(t)))
        assertEquals(listOf(t), parsed)
        // 损坏输入返回空列表而不是抛异常
        assertTrue(PendingTaskJson.listFromJson("not json").isEmpty())
    }
}
