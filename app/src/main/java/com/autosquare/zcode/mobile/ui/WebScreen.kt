package com.autosquare.zcode.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.autosquare.zcode.mobile.R
import com.autosquare.zcode.mobile.data.BindingStore
import com.autosquare.zcode.mobile.notify.Notifier
import com.autosquare.zcode.mobile.service.ConnectionService
import com.autosquare.zcode.mobile.web.NativeBridge

private const val TAG = "ZCodeWeb"

/**
 * 工作区屏：系统 WebView（Chrome 内核）加载移动页。
 * - 无外壳：无地址栏/无标签页；window.open 一律路由回本 WebView（OAuth 全程 App 内跳转）
 * - 白名单：仅放行 ZCode/OAuth 相关域的导航，zcode:// 深链拦截
 * - 通知：inject.js 观察页面通知载体 → NativeBridge → Notifier
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, onNeedRescan: () -> Unit) {
    val context = LocalContext.current
    val injectJs = remember {
        try {
            context.assets.open("inject.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var mainFrameError by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var kicked by remember { mutableStateOf(false) }


    // 菜单唤出：长按屏幕任意处（无悬浮按钮，不与页面 UI 重叠）
    var menuOpen by remember { mutableStateOf(false) }

    // 首帧加载态：页面 onPageFinished 前显示 logo + 转圈，替代白屏
    var pageLoaded by remember { mutableStateOf(false) }

    // 首次授权超时后自动重走授权时，用遮罩盖住页面上的「授权流程未完成」红字
    var authRetrying by remember { mutableStateOf(false) }

    // OAuth 授权页：用 Dialog 窗口承载（不是 Activity）。
    // 关键：Activity 被覆盖会暂停/节流主 WebView，页面轮询停摆 → 服务端 flow 过期 → 「授权流程未完成」。
    // Dialog 只是窗口叠加，主 Activity 保持 RESUMED，轮询持续。
    // 二次授权静默化：自动重登录不再弹窗（隐藏 WebView 完成），授权页只出现一次
    val silentRelogin = remember { mutableStateOf(false) }
    // 授权窗开着期间（用户正在输验证码），一切新授权 URL 一律静默承载——
    // 续期跳转有两条拦截路径，一次性标志会被第一条吃掉导致第二条弹可见窗
    val authDialogActive = remember { mutableStateOf(false) }
    val reloginCount = remember { mutableStateOf(0) }

    fun maybeRelogin() {
        if (reloginCount.value < 2) {
            reloginCount.value++
            silentRelogin.value = true
            // 等待态没有「登录」按钮（只有「取消」）：先取消等待，再发起登录
            webView?.evaluateJavascript(
                "(function(){var c=[...document.querySelectorAll('button')].find(" +
                    "function(x){return x.textContent.trim()==='取消'&&!x.disabled;});" +
                    "if(c)c.click();return 'ok';})()", null,
            )
            webView?.postDelayed({
                webView?.evaluateJavascript(
                    "(function(){var b=[...document.querySelectorAll('button')].find(" +
                        "function(x){return x.textContent.trim()==='登录'&&!x.disabled;});" +
                        "if(b){b.click();return 'retry';}return 'nobtn';})()",
                ) { r -> Log.i(TAG, "maybeRelogin click: $r") }
            }, 800)
        } else {
            authRetrying = false
        }
    }

    fun startPairWatch() {
        authRetrying = true
        var attempts = 0
        val checker = object : Runnable {
            override fun run() {
                attempts++
                if (attempts > 40) {
                    authRetrying = false
                    return
                }
                webView?.evaluateJavascript(
                    "(function(){var k=Object.keys(localStorage);" +
                        "if(k.indexOf('oauth:browser_session')!==-1)return 'paired';" +
                        "var t=document.body.innerText||'';" +
                        "if(t.indexOf('授权流程未完成')!==-1)return 'failed';" +
                        "return 'waiting';})()",
                ) { r ->
                    when {
                        r == null -> authRetrying = false
                        r.contains("paired") -> authRetrying = false
                        // 授权已完成却还没拿到会话：8 秒即主动发起新一轮
                        // （页面自己的 60 秒超时太慢；此时 zchat 已登录，新一轮秒完成）
                        r.contains("waiting") && attempts >= 13 -> {
                            maybeRelogin()
                            webView?.postDelayed(this, 1200)
                        }
                        r.contains("failed") -> {
                            maybeRelogin()
                            webView?.postDelayed(this, 1200)
                        }
                        else -> webView?.postDelayed(this, 600)
                    }
                }
            }
        }
        webView?.postDelayed(checker, 600)
    }

    fun makeAuthClient(aw: android.webkit.WebView, hadCallback: BooleanArray, quiet: Boolean, onDone: () -> Unit): android.webkit.WebViewClient =
        object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(v: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                val u = request.url
                val host = u.host?.lowercase() ?: return true
                val allowed = listOf(
                    "zchat.z.ai", "z.ai", "chat.z.ai", "bigmodel.cn", "dev.bigmodel.cn",
                    "zcode.z.ai", "zcode.chatglm.site", "api.z.ai",
                ).any { host == it || host.endsWith(".$it") }
                Log.d(TAG, "auth nav: $u")
                return !allowed
            }

            override fun onPageFinished(v: android.webkit.WebView, url: String) {
                super.onPageFinished(v, url)
                val u = android.net.Uri.parse(url)
                val isCallback = u.host?.lowercase() == "zcode.z.ai" &&
                    (u.path ?: "").startsWith("/api/v1/oauth/") &&
                    (u.getQueryParameter("code") != null || u.getQueryParameter("error") != null)
                if (isCallback) {
                    hadCallback[0] = true
                    v.evaluateJavascript("document.body.innerText.slice(0,160)") { text ->
                        Log.i(TAG, "auth callback text: $text")
                    }
                    v.postDelayed(onDone, 600)
                    return
                }
                // 授权确认自动完成（勾选协议 + 继续）；登录表单（手机号/验证码）不动
                v.evaluateJavascript(
                    "(function(){try{var t=document.body.innerText||'';" +
                        "if(t.indexOf('手机号登录')!==-1||t.indexOf('发送验证码')!==-1)return 'login';" +
                        "var bs=[].slice.call(document.querySelectorAll('button'));" +
                        "var c=bs.filter(function(b){return (b.textContent||'').trim()==='继续';})[0];" +
                        "if(!c)return 'none';" +
                        "if(!c.disabled){c.click();return 'go';}" +
                        "var cb=document.querySelector('input[type=checkbox]');" +
                        "if(!cb){cb=bs.filter(function(b){return !(b.textContent||'').trim();})" +
                        ".filter(function(b){var r=b.getBoundingClientRect();return r.width<30&&r.height<30;})[0];}" +
                        "if(cb)cb.click();" +
                        "setTimeout(function(){if(!c.disabled)c.click();},700);" +
                        "return 'armed';}catch(e){return 'err';}})()",
                ) { r ->
                    if (r != null && (r.contains("go") || r.contains("armed"))) {
                        Log.i(TAG, "auth auto-confirm: $r")
                        if (!quiet) {
                            android.widget.Toast.makeText(context, "已自动确认授权", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    fun openAuthDialog(authUrl: String, silent: Boolean = false) {
        val aw = android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        val hadCallback = booleanArrayOf(false)
        if (!silent) {
            val dialog = android.app.Dialog(context, android.R.style.Theme_Material_Light_NoActionBar)
            aw.webViewClient = makeAuthClient(aw, hadCallback, false) { runCatching { dialog.dismiss() } }
            // 顶栏：右上角 ✕ 随时关闭授权页（防止误点后无处可退）
            val dp = context.resources.displayMetrics.density
            val pad = (14 * dp).toInt()
            val root = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            val close = android.widget.TextView(context).apply {
                text = "✕"
                textSize = 18f
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(pad, pad, pad, pad)
            }
            // 页内返回：看协议等跳转后用它退回授权页（canGoBack 时），否则关闭
            val back = android.widget.TextView(context).apply {
                text = "←"
                textSize = 18f
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(pad, pad, pad, pad)
            }
            val bar = android.widget.FrameLayout(context).apply {
                addView(
                    back,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL,
                    ),
                )
                addView(
                    close,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL,
                    ),
                )
            }
            back.setOnClickListener { if (aw.canGoBack()) aw.goBack() else dialog.dismiss() }
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                    event.action == android.view.KeyEvent.ACTION_UP
                ) {
                    if (aw.canGoBack()) aw.goBack() else dialog.dismiss()
                    true
                } else {
                    false
                }
            }
            root.addView(
                bar,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            root.addView(
                aw,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                ),
            )
            close.setOnClickListener { dialog.dismiss() }
            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(false)
            // 首次登录输验证码常超 60 秒，服务端 flow 会作废。
            // 授权窗打开期间每 40 秒静默续期一次（主页面重新 init，新授权页由
            // 隐藏 WebView 承载：zchat 未登录时它停在登录表单，无副作用；
            // 用户输完码完成授权时，对应的正是最新 flow）→ 第一次就能成功。
            val renew = object : Runnable {
                override fun run() {
                    if (hadCallback[0]) return
                    Log.i(TAG, "flow renew: refresh login attempt")
                    reloginCount.value = 0
                    silentRelogin.value = true
                    webView?.evaluateJavascript(
                        "(function(){var c=[...document.querySelectorAll('button')].find(" +
                            "function(x){return x.textContent.trim()==='取消'&&!x.disabled;});" +
                            "if(c)c.click();return 'ok';})()", null,
                    )
                    webView?.postDelayed({
                        webView?.evaluateJavascript(
                            "(function(){var b=[...document.querySelectorAll('button')].find(" +
                                "function(x){return x.textContent.trim()==='登录'&&!x.disabled;});" +
                                "if(b){b.click();return 'retry';}return 'nobtn';})()",
                        ) { r -> Log.i(TAG, "renew click: $r") }
                    }, 800)
                    aw.postDelayed(this, 40000)
                }
            }
            aw.postDelayed(renew, 40000)
            dialog.setOnDismissListener {
                authDialogActive.value = false
                aw.removeCallbacks(renew)
                runCatching { aw.destroy() }
                // 无论是否已回调都监测：手动误关后 8 秒内无会话也会自动续上登录
                startPairWatch()
            }
            dialog.show()
            authDialogActive.value = true
        } else {
            // 静默授权：0x0 容器承载 WebView，全程不可见
            val host = android.widget.FrameLayout(context)
            val lp = android.view.ViewGroup.LayoutParams(0, 0)
            (context as? android.app.Activity)?.addContentView(host, lp)
            host.addView(aw, android.widget.FrameLayout.LayoutParams(0, 0))
            aw.postDelayed({
                if (!hadCallback[0]) {
                    Log.i(TAG, "silent auth: idle timeout, destroy")
                    (host.parent as? android.view.ViewGroup)?.removeView(host)
                    runCatching { aw.destroy() }
                }
            }, 90000)
            aw.webViewClient = makeAuthClient(aw, hadCallback, true) {
                (host.parent as? android.view.ViewGroup)?.removeView(host)
                runCatching { aw.destroy() }
                startPairWatch()
            }
        }
        aw.loadUrl(authUrl)
    }


    // 最近一次页面自发提示音（pop）时间，用于避免与系统通知双重响铃
    var lastPopAt by remember { mutableStateOf(0L) }

    // 通知权限（API 33+ 运行时请求）；授权后重发常驻通知（FGS 通知在授权前发出会被隐藏）
    val notifLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && BindingStore.keepaliveEnabled(context)) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ConnectionService::class.java),
                )
            }
        }
    LaunchedEffect(Unit) {
        Notifier.ensureChannels(context)
        if (!Notifier.hasNotificationPermission(context)) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 前台使用期间不挂任何常驻通知：保活服务由 MainActivity 在 onPause 时启动、onResume 时停止
    LaunchedEffect(Unit) {
        runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
        // 首次使用提示长按手势（只提示一次）
        val prefs = context.getSharedPreferences("zcode_binding", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hint_longpress", false)) {
            prefs.edit().putBoolean("hint_longpress", true).apply()
            android.widget.Toast.makeText(context, "长按屏幕任意位置可打开菜单", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 常驻工具栏（状态栏与页面之间，自绘条不与页面内容重叠）：扫码 / 刷新 / 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ZCode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            TextButton(onClick = onNeedRescan) { Text(stringResource(R.string.menu_rescan)) }
            IconButton(onClick = { webView?.reload() }, modifier = Modifier.size(40.dp)) {
                Text("⟳", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { settingsOpen = true }, modifier = Modifier.size(40.dp)) {
                Text("⚙", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createWebView(
                    ctx = ctx,
                    initialUrl = url,
                    injectJs = injectJs,
                    onPageStartedInject = { w -> w.evaluateJavascript(injectJs, null) },
                    onMainFrameError = { mainFrameError = true },
                    onNotify = { kind, title, body, key ->
                        if (kind == "pop") {
                            lastPopAt = System.currentTimeMillis()
                            return@createWebView
                        }
                        val silent = !BindingStore.soundEnabled(ctx) ||
                            (Notifier.appInForeground &&
                                System.currentTimeMillis() - lastPopAt < 2500)
                        Notifier.alert(ctx, title, body, key, silent)
                    },
                    onKicked = {
                        kicked = true
                        // 被踢=另一设备占用本配对（relay 单页面规则）。
                        // 停掉保活防止本机后台继续自动重连抢占，把对方反复踢下线。
                        runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
                    },
                    onWebViewRecreated = { reloadKey++ },
                    onLongPress = { menuOpen = true },
                    onPageReady = { pageLoaded = true },
            onOpenAuth = { authUrl ->
                val silent = silentRelogin.value || authDialogActive.value
                silentRelogin.value = false
                openAuthDialog(authUrl, silent)
            },
                ).also { webView = it }
            },
            update = { /* URL 变化不重建；由调用方控制 */ },
        )

        // 重新授权遮罩：盖住超时红字，避免「红字闪一下」
        if (authRetrying) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp,
                    )
                    Text(
                        "正在完成登录…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }

        // 首帧加载层：主题色背景 + logo + 转圈，避免加载期间白屏
        if (!pageLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(top = 20.dp).size(28.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
        }

        // 无悬浮按钮（会与页面 header 重叠挡点击）——长按屏幕唤出底部菜单
        if (menuOpen) {
            // 自绘覆盖菜单：Compose 弹层（DropdownMenu/BottomSheet）在 WebView 混合层级坐标错乱，
            // 用同层级 Surface 覆盖，从底部滑入，点击遮罩关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { menuOpen = false },
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 18.dp, horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.menu_rescan),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { menuOpen = false; onNeedRescan(); Unit }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        Text(
                            stringResource(R.string.menu_reload),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { menuOpen = false; webView?.reload(); Unit }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        Text(
                            stringResource(R.string.menu_settings),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { menuOpen = false; settingsOpen = true; Unit }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }

        if (mainFrameError) {
            ErrorOverlay(onRetry = { mainFrameError = false; webView?.reload() })
        }

        if (kicked) {
            KickBanner(
                onRescan = { kicked = false; onNeedRescan() },
                onDismiss = { kicked = false },
            )
        }

        if (settingsOpen) {
            SettingsDialog(onDismiss = { settingsOpen = false })
        }
        } // Box(weight(1f)) —— WebView 容器
    } // Column —— 工具栏 + 页面
}

