package com.nightguard.app

import com.nightguard.app.data.PendingTask
import com.nightguard.app.data.PendingTaskBacking
import com.nightguard.app.data.PendingTaskCore
import com.nightguard.app.data.Rule
import com.nightguard.app.data.TaskCtx
import com.nightguard.app.data.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待响任务状态机回归：并发装载去重、到期广播验真（旧广播不能触发新任务、
 * 清除新任务或复活已取消任务）、调度失败标记、恢复幂等与过期策略。
 */
class PendingTaskCoreTest {

    private class FakeBacking : PendingTaskBacking {
        val store = mutableListOf<PendingTask>()
        override suspend fun read(ctx: TaskCtx): List<PendingTask> = store.toList()
        override suspend fun write(ctx: TaskCtx, tasks: List<PendingTask>) {
            store.clear()
            store.addAll(tasks)
        }
    }

    private class Harness(startNow: Long = 1_000_000L) {
        var now: Long = startNow
        val backing = FakeBacking()
        private var idSeq = 0
        val core = PendingTaskCore(backing, now = { now }, newId = { "id-${idSeq++}" })
        fun rule(id: String, enabled: Boolean = true, delayed: Boolean = true) =
            Rule(id = id, enabled = enabled, delayedAlarm = delayed)
    }

    // ---------- 装载与去重 ----------

    @Test
    fun `同一规则并发装载只产生一个有效任务`() = runBlocking {
        val h = Harness()
        val fireAt = h.now + 120_000L
        val jobs = (1..8).map {
            launch(Dispatchers.IO) { h.core.arm(Unit, "r1", fireAt, "源", false) }
        }
        jobs.joinAll()
        val armed = h.backing.read(Unit).filter { it.status == TaskStatus.ARMED }
        assertEquals("并发装载后只应有一个 armed 任务", 1, armed.size)
        assertEquals(fireAt, armed.single().fireAt)
    }

    @Test
    fun `去重跳过不覆盖已有任务`() = runTest {
        val h = Harness()
        val firstAt = h.now + 120_000L
        val first = h.core.arm(Unit, "r1", firstAt, "源A", false)
        assertTrue(first is PendingTaskCore.ArmDecision.Write)
        h.now += 60_000L
        val second = h.core.arm(Unit, "r1", h.now + 180_000L, "源B", false)
        assertTrue(second is PendingTaskCore.ArmDecision.Deduped)
        assertEquals(firstAt, (second as PendingTaskCore.ArmDecision.Deduped).existing.fireAt)
        assertEquals(1, h.backing.read(Unit).size)
        assertEquals("源A", h.backing.read(Unit).single().source)
    }

