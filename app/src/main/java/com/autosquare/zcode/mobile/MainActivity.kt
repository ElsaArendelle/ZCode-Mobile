package com.autosquare.zcode.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autosquare.zcode.mobile.data.BindingStore
import com.autosquare.zcode.mobile.notify.Notifier
import com.autosquare.zcode.mobile.ui.ScanScreen
import com.autosquare.zcode.mobile.ui.WebScreen

class MainActivity : ComponentActivity() {

    private val rescanFlag = mutableStateOf(false)
    private val boundUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不用沉浸式：正常保留系统状态栏，页面内容从状态栏下方开始（避免顶进状态栏重叠）
        Notifier.ensureChannels(this)
        boundUrl.value = BindingStore.boundUrl(this)
        handleIntent(intent)
        setContent { AppRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Notifier.setForeground(true)
        // 回到前台：进程已在前台，不需要保活服务 → 撤掉常驻通知
        runCatching { stopService(Intent(this, com.autosquare.zcode.mobile.service.ConnectionService::class.java)) }
    }

    override fun onPause() {
        Notifier.setForeground(false)
        // 退到后台：为保住 WebSocket 连接（锁屏/切走后仍能收到任务提醒）才启动前台服务
        if (BindingStore.keepaliveEnabled(this) && BindingStore.boundUrl(this) != null) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, com.autosquare.zcode.mobile.service.ConnectionService::class.java),
                )
            }
        }
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifier.EXTRA_RESCAN, false) == true) {
            rescanFlag.value = true
        }
    }

    @Composable
    private fun AppRoot() {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        ) {
            val url = boundUrl.value
            val rescan = rescanFlag.value
            when {
                // 菜单/通知触发重扫：已有绑定时允许取消返回
                rescan && url != null -> ScanScreen(
                    onBound = { u ->
                        boundUrl.value = u
                        rescanFlag.value = false
                    },
                    onCancel = { rescanFlag.value = false },
                )
                // 未绑定：强制扫码
                rescan || url == null -> ScanScreen(
                    onBound = { u ->
                        boundUrl.value = u
                        rescanFlag.value = false
                    },
                )
                else -> WebScreen(url = url, onNeedRescan = { rescanFlag.value = true })
            }
        }
    }
}
