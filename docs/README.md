# ZCode Mobile（Android 壳应用）

个人用 Android 壳应用：内置扫码绑定 + 无外壳 WebView + 任务提醒（提示音/系统通知/后台保活）。
包名 `com.autosquare.zcode.mobile`，显示名 `ZCode`。

## 它是什么 / 不是什么

- **是**：ZCode 桌面端「手机扫码连接」（Web Remote Control，`https://zcode.z.ai/remote/v4`）的原生壳。
  把浏览器扫二维码的流程收进 App：首次启动扫码绑定，之后点图标直达工作区。
- **是**：补齐浏览器壳没有的「消息提醒」——任务完成/失败/需要确认时响铃 + 系统通知，锁屏可见。
- **不是**：修改 ZCode 页面任何内容。页面对壳完全透明，登录（OAuth BigModel/Z.AI、API Key）全部按页面原样走，且全程在 App 内跳转完成（不开新标签、不外跳浏览器）。
  - **例外（用户要求的登录页原生化改造）**：`inject.js` 中
    - `TEXT_REPLACEMENTS`：按钮「通过浏览器授权登录」→「登录」；标题「登录后继续使用 Web 远程控制」→「登录 ZCode」；副标题 →「使用与桌面端相同的账号继续」（文本节点就地替换，React 重渲染后持续生效）。
    - `beautifyLogin()`：给登录卡片打 `data-zc-login` 标记并注入 `zc-style`——去卡片边框/投影、Logo 放大居中、区域选择做成分段控件、登录按钮加高加大圆角、淡入动画。选择器锚定页面的稳定钩子（`data-app-logo` / `role=group` / `data-slot=button`），不依赖动态类名。

## 应用图标

| 项 | 说明 |
|---|---|
| 设计稿归档 | `docs/assets/app-icon-source.png`（方形）、`docs/assets/app-icon-round-source.png`（圆形） |
| 生成脚本 | `.debug/gen_round.ps1`（PowerShell + System.Drawing，无第三方依赖） |
| 自适应图标 | `res/mipmap-anydpi-v26/ic_launcher(.round).xml`：background=`@mipmap/ic_launcher_background`，foreground=透明 |
| 背景层 | `res/mipmap-*/ic_launcher_background.png`：圆形设计稿缩至 **66%** 居中（安全区：更大的比例会被启动器/系统闪屏的圆形遮罩裁掉右下角机器人徽标） |
| 启动闪屏 | `values-v31` / `values-night-v31`：底色 `@color/window_bg`（跟随系统深/浅），图标 `@mipmap/ic_launcher_round` |
| 加载层 | WebView `onPageFinished` 前显示主题底色 + logo + 转圈，替代加载期白屏 |
| 旧版回退 | `res/mipmap-*/ic_launcher(.round).png` 同上参数 |

## 架构

