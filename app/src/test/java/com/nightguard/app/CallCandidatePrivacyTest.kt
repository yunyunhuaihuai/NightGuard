package com.nightguard.app

import android.app.Notification
import android.content.Context
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.nightguard.app.data.Store
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 隐私回归：来电候选通知只把脱敏元数据写进持久化日志——
 * 合成通知中的独特正文与完整号码不得出现在任何日志条目里。
 * （独立测试类：Robolectric 同类共享静态 DataStore 单例，隔离按类进行。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallCandidatePrivacyTest {

    @Test
    fun `来电候选日志不落原文与完整号码`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = "独特来电正文TOKEN7788"
        val fullNumber = "13800138000"
        val notification = Notification.Builder(context, "ch")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("拨号器")
            .setContentText("张三 $fullNumber $token")
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        val sbn = StatusBarNotification(
            "com.android.dialer", "com.android.dialer", 2, null, 1000, 1000, 0,
            notification, UserHandle.getUserHandleForUid(0), System.currentTimeMillis()
        )

        RuleEngine.onCallNotification(context, sbn)

        val logs = Store.log(context)
        assertTrue("应记录一条来电候选元数据", logs.any { it.source.startsWith("来电候选") })
        val dumped = logs.joinToString("|") { "${it.rule}|${it.source}|${it.actions}" }
        assertFalse("独特正文不得落盘", dumped.contains(token))
        assertFalse("完整号码不得落盘", dumped.contains(fullNumber))
    }
}
