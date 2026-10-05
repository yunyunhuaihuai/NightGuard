package com.nightguard.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nightguard.app.data.Rule
import com.nightguard.app.data.Store
import com.nightguard.app.logic.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DAY_LABELS = listOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

fun fmtTime(mins: Int): String = "%02d:%02d".format(mins / 60, mins % 60)

private fun toggleIn(set: Set<String>, v: String): Set<String> =
    if (set.contains(v)) set - v else set + v

@Composable
fun RulesScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var editing by remember { mutableStateOf<Rule?>(null) }

    LaunchedEffect(Unit) {
        rules = withContext(Dispatchers.IO) { Store.rulesSync(ctx) }
    }

    if (editing != null) {
        val current = editing!!
        RuleEditor(
            initial = current,
            onClose = { saved ->
                editing = null
                if (saved != null) {
                    scope.launch(Dispatchers.IO) {
                        val updated = Store.rulesSync(ctx).filter { it.id != saved.id } + saved
                        Store.saveRules(ctx, updated)
                        rules = Store.rulesSync(ctx)
                    }
                }
            },
            onDelete = {
                val id = current.id
                editing = null
                scope.launch(Dispatchers.IO) {
                    // 统一清理：待响任务（闹钟/提醒/接听监听）、抑制点、冷却记录
                    com.nightguard.app.logic.ActionExecutor.cleanupRuleState(ctx, id)
                    val updated = Store.rulesSync(ctx).filter { it.id != id }
                    Store.saveRules(ctx, updated)
                    rules = Store.rulesSync(ctx)
                }
            }
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("规则", fontSize = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
            if (rules.isEmpty()) {
                Text(
                    "还没有规则。\n右下角“+”新建：设定监听时段，选择要盯的应用或联系人，再选择触发后执行的动作。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { enabled ->
                            scope.launch(Dispatchers.IO) {
                                val updated = Store.rulesSync(ctx).map {
                                    if (it.id == rule.id) it.copy(enabled = enabled) else it
                                }
                                Store.saveRules(ctx, updated)
                                if (!enabled) {
                                    // 禁用即取消其待响任务，防止“禁用再启用后旧任务还会响”
                                    com.nightguard.app.logic.ActionExecutor.cleanupRuleState(ctx, rule.id)
                                }
                                rules = Store.rulesSync(ctx)
                            }
                        },
                        onEdit = { editing = rule }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = Rule() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Text("+", fontSize = 24.sp)
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.name.ifBlank { "未命名" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text("时段 ${rule.describeWindow()} · ${rule.describeDays()}", fontSize = 13.sp)
            Text(rule.describeSources(), fontSize = 13.sp)
            Text("动作：${rule.describeActions()}", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(initial: Rule, onClose: (Rule?) -> Unit, onDelete: () -> Unit) {
    var r by remember { mutableStateOf(initial) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    val contactGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    val contactPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showContacts = true
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onClose(null) }) { Text("取消") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("删除") }
            Button(onClick = { onClose(r) }, enabled = r.isConfigured()) { Text("保存") }
        }
        if (!r.isConfigured()) {
            Text(
                "请至少选择一个要监听的应用或联系人",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        Section("名称与启用")
        OutlinedTextField(
            value = r.name,
            onValueChange = { r = r.copy(name = it) },
            label = { Text("规则名称") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = r.enabled, onCheckedChange = { r = r.copy(enabled = it) })
            Text("规则启用", modifier = Modifier.padding(start = 10.dp))
        }

        Section("监听时段")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showStart = true }) { Text(fmtTime(r.startMinutes)) }
            Text("  至  ")
            OutlinedButton(onClick = { showEnd = true }) { Text(fmtTime(r.endMinutes)) }
            if (r.startMinutes == r.endMinutes) Text("（全天）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DAY_LABELS.forEach { (d, label) ->
                FilterChip(
                    selected = r.days.contains(d),
                    onClick = {
                        val cur = r.days.map { it }.toMutableSet()
                        if (cur.contains(d)) cur.remove(d) else cur.add(d)
                        r = r.copy(days = cur)
                    },
                    label = { Text(label, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 快捷选择 + 选中摘要：让“到底选没选上”一眼可见
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("每天" to setOf(1, 2, 3, 4, 5, 6, 7), "工作日" to setOf(1, 2, 3, 4, 5), "周末" to setOf(6, 7))
                .forEach { (label, set) ->
                    TextButton(
                        onClick = { r = r.copy(days = set) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text(label, fontSize = 13.sp) }
                }
            Text(
                "已选：" + r.describeDays(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Section("触发来源（命中其一即触发）")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = r.watchMessages, onCheckedChange = { r = r.copy(watchMessages = it) })
            Text("指定应用的消息", modifier = Modifier.padding(start = 10.dp))
        }
        OutlinedButton(onClick = { showApps = true }, enabled = r.watchMessages) {
            Text(
                when {
                    r.packages.isEmpty() -> "选择要监听的应用"
                    else -> "已选 ${r.packages.size} 个应用：" +
                            r.packages.joinToString("、") { it.substringAfterLast('.') }
                }
            )
        }
        OutlinedTextField(
            value = r.senderKeyword,
            onValueChange = { r = r.copy(senderKeyword = it) },
            label = { Text("消息关键词（可选，留空=任意消息）") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Switch(checked = r.watchCalls, onCheckedChange = { r = r.copy(watchCalls = it) })
            Text("指定联系人的来电", modifier = Modifier.padding(start = 10.dp))
        }
        OutlinedButton(
            onClick = {
                if (contactGranted) showContacts = true
                else contactPermLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
            enabled = r.watchCalls
        ) {
            Text(
                when {
                    !contactGranted -> "授权通讯录后选择联系人"
                    r.contacts.isEmpty() -> "选择联系人（来电通知按姓名/号码匹配）"
                    else -> "已选 ${r.contacts.size} 个联系人"
                }
            )
        }

        Section("动作：延迟响铃")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = r.delayedAlarm, onCheckedChange = { r = r.copy(delayedAlarm = it) })
            Text("触发后定时响铃（走闹钟流，勿扰下也可响）", modifier = Modifier.padding(start = 10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1, 2, 3, 5, 10).forEach { m ->
                FilterChip(
                    selected = r.alarmDelayMinutes == m,
                    onClick = { r = r.copy(alarmDelayMinutes = m) },
                    label = { Text("${m}分") },
                    enabled = r.delayedAlarm
                )
            }
        }

        Section("去重冷却（同一规则两次触发的最小间隔）")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(60, 120, 300, 600).forEach { s ->
                FilterChip(
                    selected = r.cooldownSeconds == s,
                    onClick = { r = r.copy(cooldownSeconds = s) },
                    label = { Text("${s / 60}分") }
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showStart) {
        TimeDialog("开始时间", r.startMinutes, onConfirm = { m -> r = r.copy(startMinutes = m); showStart = false },
            onDismiss = { showStart = false })
    }
    if (showEnd) {
        TimeDialog("结束时间", r.endMinutes, onConfirm = { m -> r = r.copy(endMinutes = m); showEnd = false },
            onDismiss = { showEnd = false })
    }
    if (showApps) {
        AppPickerDialog(
            initial = r.packages,
            onDone = { sel -> r = r.copy(packages = sel); showApps = false },
            onDismiss = { showApps = false }
        )
    }
    if (showContacts) {
        ContactPickerDialog(
            initial = r.contacts,
            initialNames = r.contactNames,
            onDone = { sel, names -> r = r.copy(contacts = sel, contactNames = names); showContacts = false },
            onDismiss = { showContacts = false }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AppPickerDialog(
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sel by remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要监听的应用") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(apps, key = { it.first }) { (pkg, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sel = toggleIn(sel, pkg) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = sel.contains(pkg), onCheckedChange = { sel = toggleIn(sel, pkg) })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(sel) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ContactPickerDialog(
    initial: Set<String>,
    initialNames: Map<String, String>,
    onDone: (Set<String>, Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var contacts by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var sel by remember { mutableStateOf(initial) }
    var names by remember { mutableStateOf(initialNames) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { loadContacts(ctx) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要监听的联系人") },
        text = {
            if (contacts.isEmpty()) {
                Text("未读取到联系人（需通讯录权限）")
            } else {
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    items(contacts, key = { it.first }) { (norm, name, display) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sel = toggleIn(sel, norm)
                                    names = if (sel.contains(norm)) names + (norm to name) else names - norm
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = sel.contains(norm), onCheckedChange = {
                                sel = toggleIn(sel, norm)
                                names = if (sel.contains(norm)) names + (norm to name) else names - norm
                            })
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(name)
                                Text(display, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(sel, names.filterKeys { sel.contains(it) }) }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------- 数据加载 ----------

private fun loadLaunchableApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).asSequence()
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == ctx.packageName || pkg == "android") return@mapNotNull null
            val label = try {
                ri.loadLabel(pm)?.toString() ?: pkg
            } catch (e: Exception) {
                pkg
            }
            pkg to label
        }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
        .toList()
}

private fun loadContacts(ctx: Context): List<Triple<String, String, String>> {
    val out = mutableListOf<Triple<String, String, String>>()
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val norm = c.getString(2) ?: RuleEngine.normalizeNumber(num)
                if (RuleEngine.normalizeNumber(norm).length < 5) continue
                out.add(Triple(RuleEngine.normalizeNumber(norm), name, num))
            }
        }
    } catch (e: Exception) {
    }
    return out.distinctBy { it.first }
}
