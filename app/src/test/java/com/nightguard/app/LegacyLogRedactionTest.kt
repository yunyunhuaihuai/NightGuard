package com.nightguard.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.Store
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 历史日志脱敏回归：旧版本持久化过的“来电候选”原文被一次性清除，
 * 与该缺陷无关的记录原样保留，且重复执行幂等。
 *
 * 说明：Robolectric 对相同 @Config 的测试类复用沙箱，Store 的静态 DataStore 单例
 * 会跨测试类共享，因此本类只按自己写入的时间戳断言，不假设日志总量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyLogRedactionTest {

    @Test
    fun `历史来电候选原文被脱敏且无关记录保留`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mine = mapOf(
            1000L to LogEntry(1000L, "", "来电候选·dialer", "张三 13800138000 旧版本落盘的原文"),
            2000L to LogEntry(2000L, "起床", "消息·微信", "2分钟后响铃"),
            3000L to LogEntry(3000L, "", "来电候选·dialer", RuleEngine.callCandidateMeta("任意")),
        )
        mine.values.forEach { Store.addLog(context, it) }

        suspend fun myEntries(): Map<Long, LogEntry> =
            Store.log(context).associateBy { it.time }.filterKeys { it in mine }

        Store.redactLegacyCallCandidateLogs(context)

        val after = myEntries()
        assertEquals("本测试写入的三条都还在", mine.keys, after.keys)
        assertEquals("新格式元数据条目不受影响", RuleEngine.callCandidateMeta("任意"), after[3000L]!!.actions)
        assertEquals("无关记录原样保留", "起床", after[2000L]!!.rule)
        assertEquals("消息·微信", after[2000L]!!.source)
        assertFalse("旧原文已被清除", after[1000L]!!.actions.contains("13800138000"))
        assertTrue(after[1000L]!!.actions.startsWith("（旧版本"))

        // 幂等：再跑一遍不产生新的变化
        Store.redactLegacyCallCandidateLogs(context)
        val again = myEntries()
        assertEquals(mine.keys, again.keys)
        assertEquals(after[1000L]!!.actions, again[1000L]!!.actions)
    }
}