    @Test
    fun `旧任务已到期则装载新任务替换`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now + 120_000L, "源A", false)
        h.now += 121_000L
        val second = h.core.arm(Unit, "r1", h.now + 60_000L, "源B", false)
        assertTrue(second is PendingTaskCore.ArmDecision.Write)
        assertEquals(1, h.backing.read(Unit).count { it.status == TaskStatus.ARMED })
        assertEquals("源B", h.backing.read(Unit).single { it.status == TaskStatus.ARMED }.source)
    }

    // ---------- 到期广播验真 ----------

    @Test
    fun `到期广播 taskId 不匹配则忽略且任务保留`() = runTest {
        val h = Harness()
        val decision = h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
        val task = (decision as PendingTaskCore.ArmDecision.Write).task
        assertNull(h.core.consumeForFire(Unit, "r1", "wrong-id"))
        assertEquals(task.taskId, h.backing.read(Unit).single().taskId)
    }

    @Test
    fun `到期广播 taskId 匹配才放行且只生效一次`() = runTest {
        val h = Harness()
        val decision = h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
        val task = (decision as PendingTaskCore.ArmDecision.Write).task
        val fired = h.core.consumeForFire(Unit, "r1", task.taskId)
        assertNotNull(fired)
        assertEquals(task.taskId, fired!!.taskId)
        assertNull("同一广播不能触发两次", h.core.consumeForFire(Unit, "r1", task.taskId))
        assertTrue(h.backing.read(Unit).none { it.status == TaskStatus.ARMED })
    }

    @Test
    fun `旧广播不能触发也不能清除新任务`() = runTest {
        val h = Harness()
        val first = (h.core.arm(Unit, "r1", h.now + 60_000L, "旧", false)
                as PendingTaskCore.ArmDecision.Write).task
        h.core.cancel(Unit, "r1", first.taskId)
        val second = (h.core.arm(Unit, "r1", h.now + 120_000L, "新", false)
                as PendingTaskCore.ArmDecision.Write).task
        // 旧任务的到期广播晚到：不得触发新任务、不得清除新任务
        assertNull(h.core.consumeForFire(Unit, "r1", first.taskId))
        val still = h.backing.read(Unit).single { it.status == TaskStatus.ARMED }
        assertEquals(second.taskId, still.taskId)
        // 新任务的广播正常生效
        assertNotNull(h.core.consumeForFire(Unit, "r1", second.taskId))
    }

    @Test
    fun `已取消任务不能被过期广播复活`() = runTest {
        val h = Harness()
        val task = (h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
                as PendingTaskCore.ArmDecision.Write).task
        h.core.cancel(Unit, "r1", task.taskId)
        assertNull(h.core.consumeForFire(Unit, "r1", task.taskId))
    }

    @Test
    fun `无 taskId 的旧格式广播只接受迁移的 legacy 任务`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now + 60_000L, "新", false)
        // 新任务不接受无 taskId 广播
        assertNull(h.core.consumeForFire(Unit, "r1", null))
        // 换成迁移出来的 legacy 任务后放行
        h.backing.write(
            Unit,
            listOf(
                PendingTask(
                    taskId = PendingTask.LEGACY_PREFIX + "r1", ruleId = "r1",
                    fireAt = h.now + 60_000L, source = "旧版本迁移", createdAt = h.now,
                )
            )
        )
        val fired = h.core.consumeForFire(Unit, "r1", null)
        assertEquals(PendingTask.LEGACY_PREFIX + "r1", fired?.taskId)
    }

    // ---------- 取消 ----------

    @Test
    fun `取消带错误 taskId 时被忽略`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
        val decision = h.core.cancel(Unit, "r1", "stale-task-id")
        assertTrue(decision is PendingTaskCore.CancelDecision.StaleRequest)
        assertEquals(1, h.backing.read(Unit).count { it.status == TaskStatus.ARMED })
    }

    @Test
    fun `规则级取消不需要 taskId`() = runTest {
        val h = Harness()
        val task = (h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
                as PendingTaskCore.ArmDecision.Write).task
        val decision = h.core.cancel(Unit, "r1", null)
        assertTrue(decision is PendingTaskCore.CancelDecision.Cancelled)
        assertEquals(task.taskId, (decision as PendingTaskCore.CancelDecision.Cancelled).task.taskId)
    }

    // ---------- 调度失败 ----------

    @Test
    fun `markFailed 后不可触发且不参与去重`() = runTest {
        val h = Harness()
        val task = (h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
                as PendingTaskCore.ArmDecision.Write).task
        assertTrue(h.core.markFailed(Unit, "r1", task.taskId))
        assertNull("failed 任务不能再被到期广播触发", h.core.consumeForFire(Unit, "r1", task.taskId))
        // 失败不阻塞重新装载
        val retry = h.core.arm(Unit, "r1", h.now + 120_000L, "重试", false)
        assertTrue(retry is PendingTaskCore.ArmDecision.Write)
    }

    // ---------- 恢复 ----------

    @Test
    fun `恢复 未来任务重排且幂等`() = runTest {
        val h = Harness()
        val fireAt = h.now + 120_000L
        h.core.arm(Unit, "r1", fireAt, "源", false)
        val plan = h.core.recover(Unit, listOf(h.rule("r1")))
        assertEquals(listOf(fireAt), plan.rearm.map { it.fireAt })
        assertTrue(plan.catchUp.isEmpty() && plan.expired.isEmpty() && plan.dropped.isEmpty())
        val plan2 = h.core.recover(Unit, listOf(h.rule("r1")))
        assertEquals(listOf(fireAt), plan2.rearm.map { it.fireAt })
        assertTrue(plan2.catchUp.isEmpty() && plan2.expired.isEmpty() && plan2.dropped.isEmpty())
        assertEquals(1, h.backing.read(Unit).count { it.status == TaskStatus.ARMED })
    }

    @Test
    fun `恢复 宽限期内到期补响`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now - 30_000L, "源", false)
        val plan = h.core.recover(Unit, listOf(h.rule("r1")))
        assertEquals(1, plan.catchUp.size)
        assertEquals(h.now + 15_000L, plan.catchUp.single().fireAt)
    }

    @Test
    fun `恢复 超过宽限期的过期任务明确跳过`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now - 10 * 60_000L, "源", false)
        val plan = h.core.recover(Unit, listOf(h.rule("r1")))
        assertEquals(1, plan.expired.size)
        assertTrue(plan.rearm.isEmpty() && plan.catchUp.isEmpty())
        // 记录转 expired 保留诊断，再次恢复不再出现
        assertEquals(TaskStatus.EXPIRED, h.backing.read(Unit).single().status)
        val plan2 = h.core.recover(Unit, listOf(h.rule("r1")))
        assertTrue(plan2.expired.isEmpty())
    }

    @Test
    fun `恢复 已禁用或已删除规则的任务不恢复`() = runTest {
        val h = Harness()
        h.core.arm(Unit, "r1", h.now + 60_000L, "源", false)
        h.core.arm(Unit, "r2", h.now + 60_000L, "源", false)
        h.core.arm(Unit, "r3", h.now + 60_000L, "源", false)
        val plan = h.core.recover(
            Unit,
            listOf(
                h.rule("r1", enabled = false),
                // r2 已删除（不在列表）
                h.rule("r3", delayed = false),
            )
        )
        assertEquals(setOf("r1", "r2", "r3"), plan.dropped.map { it.first.ruleId }.toSet())
        assertTrue(plan.rearm.isEmpty())
        assertTrue(h.backing.read(Unit).none { it.status == TaskStatus.ARMED })
    }
}
