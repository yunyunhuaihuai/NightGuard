package com.nightguard.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.nightguard.app.data.Rule
import com.nightguard.app.data.RuleJson
import com.nightguard.app.data.Store
import com.nightguard.app.logic.AlarmScheduler
import com.nightguard.app.logic.TaskRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 每次 onResume 自增，驱动权限/日志状态刷新 */
    private val refreshTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        AlarmScheduler.ensureChannels(this)
        // 心跳兜底：安装后/长期不重启也要保证每 24h 一次自检（同一 PendingIntent，重排即刷新）
        AlarmScheduler.scheduleHeartbeat(this)
        // ColorOS 可能在后台解绑通知监听器：每次打开应用都请求系统重绑（自愈）
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, com.nightguard.app.service.NightNotificationListener::class.java)
            )
        } catch (e: Exception) {
        }
        refreshTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            // 旧版本把“来电候选”通知原文（可含完整号码）写进了持久化日志：升级后一次性脱敏
            Store.redactLegacyCallCandidateLogs(this@MainActivity)
            // 应用启动后的幂等恢复：重排丢失的精确闹钟、跳过过期任务、重挂接听监听
            TaskRecovery.recover(this@MainActivity)
        }
        // 状态栏/导航栏透明，浅色图标，与应用深色主题无缝衔接（消除黑色割裂条）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NightGuardApp(tick = refreshTick.intValue)
            }
        }
    }
}

/** 规则编辑草稿的 Saveable 编解码：JSON 字符串落盘到实例状态，进程重建可恢复 */
private val RuleDraftSaver = Saver<Rule?, String>(
    save = { it?.let { r -> RuleJson.ruleToJson(r).toString() } ?: "" },
    restore = { if (it.isEmpty()) null else RuleJson.ruleFromJsonString(it) },
)

@Composable
fun NightGuardApp(tick: Int) {
    var tab by rememberSaveable { mutableIntStateOf(1) } // 启动页：规则
    // 编辑草稿挂在本层并 rememberSaveable：切页、旋转、进程重建都能恢复正在编辑的内容
    var editing by rememberSaveable(stateSaver = RuleDraftSaver) { mutableStateOf<Rule?>(null) }
    val tabs = listOf("权限", "规则", "日志")
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> PermissionsScreen(tick)
                1 -> RulesScreen(editing = editing, onEditChange = { editing = it })
                2 -> LogScreen()
            }
        }
    }
}
