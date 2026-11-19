package com.autosquare.zcode.mobile.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autosquare.zcode.mobile.MainActivity
import com.autosquare.zcode.mobile.R
import com.autosquare.zcode.mobile.data.BindingStore
import java.util.UUID

data class TaskAlert(val token: String, val taskId: String, val title: String, val body: String, val sameBinding: Boolean)

/**
 * 通知中枢。三个 channel：
 * - keepalive：前台服务常驻通知，IMPORTANCE_LOW 静默（模式平移自 01-GeoBuild-Mobile/AssistService.kt）
 * - alerts：任务提醒，系统提示音（提取自 ZCode 桌面端的 task-notification-pop.mp3）+ 震动
 * - alerts_silent：关闭「提醒声音」后的任务通知
 */
object Notifier {

    /** v2：IMPORTANCE_MIN —— 不显示状态栏图标，仅在通知栏底部折叠（前台服务必需的最小存在感）。 */
    const val CHANNEL_KEEPALIVE = "keepalive_v2"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_ALERTS_SILENT = "alerts_silent"

    const val KEEPALIVE_NOTIFICATION_ID = 7
    const val ACTION_RESCAN = "com.autosquare.zcode.mobile.RESCAN"
    const val ACTION_TASK = "com.autosquare.zcode.mobile.OPEN_TASK"

    private const val DEDUPE_WINDOW_MS = 10_000L
    private val recentKeys = LinkedHashMap<String, Long>()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val keepalive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            context.getString(R.string.notif_channel_keepalive),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notif_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
            setSound(popSoundUri(context), notificationAudioAttributes())
            enableVibration(true)
        }

        val alertsSilent = NotificationChannel(
            CHANNEL_ALERTS_SILENT,
            context.getString(R.string.notif_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannels(listOf(keepalive, alerts, alertsSilent))
        // 清理旧的高打扰 channel
        runCatching { nm.deleteNotificationChannel("keepalive") }
    }

    /** 提取自 ZCode 桌面端安装包的 task-notification-pop-qiQ1Ge3C.mp3。 */
    private fun popSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.task_notification_pop}")

    private fun notificationAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /**
     * 发任务提醒。silent=true 时使用静默通道。
     * 按 dedupeKey 在 [DEDUPE_WINDOW_MS] 内去重。
     */
    fun alert(context: Context, title: String, body: String, dedupeKey: String, silent: Boolean, taskId: String) {
        if (title.isBlank() && body.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(recentKeys) {
            recentKeys.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
            if (recentKeys.containsKey(dedupeKey)) return
            recentKeys[dedupeKey] = now
            if (recentKeys.size > 300) {
                val drop = recentKeys.size - 200
                val iter = recentKeys.entries.iterator()
                repeat(drop) { _ ->
                    iter.next()
                    iter.remove()
                }
            }
        }
        if (!hasNotificationPermission(context)) return

        val channel = if (silent) CHANNEL_ALERTS_SILENT else CHANNEL_ALERTS
        val token = UUID.randomUUID().toString()
        val open = Intent(context, MainActivity::class.java).apply {
            action = ACTION_TASK
            identifier = token
            putExtra("taskId", taskId)
            putExtra("title", title)
            putExtra("body", body)
            putExtra("binding", BindingStore.boundUrl(context)?.hashCode() ?: 0)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { title }))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(token, 0, notification)
        } catch (_: SecurityException) {
            // 通知权限可能在构建通知期间被撤回。
        }
    }

    fun taskFrom(context: Context, intent: Intent): TaskAlert? {
        if (intent.action != ACTION_TASK || intent.identifier == null) return null
        val sameBinding = intent.getIntExtra("binding", 0) == BindingStore.boundUrl(context)?.hashCode()
        return TaskAlert(intent.identifier!!, if (sameBinding) intent.getStringExtra("taskId").orEmpty() else "",
            intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty(), sameBinding)
    }

    /** 前台服务常驻通知（静默 + ongoing），带「重新扫码」动作。 */
    fun keepaliveNotification(context: Context): Notification {
        val open = mainActivityIntent(context, null)
        val rescan = mainActivityIntent(context, Intent().setAction(ACTION_RESCAN))
        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_keepalive_title))
            .setContentText(context.getString(R.string.notif_keepalive_text))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_action_rescan), rescan)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun mainActivityIntent(context: Context, extra: Intent?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            extra?.let { putExtra(EXTRA_RESCAN, true) }
        }
        return PendingIntent.getActivity(
            context,
            if (extra == null) 0 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    const val EXTRA_RESCAN = "extra_rescan"

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** 供 ServiceCompat.startForeground 使用（API 34 要求显式传 type）。 */
internal fun startForegroundCompat(service: android.app.Service, id: Int, notification: Notification) {
    ServiceCompat.startForeground(
        service,
        id,
        notification,
        if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        },
    )
}
