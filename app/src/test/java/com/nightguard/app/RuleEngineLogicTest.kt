package com.nightguard.app

import com.nightguard.app.data.Rule
import com.nightguard.app.logic.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 规则引擎纯逻辑回归：时段窗口（跨午夜/周日到周一/全天/边界）、匹配、号码脱敏、
 * 通知文本组装。不依赖 Android 运行时。
 */
class RuleEngineLogicTest {

    /**
     * 基准：2024-01-01 是周一（isoDay=1）。用默认时区——被测代码内部
     * （nextWindowEnd/todayAt）即以默认时区构造 Calendar，测试必须一致。
     */
    private fun calAt(dayIso: Int, hour: Int, minute: Int): Calendar {
        val c = Calendar.getInstance()
        c.clear()
        c.set(2024, Calendar.JANUARY, 1, hour, minute, 0)
        c.add(Calendar.DAY_OF_MONTH, dayIso - 1)
        return c
    }

    private fun rule(
        start: Int = 22 * 60,
        end: Int = 7 * 60,
        days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    ) = Rule(startMinutes = start, endMinutes = end, days = days)

    // ---------- isoDay 换算 ----------

    @Test
    fun `isoDay 周一到周日`() {
        assertEquals(1, RuleEngine.isoDay(calAt(1, 12, 0)))
        assertEquals(7, RuleEngine.isoDay(calAt(7, 12, 0)))
    }

    // ---------- 时段窗口 ----------

    @Test
    fun `跨午夜规则 周内晚上与次日凌晨命中`() {
        val r = rule(days = setOf(1, 2, 3, 4, 5, 6, 7))
        assertTrue(RuleEngine.inWindow(r, calAt(1, 23, 0)))
        assertTrue(RuleEngine.inWindow(r, calAt(2, 6, 59)))
    }

    @Test
    fun `跨午夜规则 凌晨归属前一天星期`() {
        // 周日 22:00 起、跨到周一凌晨 07:00：周一 06:00 命中的是“周日”这条
        val sundayOnly = rule(days = setOf(7))
        assertTrue(RuleEngine.inWindow(sundayOnly, calAt(7, 23, 30))) // 周日 23:30
        assertTrue(RuleEngine.inWindow(sundayOnly, calAt(1, 6, 0)))   // 周一 06:00（前一天是周日）
        assertFalse(RuleEngine.inWindow(sundayOnly, calAt(1, 8, 0)))  // 周一白天不属于周日窗口

        // 只有周一：周一 23:30 命中，周一凌晨 02:00（前一天周日）不命中
        val mondayOnly = rule(days = setOf(1))
        assertTrue(RuleEngine.inWindow(mondayOnly, calAt(1, 23, 30)))
        assertFalse(RuleEngine.inWindow(mondayOnly, calAt(1, 2, 0)))
    }

    @Test
    fun `非跨午夜规则 结束分钟不命中`() {
        val r = rule(start = 8 * 60, end = 22 * 60)
        assertFalse(RuleEngine.inWindow(r, calAt(1, 7, 59)))
        assertTrue(RuleEngine.inWindow(r, calAt(1, 8, 0)))
        assertTrue(RuleEngine.inWindow(r, calAt(1, 21, 59)))
        assertFalse(RuleEngine.inWindow(r, calAt(1, 22, 0)))
    }

    @Test
    fun `start 等于 end 视为全天`() {
        val r = rule(start = 0, end = 0, days = setOf(1))
        assertTrue(RuleEngine.inWindow(r, calAt(1, 3, 0)))
        assertFalse(RuleEngine.inWindow(r, calAt(2, 3, 0)))
    }

    @Test
    fun `空星期视为每天`() {
        val r = rule(start = 0, end = 0, days = emptySet())
        assertTrue(RuleEngine.inWindow(r, calAt(3, 12, 0)))
        assertTrue(RuleEngine.inWindow(r, calAt(7, 23, 0)))
    }

    // ---------- 窗口结束时刻（抑制边界用） ----------

    @Test
    fun `nextWindowEnd 非跨午夜当日结束`() {
        val r = rule(start = 8 * 60, end = 22 * 60)
        assertEquals(calAt(1, 22, 0).timeInMillis, RuleEngine.nextWindowEnd(r, calAt(1, 9, 30).timeInMillis))
    }

