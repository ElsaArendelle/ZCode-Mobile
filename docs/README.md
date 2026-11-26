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
| 普通图标 | `ic_launcher.xml` 使用铺满图层的深灰底色，由系统裁出圆形或方形轮廓。透明前景取自方形稿，白色 Z 居中，机器人位于右下角。 |
| 圆形入口 | `android:roundIcon` 指向 `ic_launcher_round`，在同色实底上叠加圆稿。部分圆形桌面及系统设置页会直接裁切普通图标。 |
| 生成脚本 | 在仓库根目录运行 `scripts/build-launcher-icons.ps1`，从方形原稿生成各密度的普通图标和自适应前景。 |
| 启动闪屏 | 浅色和深色主题均通过 `android:windowSplashScreenAnimatedIcon` 使用带透明留白的完整圆标位图 `@mipmap/ic_launcher_background`。窗口底色随 `@color/window_bg` 切换。桌面图标的深色底板独立于透明的 Z 和机器人前景。 |
| 加载层 | WebView `onPageFinished` 前显示主题底色 + logo + 转圈，替代加载期白屏 |
| 位图回退 | 各密度的 `ic_launcher.png` 使用方稿，`ic_launcher_round.png` 使用圆稿。 |

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
     │    │   同一任务优先使用任务索引的活动时间与状态，忽略滞后的旧版会话状态。
     │    │   任务仍在 running 时，单独读取待处理交互，向 Android 上报提问、计划审批和权限确认。
     │    ├─ ⓪ window.Notification 垫片（兜底）：页面 web 模式 showTaskNotification 的实现是
     │    │   `if (!document.hasFocus() && Notification.permission==='granted') new Notification(title,{body})`，
     │    │   WebView 里该 API 恒为 denied → 页面通知永不触发。垫片伪装 granted 并把
     │    │   new Notification(...) 转交 ZCodeNative → 系统通知 + 官方提示音。
     │    │   注意：官方 HTTP 推送（/remote/notification/events，给官方 App 的系统推送）
     │    │   对网页客户端回执 rejected——网页端通知只能走页面内 Notification 路径。
     │    ├─ 在媒体 play() 时识别并静音网页的 task-notification-pop 音频，任务铃声由 Android 通知通道负责。
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

开启后台提醒后，主工作区 WebView 在窗口隐藏时保持可见性，避免移动页主动暂停中继心跳。授权窗口保持系统默认行为。Activity 在退后台时请求连接服务，服务收到启动命令后先调用 `startForeground`，再核对当前是否仍须运行。快速切回前台或关闭提醒时，待投递的启动命令仍能完成系统要求的前台服务登记。进程被系统终止后，单独重建服务无法恢复 WebView 会话。

华为 JEF-AN00 上曾出现短时间切换正常、长时间后台后需要重连。重新打开应用前，先用 `adb shell dumpsys activity exit-info com.autosquare.zcode.mobile` 检查主进程的退出时间和 description，再用 `adb shell dumpsys usagestats` 对照退后台时间。2026-11-22 的 `iAwareF[SystemManager]` 记录发生在切走约一秒后，检查时包状态为 `stopped=true`。此前还有两次独立的 `iAwareF[LowMem](fg-service)` 记录。只有退出时间和故障时间吻合，才能据此判断那一次断连的原因。

`WebScreen.kt` 的设置面板通过 Android 的 `ACTION_APPLICATION_DETAILS_SETTINGS` 打开本包应用信息。华为机型显示「耗电详情 → 启动管理」路径，用户可以单独为 ZCode 开启「允许后台活动」。复测时把已连接的工作区退到后台，在原先会断开的时长之后，从桌面端完成一条任务；回到手机应用之前先检查任务通知，单凭常驻服务或进程仍在无法确认消息送达。

2026-11-22 在 JEF-AN00 上单独为 ZCode 开启「允许后台活动」后，使用带上述入口的 Release 测试包复测。系统记录工作区于 18:07:30 退到后台，18:39:02 在任务通知通道 `alerts` 发出提醒，18:39:13 才回到前台。该次提醒在连续后台约 31 分半时送达。

