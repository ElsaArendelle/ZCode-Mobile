package com.autosquare.zcode.mobile.web

import android.webkit.JavascriptInterface

/**
 * JS 桥：inject.js 通过 window.ZCodeNative 调用。方法体跑在 WebView 的 JS 线程，
 * 回调统一抛回主线程处理。
 */
class NativeBridge(
    private val onNotify: (kind: String, title: String, body: String, dedupeKey: String, taskId: String) -> Unit,
    private val onKicked: () -> Unit,
) {

    @JavascriptInterface
    fun notify(kind: String, title: String, body: String, dedupeKey: String) {
        notifyTask(kind, title, body, dedupeKey, "")
    }

    @JavascriptInterface
    fun notifyTask(kind: String, title: String, body: String, dedupeKey: String, taskId: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onNotify(kind, title, body, dedupeKey, taskId)
        }
    }

    @JavascriptInterface
    fun onKicked() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onKicked()
        }
    }
}
