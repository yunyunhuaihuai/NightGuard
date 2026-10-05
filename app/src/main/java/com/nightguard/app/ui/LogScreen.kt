package com.nightguard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightguard.app.data.LogKind
import com.nightguard.app.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@Composable
fun LogScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 日志走 Flow：触发/装载/响铃/停止等事件写入后自动刷新
    val entries by Store.logFlow(ctx).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("触发日志", fontSize = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "仅记录规则名/来源/动作等元数据，保存在本机，不记录消息内容",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entries.isEmpty()) {
            Text(
                "暂无记录",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                LOG_TIME_FMT.format(Date(e.time)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (e.kind.isNotBlank()) {
                                Text(
                                    "  ${LogKind.label(e.kind)}",
                                    fontSize = 11.sp,
                                    color = when (e.kind) {
                                        LogKind.ARM_FAILED, LogKind.RING_FAILED -> MaterialTheme.colorScheme.error
                                        LogKind.RING_START -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        Text(
                            (if (e.rule.isBlank()) "" else e.rule + "  ·  ") + e.source,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (e.actions.isNotBlank()) {
                            Text(e.actions, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