```
MainActivity (singleTask)
 ├─ ScanScreen   相机权限 → CameraX 预览 + ML Kit QR → BindingStore 校验/存储 → 进工作区
 │               相册选图：Photo Picker（API<33 自动降级）→ InputImage.fromFilePath → 同一 ML Kit 校验
 │               （无相机权限也可从相册绑定；截图识别实测通过）
 └─ WebScreen    系统 WebView (Chrome 内核)
     ├─ 白名单导航：zcode.z.ai / zcode.chatglm.site / bigmodel.cn / dev.bigmodel.cn / chat.z.ai / zchat.z.ai / api.z.ai
     ├─ OAuth 登录：WebChromeClient.onCreateWindow 把独立授权窗口内的 WebView 交给 Chromium。
     │    服务端处理回调，主页面保持打开并轮询登录结果。
     ├─ zcode:// 深链拦截（桌面 OAuth 回调，壳内不放行）
     ├─ inject.js（只读观察，零改动页面）
     │    ├─ ⓪-b **WebSocket 帧嗅探（任务完成/失败主通道，已实测闭环）**：
     │    │   relay 帧为明文信封 {"type":"data","payload":{"dataBase64":...}}，内层是
     │    │   二进制帧头 + JSON。解码后 topic=controller/tasks-index，
     │    │   deltas[].task.meta{taskId,title} + task.activity.phase（running/completedSuccess/error）。
     │    │   维护 taskId→phase，相位跃迁到完成/失败 → 原生通知（首见只做基线，避免历史任务刷屏）。
     │    │   实测日志：`[zc-notify] 任务已完成 | 任务：…` + 系统通知 posted。
     │    ├─ ⓪ window.Notification 垫片（兜底）：页面 web 模式 showTaskNotification 的实现是
     │    │   `if (!document.hasFocus() && Notification.permission==='granted') new Notification(title,{body})`，
     │    │   WebView 里该 API 恒为 denied → 页面通知永不触发。垫片伪装 granted 并把
     │    │   new Notification(...) 转交 ZCodeNative → 系统通知 + 官方提示音。
     │    │   注意：官方 HTTP 推送（/remote/notification/events，给官方 App 的系统推送）
     │    │   对网页客户端回执 rejected——网页端通知只能走页面内 Notification 路径。
     │    ├─ 包装 window.Audio：页面播 task-notification-pop-*.mp3 → pop 事件（页面自发提示音信号）
     │    ├─ MutationObserver（childList + characterData）：
     │    │     data-testid="v4-user-input-dialog"           → 权限/输入请求
     │    │     v4-workspace-hook/mcp-pending-unread          → 待审批未读徽标
     │    │     文本命中 任务已完成/任务出错/需要你的确认/…/任务：{title} → 任务通知
     │    │     文本命中 重新生成二维码/被踢出 → onKicked（引导重扫）
     │    └─ TEXT_REPLACEMENTS 文案替换 + 登录页原生化（见下）
     └─ NativeBridge (window.ZCodeNative) → Notifier

顶部工具栏的刷新与设置使用 24dp 线框矢量图标，随主题着色。两个按钮保留 Material 的触控区域，并分别向读屏服务提供“刷新页面”和“设置”标签。

菜单交互（重要设计）：**无悬浮按钮**——页面是全屏沉浸 SPA，任何悬浮元素都会与页面 header 重叠挡点击。
菜单=长按屏幕任意处唤出（WebView.setOnLongClickListener 拦截，替换默认文本选择）；
首次使用 Toast 提示一次。菜单/设置/被踢提示全部为自绘覆盖层（Compose 弹层组件在
WebView 混合层级坐标错乱，不可用）。
ConnectionService (前台服务 specialUse)
 └─ 仅保进程（WS 连接活在主进程 WebView 里）；常驻静默通知 + 「重新扫码」动作
 └─ onTaskRemoved 自动停止（划掉任务即断连接，防止后台「幽灵占用」抢配对）
 └─ 检测到「被踢」时 WebScreen 主动停服务（被踢=另一设备占用，不反抢）
```

### 通知三通道（notify/Notifier.kt）

| Channel | 用途 | 声音 |
|---|---|---|
| `keepalive` | 前台服务常驻 | 无（IMPORTANCE_LOW） |
| `alerts` | 任务提醒 | `res/raw/task_notification_pop.mp3`（提取自 ZCode 桌面端安装包，官方同款提示音）+ 震动 |
| `alerts_silent` | App 在前台且页面已自发提示音（pop 后 2.5s 内） | 无，避免双重响铃 |

按 `dedupeKey` 做 10 秒窗口去重；`settings.sound_enabled=false` 时走 silent 通道。

## 依据的关键逆向事实（来自桌面 app.asar，2026-11）

- 扫码内容 = `https://zcode.z.ai/remote/v4?sid=<设备ID>&mid=…&name=…&app_version=…`；
  `sid` 持久化在桌面 `~/.zcode/v2/setting.json` 的 `webRemoteControlRelayV2Device.deviceSid`，稳定不变 → 扫一次即可。
- 桌面 → 手机推送：`POST /api/v1/remote/notification/events`（事件 `task_completed/task_failed/permission_request/elicitation_request/feedback_update`，标题模板 `任务：{title}`），relay 经 ws2 转发给手机页。
- 页面**不用**浏览器 Notification API、不改 title、不震动（asar 全文 0 处）——所以壳内通知只能观察 DOM/页面自身音频，见上表。
- 单配对只允许一个手机页面：同一 sid 在桌面浏览器再开会把 App 踢下线（页面报「重新生成二维码」→ App 弹重扫引导）。

