package com.nightguard.app

import android.app.Notification
import android.content.Context
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.nightguard.app.logic.RuleEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MessagingStyle 文本提取回归：EXTRA_MESSAGES 是 Bundle[]，必须走
 * getMessagesFromBundleArray 解包。用 Robolectric 走真实框架行为
 * （Notification+MessagingStyle 构建 → extras → extractText 全链路）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTextExtractionTest {

    private fun sbnOf(context: Context, builder: Notification.Builder): StatusBarNotification =
        StatusBarNotification(
            "com.example.chat", "com.example.chat", 1, "tag", 10042, 10042, 0,
            builder.build(), UserHandle.getUserHandleForUid(0), System.currentTimeMillis()
        )

    @Test
    fun `标准 MessagingStyle 的发送者与正文可被提取`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sender = android.app.Person.Builder().setName("李四").build()
        val style = Notification.MessagingStyle(sender)
            .addMessage("独特正文XYZ", System.currentTimeMillis(), sender)
        val sbn = sbnOf(
            context,
            Notification.Builder(context, "ch")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("会话标题")
                .setStyle(style)
        )
        val text = RuleEngine.extractText(sbn)
        assertTrue("应含会话消息行，实际=[$text]", text.contains("李四:独特正文XYZ"))
        // 注意：MessagingStyle 构建时框架会用会话信息改写 EXTRA_TITLE，标题不可断言原值
    }

    @Test
    fun `多条会话消息逐条提取`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = android.app.Person.Builder().setName("甲").build()
        val b = android.app.Person.Builder().setName("乙").build()
        val style = Notification.MessagingStyle(a)
            .addMessage("第一条", 1L, a)
            .addMessage("第二条", 2L, b)
        val sbn = sbnOf(
            context,
            Notification.Builder(context, "ch")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(style)
        )
        val text = RuleEngine.extractText(sbn)
        assertTrue(text.contains("甲:第一条"))
        assertTrue(text.contains("乙:第二条"))
    }

    @Test
    fun `大文本 EXTRA_BIG_TEXT 参与匹配`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sbn = sbnOf(
            context,
            Notification.Builder(context, "ch")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("标题")
                .setContentText("被折叠的短文本")
                .setStyle(Notification.BigTextStyle().bigText("展开后的独特长文本BIGBODY"))
        )
        val text = RuleEngine.extractText(sbn)
        assertTrue(text.contains("独特长文本BIGBODY"))
        assertTrue(text.contains("被折叠的短文本"))
    }

    @Test
    fun `普通文本通知不含会话消息段`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sbn = sbnOf(
            context,
            Notification.Builder(context, "ch")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("标题T")
                .setContentText("正文B")
        )
        val text = RuleEngine.extractText(sbn)
        assertTrue(text.contains("标题T"))
        assertTrue(text.contains("正文B"))
        assertFalse(text.contains(":"))
    }
}