private val OAUTH_CALLBACK_PATH = Regex("^/api/v1/oauth/.*/callback(/.*)?$")

/** 服务端 OAuth 回调 URL（zcode.z.ai/api/v1/oauth/&lt;provider&gt;/callback?code&state…）。 */
private fun isOAuthCallbackUrl(u: Uri): Boolean =
    u.host?.lowercase() == "zcode.z.ai" &&
        OAUTH_CALLBACK_PATH.matches(u.path ?: "") &&
        (u.getQueryParameter("code") != null || u.getQueryParameter("error") != null)

/**
 * 把回调参数拼回 SPA 绑定 URL。SPA 检测到自身 URL 携带 code&state 后，
 * 会走 handleCallback → 客户端 exchangeToken（/api/v1/oauth/token）完成登录；
 * state 自包含（nonce+return_to），SPA 重启也能校验。
 */
private fun buildSpaCallbackUrl(spaUrl: String, callback: Uri): String {
    val base = Uri.parse(spaUrl).buildUpon()
    callback.getQueryParameter("code")?.let { base.appendQueryParameter("code", it) }
    callback.getQueryParameter("authCode")?.let { base.appendQueryParameter("authCode", it) }
    callback.getQueryParameter("error")?.let { base.appendQueryParameter("error", it) }
    callback.getQueryParameter("state")?.let { base.appendQueryParameter("state", it) }
    return base.build().toString()
}

