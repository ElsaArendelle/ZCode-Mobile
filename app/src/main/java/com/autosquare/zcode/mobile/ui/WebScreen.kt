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
import com.autosquare.zcode.mobile.web.AuthAssets
import com.autosquare.zcode.mobile.web.NativeBridge

private const val TAG = "ZCodeWeb"

/**
 * 工作区屏：系统 WebView（Chrome 内核）加载移动页。
 * - 无外壳：无地址栏/无标签页；window.open 由独立授权窗口承载（OAuth 全程 App 内完成）
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

    // 授权窗口始终独立承载，主页面保留发起授权时的轮询与状态。
    fun openAuthDialog(): WebView {
        val dialog = android.app.Dialog(context, android.R.style.Theme_Material_Light_NoActionBar)
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()
        val status = android.widget.TextView(context).apply {
            visibility = android.view.View.GONE
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(pad, pad, pad, pad)
        }
        val retry = android.widget.Button(context).apply {
            text = "重新加载"
            visibility = android.view.View.GONE
        }
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            contentDescription = "正在加载授权页面"
        }
        var failed = false
        val aw = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            setBackgroundColor(android.graphics.Color.WHITE)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        fun showError(message: String) {
            failed = true
            progress.visibility = android.view.View.GONE
            status.text = message
            status.visibility = android.view.View.VISIBLE
            retry.visibility = android.view.View.VISIBLE
        }
        aw.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest) =
                AuthAssets.load(request)

            override fun onPageCommitVisible(v: WebView, url: String) {
                if (!failed) progress.visibility = android.view.View.GONE
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val allowed = u.scheme == "https" && BindingStore.isAllowedHost(u.host ?: "")
                if (!allowed && request.isForMainFrame) showError("无法打开此授权链接，请关闭窗口后重新登录。")
                return !allowed
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                failed = false
                progress.visibility = android.view.View.VISIBLE
                status.visibility = android.view.View.GONE
                retry.visibility = android.view.View.GONE
                // 日志不记录授权码、state 或其它查询参数。
                val uri = Uri.parse(url)
                Log.d(TAG, "auth page: ${uri.host}${uri.path}")
            }

            override fun onPageFinished(v: WebView, url: String) {
                progress.visibility = android.view.View.GONE
                if (failed) return
                status.visibility = android.view.View.GONE
                retry.visibility = android.view.View.GONE
                CookieManager.getInstance().flush()
                if (isOAuthCallbackUrl(Uri.parse(url))) {
                    // 回调由服务端处理；主页面自己轮询、落盘会话，不能在这里刷新主页面。
                    v.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 800)
                }
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || AuthAssets.matches(request.url)) showError("登录页面未能加载，请重试。")
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame || AuthAssets.matches(request.url)) showError("授权服务暂时无法访问（${response.statusCode}），请稍后重试。")
            }
        }
        aw.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { dialog.dismiss() }
        }
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val mkBtn = { label: String ->
            android.widget.TextView(context).apply {
                text = label
                textSize = 18f
                setTextColor(android.graphics.Color.DKGRAY)
                setPadding(pad, pad, pad, pad)
            }
        }
        val back = mkBtn("←")
        val close = mkBtn("✕")
        val bar = android.widget.FrameLayout(context).apply {
            addView(back, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.START))
            addView(close, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END))
        }
        root.addView(bar, android.widget.LinearLayout.LayoutParams(-1, -2))
        root.addView(progress, android.widget.LinearLayout.LayoutParams(-1, (3 * dp).toInt()))
        root.addView(status, android.widget.LinearLayout.LayoutParams(-1, -2))
        root.addView(retry, android.widget.LinearLayout.LayoutParams(-1, -2))
        root.addView(aw, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        retry.setOnClickListener { aw.reload() }
        back.setOnClickListener { if (aw.canGoBack()) aw.goBack() else dialog.dismiss() }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (aw.canGoBack()) aw.goBack() else dialog.dismiss()
                true
            } else false
        }
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            aw.stopLoading()
            root.removeView(aw)
            aw.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return aw
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
            IconButton(onClick = { webView?.reload() }) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.menu_reload),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { settingsOpen = true }) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.menu_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    onCreateAuthWindow = { openAuthDialog() },
                ).also { webView = it }
            },
            update = { /* URL 变化不重建；由调用方控制 */ },
        )

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
    u.scheme == "https" && u.host?.lowercase() in setOf("zcode.z.ai", "zcode.chatglm.site") &&
        OAUTH_CALLBACK_PATH.matches(u.path ?: "") &&
        (u.getQueryParameter("code") != null || u.getQueryParameter("error") != null)

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
    onCreateAuthWindow: () -> WebView,
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
                Log.d(TAG, "page started: ${Uri.parse(url).host}${Uri.parse(url).path}")
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
            // 把真实授权 WebView 交给 Chromium，保留 window.open / closed / close 的窗口语义。
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                (resultMsg.obj as WebView.WebViewTransport).webView = onCreateAuthWindow()
                resultMsg.sendToTarget()
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
