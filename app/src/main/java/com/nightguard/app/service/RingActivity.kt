package com.nightguard.app.service

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 锁屏上弹出的响铃页：showWhenLocked + turnScreenOn（有全屏通知权限才亮屏，拿不到则只有声音/震动）。
 *
 * 与响铃会话绑定：全屏意图可能在服务置位会话前弹出，最多等 3 秒；
 * 会话结束后（自动超时 / 用户停止 / 播放失败 / 服务销毁）自动 finish，
 * 不再残留亮屏页面（FLAG_KEEP_SCREEN_ON 随页面销毁释放）。
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                RingScreen(onStop = {
                    stopService(Intent(this, AlarmRingService::class.java))
                    finish()
                })
            }
        }
        lifecycleScope.launch {
            val active = withTimeoutOrNull(3000L) {
                AlarmRingService.sessionActiveFlow.first { it }
            }
            if (active != true) {
                finish()
                return@launch
            }
            AlarmRingService.sessionActiveFlow.collect { ringActive ->
                if (!ringActive) finish()
            }
        }
    }
}

@Composable
private fun RingScreen(onStop: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⏰ 关键响铃", fontSize = 30.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "规则在监听时段内触发，延迟时间已到",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("停止响铃", fontSize = 18.sp)
            }
        }
    }
}
