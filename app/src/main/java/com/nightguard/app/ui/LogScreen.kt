package com.nightguard.app.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightguard.app.data.LogEntry
import com.nightguard.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_TIME_FMT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

@Composable
fun LogScreen(tick: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(tick) {
        entries = withContext(Dispatchers.IO) { Store.logSync(ctx) }
    }
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
        LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(
                                LOG_TIME_FMT.format(Date(e.time)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