    @Test
    fun `nextWindowEnd 跨午夜结束点在次日`() {
        val r = rule()
        assertEquals(
            calAt(2, 7, 0).timeInMillis,
            RuleEngine.nextWindowEnd(r, calAt(1, 23, 0).timeInMillis)
        )
        assertEquals(
            calAt(1, 7, 0).timeInMillis,
            RuleEngine.nextWindowEnd(r, calAt(1, 2, 0).timeInMillis)
        )
    }

    @Test
    fun `nextWindowEnd 全天为当前时刻加 24 小时`() {
        val r = rule(start = 0, end = 0)
        val now = calAt(1, 15, 0).timeInMillis
        assertEquals(now + 24 * 3600_000L, RuleEngine.nextWindowEnd(r, now))
    }

    @Test
    fun `nextWindowEnd 窗口外返回 null`() {
        val r = rule(start = 8 * 60, end = 22 * 60)
        assertEquals(null, RuleEngine.nextWindowEnd(r, calAt(1, 23, 0).timeInMillis))
    }

    // ---------- 匹配 ----------

    @Test
    fun `消息匹配 包名与关键词`() {
        val r = Rule(watchMessages = true, packages = setOf("com.tencent.mm"), senderKeyword = "加班")
        assertTrue(RuleEngine.matchesMessage(r, "com.tencent.mm", "今晚加班到 10 点"))
        assertFalse(RuleEngine.matchesMessage(r, "com.qq.app", "加班"))
        assertFalse(RuleEngine.matchesMessage(r, "com.tencent.mm", "今晚聚餐"))
        // 关键词大小写不敏感
        val kw = Rule(watchMessages = true, packages = setOf("a.b"), senderKeyword = "ServerDown")
        assertTrue(RuleEngine.matchesMessage(kw, "a.b", "alert: serverdown!"))
    }

    @Test
    fun `消息匹配 空关键词等于任意消息`() {
        val r = Rule(watchMessages = true, packages = setOf("a.b"), senderKeyword = "")
        assertTrue(RuleEngine.matchesMessage(r, "a.b", "任何内容"))
    }

    @Test
    fun `来电匹配 姓名与号码`() {
        val r = Rule(
            watchCalls = true,
            contacts = setOf("13800138000", "10086"),
            contactNames = mapOf("13800138000" to "张三"),
        )
        assertTrue(RuleEngine.matchesCallNotification(r, "张三 手机来电"))
        assertTrue(RuleEngine.matchesCallNotification(r, "+86 138-0013-8000 来电"))
        assertFalse(RuleEngine.matchesCallNotification(r, "10086 客服来电")) // <7 位号码不按号码匹配
        assertFalse(RuleEngine.matchesCallNotification(r, "李四 来电"))
        assertFalse(RuleEngine.matchesCallNotification(r.copy(watchCalls = false), "张三 来电"))
    }

    // ---------- 号码工具 ----------

    @Test
    fun `normalizeNumber 只保留数字`() {
        // “+86”前缀的 86 也是数字，会被保留——规则里的号码存的是用户归一化后的值
        assertEquals("8613800138000", RuleEngine.normalizeNumber("+86 138-0013-8000"))
        assertEquals("13800138000", RuleEngine.normalizeNumber("138-0013-8000"))
    }

    @Test
    fun `maskNumber 只留末四位`() {
        assertEquals("****8000", RuleEngine.maskNumber("+86 138-0013-8000"))
        assertEquals("****", RuleEngine.maskNumber("1234"))
    }

    // ---------- 通知文本组装 ----------

    @Test
    fun `composeExtractedText 标题正文大文本与会话消息`() {
        val out = RuleEngine.composeExtractedText(
            title = "群名", text = "compat", bigText = "大文本",
            messages = listOf("李四" to "你好"),
        )
        assertEquals("群名\ncompat\n大文本\n李四:你好", out)
    }

    @Test
    fun `composeExtractedText 过滤空白并跳过空消息`() {
        val out = RuleEngine.composeExtractedText(
            title = "", text = null, bigText = "  ",
            messages = listOf(null to null, null to "只有正文", "" to ""),
        )
        assertEquals(":只有正文", out)
    }

    @Test
    fun `composeExtractedText 全空输入`() {
        assertEquals("", RuleEngine.composeExtractedText(null, null, null, emptyList()))
    }

    @Test
    fun `callCandidateMeta 只含长度不含原文`() {
        val meta = RuleEngine.callCandidateMeta("张三 13800138000 请回电")
        assertEquals("通知文本 18 字（隐私不记录原文）", meta)
        assertFalse(meta.contains("13800138000"))
    }
}
