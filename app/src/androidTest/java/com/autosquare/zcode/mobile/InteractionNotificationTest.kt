package com.autosquare.zcode.mobile

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autosquare.zcode.mobile.notify.Notifier
import com.autosquare.zcode.mobile.web.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InteractionNotificationTest {
    @Test
    fun runningTaskQuestionsAndPlanApprovalsReachNotificationManager() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("设备须允许测试应用发送通知", manager.areNotificationsEnabled())
        val name = "通知回归-" + UUID.randomUUID()
        val inject = context.assets.open("inject.js").bufferedReader().use { it.readText() }
        fun notifications() = manager.activeNotifications.filter {
            it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "任务：$name"
        }
        lateinit var web: WebView
        Notifier.ensureChannels(context)
        try {
            instrumentation.runOnMainSync {
                web = WebView(context)
                web.settings.javaScriptEnabled = true
                web.addJavascriptInterface(NativeBridge({ _, title, body, key, id ->
                    Notifier.alert(context, title, body, key, false, id)
                }, {}), "ZCodeNative")
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(inject, null)
                        view.evaluateJavascript("""
                            for (const tool of ['AskUserQuestion','ExitPlanMode','ExitPlanMode']) {
                              const meta = {taskId:'$name', title:'$name',
                                pendingInteraction:{kind:'userInput', interactionId:tool, toolName:tool}};
                              window.__zcFeed(JSON.stringify({meta,activity:{phase:'running'}}));
                            }
                        """.trimIndent(), null)
                    }
                }
                web.loadDataWithBaseURL(null, "<html></html>", "text/html", "utf-8", null)
            }
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (notifications().size < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val titles = notifications().map {
                assertEquals(Notifier.CHANNEL_ALERTS, it.notification.channelId)
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            }
            assertEquals(listOf("计划等待确认", "需要你的回复").sorted(), titles.sorted())
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
            notifications().forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
