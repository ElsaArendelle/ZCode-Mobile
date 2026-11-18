package com.autosquare.zcode.mobile.data

import android.content.Context
import android.net.Uri

/**
 * 绑定与设置存储。
 *
 * 扫码所得的绑定 URL（桌面端「手机扫码连接」二维码内容）持久化在 SharedPreferences。
 * URL 形如 https://zcode.z.ai/remote/v4?sid=<设备ID>&mid=<设备ID2>&name=<电脑名>&app_version=<版本>，
 * sid 是桌面端持久化的设备 ID，稳定不变，扫一次即可长期复用。
 */
object BindingStore {

    private const val PREFS = "zcode_binding"
    private const val KEY_URL = "bound_url"
    private const val KEY_KEEPALIVE = "keepalive_enabled"
    private const val KEY_SOUND = "sound_enabled"

    /** 页面导航域名白名单。来源：桌面 app.asar endpoint 白名单 + 实测 OAuth 授权域（zchat.z.ai）。 */
    private val ALLOWED_SUFFIXES = listOf(
        "zcode.z.ai",
        "zcode.chatglm.site",
        "bigmodel.cn",
        "dev.bigmodel.cn",
        "chat.z.ai",
        "zchat.z.ai",
        "api.z.ai",
    )

    /** 校验扫码结果：必须是移动页绑定 URL（https + 白名单域 + /remote/v4 路径 + sid 参数）。 */
    fun parseBindingUrl(raw: String): Uri? {
        val uri = try {
            Uri.parse(raw.trim())
        } catch (_: Exception) {
            null
        } ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (!isAllowedHost(host)) return null
        val path = uri.path ?: return null
        if (!path.startsWith("/remote/v4")) return null
        if (uri.getQueryParameter("sid").isNullOrBlank()) return null
        return uri
    }

    /** WebView 内导航放行判断。 */
    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return ALLOWED_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun boundUrl(context: Context): String? =
        prefs(context).getString(KEY_URL, null)

    fun save(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, url).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_URL).apply()
    }

    /**
     * 后台提醒（锁屏/切走后仍收任务提醒）。默认开启，但服务只在 App 退到后台时运行——
     * 前台使用期间没有任何常驻通知。
     */
    fun keepaliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEPALIVE, true)

    fun setKeepalive(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEPALIVE, enabled).apply()
    }

    fun soundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSound(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
