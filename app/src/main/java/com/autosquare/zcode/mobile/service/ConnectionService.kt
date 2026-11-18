package com.autosquare.zcode.mobile.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.autosquare.zcode.mobile.data.BindingStore
import com.autosquare.zcode.mobile.notify.Notifier
import com.autosquare.zcode.mobile.notify.startForegroundCompat

/**
 * 前台服务：仅用于保住进程与 WebView 内的 WebSocket 连接（WS 在主进程的 WebView 里跑），
 * 服务本身不持有连接。specialUse 类型避开 dataSync 在 Android 15+ 的 6 小时上限。
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        if (!BindingStore.keepaliveEnabled(this) || BindingStore.boundUrl(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(this, Notifier.KEEPALIVE_NOTIFICATION_ID, Notifier.keepaliveNotification(this))
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉 App 时停止保活：否则前台服务撑着进程，
     * WebView 里页面的自动重连会在后台持续抢占 relay 配对（单页面规则），
     * 把用户当前正在用的另一台设备踢下线（「幽灵占用」）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