private fun createWebView(
    ctx: android.content.Context,
    initialUrl: String,
    injectJs: String,
    onPageStartedInject: (WebView) -> Unit,
    onMainFrameError: () -> Unit,
    onNotify: (kind: String, title: String, body: String, key: String) -> Unit,
    onKicked: () -> Unit,
    onWebViewRecreated: () -> Unit,
    onLongPress: () -> Unit,
    onPageReady: () -> Unit,
    onOpenAuth: (String) -> Unit,
): WebView {
    val main = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        // 页面自带 width=device-width viewport 声明；关闭 overview 缩放，
        // 避免 WebView 按「总览」把响应式页面缩小渲染（登录卡片变小一圈的元凶）
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        // 壳是 App 形态：禁掉捏合缩放，防止误手势把页面缩小后 WebView 记住缩放状态
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.databaseEnabled = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(
            NativeBridge(
                onNotify = onNotify,
                onKicked = onKicked,
                onOpenAuth = { url -> onOpenAuth(url) },
            ),
            "ZCodeNative",
        )

        // 长按唤出菜单（替换 WebView 默认的文本选择长按）；无悬浮按钮不挡页面 UI
        setOnLongClickListener { onLongPress(); true }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme?.lowercase()
                return when {
                    // OAuth 服务端回调：把 code&state 转交给 SPA 自身 URL 完成客户端换 token
                    request.isForMainFrame && isOAuthCallbackUrl(u) -> {
                        val target = buildSpaCallbackUrl(initialUrl, u)
                        Log.d(TAG, "oauth callback -> spa: $u => $target")
                        view.loadUrl(target)
                        true
                    }
                    // 白名单域：页面内部加载（含 OAuth 授权页跳转）
                    (scheme == "https" || scheme == "http") &&
                        BindingStore.isAllowedHost(u.host ?: "") -> false
                    // 桌面 OAuth 深链回调：壳内不放行（页面侧等待/轮询逻辑自行收尾）
                    scheme == "zcode" -> {
                        Log.d(TAG, "block deep link: $u")
                        true
                    }
                    else -> {
                        Log.d(TAG, "blocked navigation to $u")
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "page started: $url")
                // 302 服务端重定向不经过 shouldOverrideUrlLoading：回调页已开载时同样改道回 SPA
                val startedUri = Uri.parse(url)
                if (isOAuthCallbackUrl(startedUri)) {
                    val target = buildSpaCallbackUrl(initialUrl, startedUri)
                    Log.d(TAG, "oauth callback (redirect) -> spa: $target")
                    view.loadUrl(target)
                    return
                }
                // 尽早注入（抢 Audio 构造器包装时机）
                onPageStartedInject(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                onPageReady()
                // 兜底注入（observer 保证存活）+ 持久化 Cookie
                view.evaluateJavascript(injectJs, null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) onMainFrameError()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 渲染进程崩溃：销毁重建（AndroidView factory 重新执行）
                view.destroy()
                Handler(Looper.getMainLooper()).post { onWebViewRecreated() }
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            /**
             * 新窗口（window.open / target=_blank）：不内嵌渲染（叠层白屏），
             * 用一次性 transport 捕获 URL → 交给授权弹窗（openAuthDialog）打开。
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val captured = arrayOfNulls<String>(1)
                val transport = WebView(view.context)
                transport.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        if (captured[0] == null) {
                            captured[0] = u
                            Log.d(TAG, "auth popup captured: $u")
                            onOpenAuth(u)
                        }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = transport
                resultMsg.sendToTarget()
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { transport.destroy() }
                }, 8000)
                return true
            }

            // 相机/麦克风/定位一律拒绝（扫码用原生实现，不依赖页面权限）
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d(TAG, consoleMessage?.message() ?: "")
                return true
            }
        }

        loadUrl(initialUrl)
    }
    return main
}

@Composable
private fun ErrorOverlay(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.web_offline), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.web_retry))
            }
        }
    }
}

@Composable
private fun KickBanner(onRescan: () -> Unit, onDismiss: () -> Unit) {
    // 自绘居中卡片（AlertDialog 弹层在 WebView 混合层级坐标错乱）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss(); Unit },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.menu_rescan), style = MaterialTheme.typography.titleMedium)
                Text(
                    "配对已被其他页面占用或已失效。请在桌面端重新打开「手机扫码连接」并重新扫码。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRescan) { Text(stringResource(R.string.menu_rescan)) }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var keepalive by remember { mutableStateOf(BindingStore.keepaliveEnabled(context)) }
    var sound by remember { mutableStateOf(BindingStore.soundEnabled(context)) }

    // 自绘居中卡片（AlertDialog 弹层在 WebView 混合层级坐标错乱）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss(); Unit },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_keepalive))
                        Text(
                            stringResource(R.string.settings_keepalive_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = keepalive,
                        onCheckedChange = { on ->
                            keepalive = on
                            BindingStore.setKeepalive(context, on)
                            // 前台期间不启动服务（无通知）；开启后会在下次退到后台时生效
                            if (!on) {
                                runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_sound))
                    }
                    Switch(
                        checked = sound,
                        onCheckedChange = { on ->
                            sound = on
                            BindingStore.setSound(context, on)
                        },
                    )
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}
