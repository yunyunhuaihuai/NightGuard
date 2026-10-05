package com.nightguard.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.launch

private data class PermRow(
    val title: String,
    val desc: String,
    val granted: Boolean,
    val optional: Boolean,
    val fixLabel: String,
    /** 覆盖默认 ✓/✗ 的状态文案（如“已授权未连接/状态未知”） */
    val statusLabel: String? = null,
    val fix: () -> Unit,
)

/** 通知使用权的四态：按当前进程的监听连接状态区分，历史连接时间仅用于诊断 */
private enum class ListenerStatus(val label: String, val ok: Boolean) {
    UNAUTHORIZED("✗ 未授权", false),
    DISCONNECTED("✗ 已授权未连接", false),
    CONNECTED("✓ 已连接", true),
    UNKNOWN("？ 状态未知", false),
}

@Composable
fun PermissionsScreen(tick: Int) {
    val ctx = LocalContextCurrent()
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val alm = ctx.getSystemService(android.app.AlarmManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val scope = rememberCoroutineScope()

    val notifPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    val authorized = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val listenerStatus = when {
        !authorized -> ListenerStatus.UNAUTHORIZED
        com.nightguard.app.service.NightNotificationListener.boundInProcess -> ListenerStatus.CONNECTED
        com.nightguard.app.service.NightNotificationListener.serviceCreatedInProcess -> ListenerStatus.DISCONNECTED
        else -> ListenerStatus.UNKNOWN
    }
    // 历史连接时间：仅诊断用
    var lastConn by remember { mutableStateOf(0L) }
    var lastDisc by remember { mutableStateOf(0L) }
    LaunchedEffect(tick) {
        lastConn = Store.lastConnectedAt(ctx)
        lastDisc = Store.lastDisconnectedAt(ctx)
    }
    val listenerDesc = buildString {
        append("监听指定应用的消息与系统来电通知（核心，必开）")
        if (lastConn > 0L) {
            append("；上次连接 ").append(fmtStamp(lastConn))
        }
        if (lastDisc > 0L) {
            append("，上次断开 ").append(fmtStamp(lastDisc))
        }
        if (lastConn > 0L || lastDisc > 0L) append("（历史时间仅用于诊断）")
    }
    val exact = alm?.canScheduleExactAlarms() == true
    val batt = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    val fsi = nm?.canUseFullScreenIntent() == true
    val contacts = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    val phoneState = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val contactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun go(intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            // 个别 ROM 没有对应设置页时静默失败
        }
    }
    fun pkgIntent(action: String): Intent =
        Intent(action, Uri.parse("package:" + ctx.packageName))

    val rows = listOf(
        PermRow("通知使用权", listenerDesc, listenerStatus.ok, false, "去开启", listenerStatus.label) {
            go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        PermRow("通知权限", "展示响铃通知与状态提醒", notifPerm, false, "授权") {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        PermRow("精确闹钟", "准点响铃与状态恢复（USE_EXACT_ALARM 自动授予，一般无需操作）", exact, false, "去授权") {
            go(pkgIntent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        },
        PermRow("联系人", "把来电通知里的姓名/号码匹配到联系人（仅在本机匹配，不上传）", contacts, true, "授权") {
            contactLauncher.launch(Manifest.permission.READ_CONTACTS)
        },
        PermRow("电话状态", "来电接听后自动取消已设定的闹钟（可选，仅读取通话状态，不读通话记录）", phoneState, true, "授权") {
            phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        },
        PermRow("电池优化白名单", "防止 ColorOS 清理后台导致失灵（强烈建议开）", batt, false, "去设置") {
            go(pkgIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        },
        PermRow("全屏通知", "响铃时点亮屏幕（可选；拿不到时仍有声音+震动）", fsi, true, "去设置") {
            val i = pkgIntent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
            try {
                ctx.startActivity(i)
            } catch (e: Exception) {
                go(pkgIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("权限检查清单", fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
        rows.forEach { row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            row.statusLabel
                                ?: if (row.granted) "✓ 已就绪" else if (row.optional) "○ 可选" else "✗ 未授权",
                            color = if (row.granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        row.desc,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (!row.granted) {
                        Button(onClick = row.fix, modifier = Modifier.padding(top = 8.dp)) {
                            Text(row.fixLabel)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("来电检测说明", fontSize = 16.sp)
                Text(
                    "来电通过“通知使用权”读取系统拨号器的来电通知来识别（不接管系统来电筛选，" +
                            "不影响自带骚扰拦截）。命中规则联系人时通知文本需包含其姓名或号码；" +
                            "每次通话都会在“日志”页记录一条“来电候选”，但只记包名与文本长度等元数据，" +
                            "通知原文（可能含完整号码）不会进入本地日志。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("一加 / ColorOS 手动设置（重要）", fontSize = 16.sp)
                Text(
                    "1. 设置 → 应用 → 通知唤醒 → 电池 → 允许后台运行（不优化）\n" +
                            "2. 最近任务卡片下拉 → 锁定本应用（防一键清理）\n" +
                            "3. 设置 → 应用 → 自启动管理 → 允许通知唤醒\n" +
                            "4. 切勿使用“强行停止”：它会同时取消全部闹钟与监听，任何应用都无法自救；误操作后重新打开一次本应用即可恢复。\n" +
                            "5. 划掉最近任务 ≠ 强行停止，一般无碍；ColorOS 一键清理建议先锁定本应用。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("自检工具（真机测试用）", fontSize = 16.sp)
                Text(
                    "① 会创建一条全天生效的“自检”规则（监听 com.android.shell 的通知），并立即走完整链路：" +
                            "先出现一条静默通知（可点“本次不响/今天不再响”取消），不取消则 1 分钟后响铃约 1 分钟自动停止。" +
                            "测试时手机会出声，请注意。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                Button(onClick = {
                    scope.launch {
                        val selftest = Rule(
                            id = "selftest", name = "自检", enabled = true,
                            startMinutes = 0, endMinutes = 0,
                            days = setOf(1, 2, 3, 4, 5, 6, 7),
                            watchMessages = true, packages = setOf("com.android.shell"),
                            watchCalls = false,
                            delayedAlarm = true, alarmDelayMinutes = 1,
                            cooldownSeconds = 0,
                        )
                        Store.upsertRule(ctx, selftest)
                        RuleEngine.onEventTriggered(ctx, selftest, "自检·模拟消息")
                    }
                }) {
                    Text("① 创建自检规则并立即触发")
                }
                Text(
                    "② 真实通知走监听链路：电脑执行\n" +
                            "adb shell cmd notification post -t 自检测试 shelltag \"你好\"",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                )
                Row {
                    OutlinedButton(onClick = {
                        scope.launch {
                            // 统一清理：取消待响任务、抑制点、冷却记录，再删除自检规则
                            com.nightguard.app.logic.ActionExecutor.cleanupRuleState(ctx, "selftest")
                            Store.deleteRule(ctx, "selftest")
                        }
                    }) {
                        Text("④ 清除自检规则")
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LocalContextCurrent(): Context = androidx.compose.ui.platform.LocalContext.current

private fun fmtStamp(at: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