任务事件从中继帧携带 `taskId` 到 Android，系统通知各自持有独立的不可变 PendingIntent。点击后等待工作区就绪，按任务 ID 打开列表项。目标行折叠时，通过工作区搜索按 ID 核对结果；已打开其他会话时先返回任务列表。页面显示匹配的会话 ID 后结束定位。无目标或目标不可用时显示原通知内容。静默的连接通知仍用于返回应用。

回归检查可运行 `node tests/task-notifications.mjs`。真机验收时先打开任务 A，再回到 Android 桌面，让电脑完成任务 B。先在手机通知栏确认收到 B 的提醒，再点通知核对会话标题及最新回复。2026-11-20 已在华为 JEF-AN00 的 Release 包跑通这条路径。

| Channel | 用途 | 声音 |
|---|---|---|
| `keepalive` | 前台服务常驻 | 无（IMPORTANCE_LOW） |
| `alerts` | 任务提醒 | `res/raw/task_notification_pop.mp3`（提取自 ZCode 桌面端安装包，官方同款提示音）+ 震动 |
| `alerts_silent` | App 内关闭「提醒声音」时的任务通知 | 无 |

中继状态变化和网页 Notification 都可能报告一次任务结束。`inject.js` 按任务 ID 和完成／失败结果在 10 秒内合并，重新进入 running 后开启下一轮。网页只给出标题时短暂等待中继，再用 ID 或未配对的同名结果匹配。两个不同 ID 的同名任务分别提醒。Android 端仍按 `dedupeKey` 在 10 秒内去重；关闭提醒声音时使用静默通道。

**提问和审批**。工作区列表从任务索引的 `meta.pendingInteraction` 或会话摘要的 `pendingInteraction` 读取未处理请求。会话详情从 `pendingInteractions` 快照和 `conversation/<sessionId>` 的 `state.updated` 增量读取。普通 `userInput` 提醒「需要你的回复」，`ExitPlanMode` 或带 `plan_approval` 标记的请求提醒「计划等待确认」，权限请求提醒「需要你的确认」。这些提醒使用原有任务通知通道，点击后进入对应任务。

同一页面内按任务 ID 和请求 ID 识别重复同步，下一次提问使用新的请求 ID。只有分类计数时，在待处理数量增加时生成对应类别的通用提醒。回归用例覆盖停留在列表、会话内增量、同名任务、连续提问及重复帧。Android 设备允许测试应用发送通知后，可用 `InteractionNotificationTest` 检查 WebView 到系统任务通知通道的投递。

2026-11-26 的 Android 模拟器测试在工作区外使用测试数据，依次送入用户提问、计划批准和同一计划批准的重放。系统 `alerts` 通道最终出现两条通知，标题分别为「需要你的回复」和「计划等待确认」。

同日使用华为 JEF-AN00 和签名 Release 包验证桌面端实际请求。结束模拟器占用的配对并让真机重新连接后，手机在 23:28:24 退到后台。23:30:20 的计划审批触发「计划等待确认」，23:31:37 的新提问触发「需要你的回复」。两条通知均进入 `alerts` 通道，期间系统没有记录 ZCode 界面回到前台。

熄屏验收时，同时检查 Android 的 `notification_enqueue` 事件和 `dumpsys audio` 的音频焦点记录。华为真机曾在同一条提醒后先出现系统的 `USAGE_NOTIFICATION`，约一秒后又出现 WebView 的 `USAGE_MEDIA`。修复后，同轮实测只有一次任务通知入队和一次系统通知音频请求。

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

授权窗口在顶栏下显示加载进度条。主文档或登录所需脚本、样式请求失败时才出现错误提示与「重新加载」。部分网络下载官方 CDN 的入口脚本会出现 HTTP/2 流错误；官方移动页可能在主窗口直接跳转，也可能打开独立授权窗口。两种路径共用 AuthAssets，仅对 z-cdn.chatglm.cn 的公开前端脚本和样式使用 HTTPS 上的 HTTP/1.1，完整读取后交给 WebView。客户端按 HTTP 缓存规则复用资源，读取超时为 30 秒，单次请求上限为 55 秒；失败时回退到 WebView 的网络栈。用户在官方页面确认授权后，客户端关闭授权窗口，主页面继续轮询并保存登录状态。授权过期后可从登录页重新发起。

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