## 构建 / 调试

```bash
# 本机 Git Bash 无 JAVA_HOME，用 Android Studio 自带 JBR：
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat installDebug
```

- **通知钩子实测**：`chrome://inspect` 远程调试 WebView（debug 构建默认可调试），桌面端跑任务触发真实推送，观察 DOM 与注入脚本命中情况（`adb logcat -s ZCodeWeb` 看 console 转发）。
- **模拟器无相机扫码场景**：可用 `adb shell run-as com.autosquare.zcode.mobile` 直接写 `shared_prefs/zcode_binding.xml` 注入绑定 URL（桌面端二维码旁有「复制链接」按钮）。
- 通知日志：`adb logcat | grep -i notif`；桥接事件走 Notifier（无独立日志），排查时在 `NativeBridge.notify` 加断点。

## 登录与模拟器验证

授权窗口在顶栏下显示加载进度条。主文档或登录所需脚本、样式请求失败时才出现错误提示与「重新加载」。部分网络下载官方 CDN 的入口脚本会出现 HTTP/2 流错误；AuthAssets 仅对授权窗口中 z-cdn.chatglm.cn 的公开前端脚本和样式使用 HTTPS 上的 HTTP/1.1，完整读取后交给 WebView。用户在官方页面确认授权后，客户端关闭授权窗口，主页面继续轮询并保存登录状态。授权过期后可从登录页重新发起。

在已绑定并安装 debug 包的测试模拟器上运行 `node tests/oauth-webview.mjs`，可检查授权返回和重复登录时主页面状态是否保留。测试会重启测试应用。

本机切换 VPN 后若模拟器无法解析域名，可先关闭该 AVD，再运行 `.\scripts\start-emulator.ps1`。脚本使用当前以太网实测可达的 DNS 202.98.18.3。更换网络后可通过 `-DnsServer` 指定新网络可达的 DNS。该选项只对本次模拟器启动生效。

## 已知边界

- **主题跟随系统（重要）**：ZCode 移动页支持深/浅两套主题、跟随系统 `prefers-color-scheme`。
  WebView 的 prefers-color-scheme 继承 App 主题基座——因此 values 用 `Theme.Material.Light.NoActionBar`、
  values-night 用深色基座，状态栏颜色/浅深图标（windowLightStatusBar）同步两套；
  页面、状态栏、窗口背景三者在两种系统模式下均一致（浅色系统=全浅色，深色系统=全深色）。
  坑：主题基座若写死深色，浅色系统用户在 App 里页面会被强制深色（与浏览器行为不一致）。
- **非沉浸式布局（用户要求）**：不用 enableEdgeToEdge，状态栏/导航栏为不透明实底，页面内容从状态栏下方开始——避免页面 header 顶进状态栏重叠。

- **relay 会话粘滞（官方服务端行为）**：配对成功后若手机端异常断开（force-stop/断网），relay 侧旧 terminal 记录未即时清理；短时间内手机重连会触发 `session-conflict` 把桌面端踢下线（桌面日志 `external-relay-kicked`），手机页显示「电脑端离线」。恢复方式=官方引导：桌面端重开「手机扫码连接」面板（waiting_terminal）→ 手机点「重新连接」；若 relay 未清理仍失败，等待数分钟让旧会话超时后重试。调试时避免反复 force-stop。2026-11-18 实测：首次配对/工作区同步/真实推送（mobile-push receipt）/通知铃全链路验证通过。
- WebView 关闭捏合缩放与 overview 总览缩放（页面自带 device-width viewport；App 形态不缩放，防误手势缩小后记忆缩放状态）。
- 白名单外域一律拒绝导航；OAuth 若新增授权域，改 `BindingStore.ALLOWED_SUFFIXES`。
- 相机/麦克风/定位权限页面一律拒绝（扫码用原生 CameraX，不依赖页面权限）。
- 下载文件未处理（DownloadListener 未实现）。
- 前台保活可关（设置 → 后台保活）；关闭后锁屏/后台数分钟进程可能被杀，提醒不保证送达。
