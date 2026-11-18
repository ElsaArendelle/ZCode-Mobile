# ZCode Mobile（非官方 Android 客户端）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

为 ZCode 桌面端「手机扫码连接（Web 远程控制）」打造的 Android 壳应用：
内置扫码绑定、无外壳浏览器、任务完成/失败提醒（提示音 + 系统通知 + 后台保活）。

> **非官方项目**：本项目与 ZCode / 智谱 AI 无任何关联，仅供个人学习研究。
> 「ZCode」名称与相关商标归其所有者所有。使用需遵守 ZCode 官方服务条款。

## 功能

| 功能 | 说明 |
|---|---|
| 扫码绑定 | 首次用相机扫描桌面端二维码；也支持**从相册选二维码截图**识别 |
| 免扫码直达 | 绑定一次，之后点图标直接进工作区 |
| 无外壳浏览 | 系统 WebView（Chromium 内核）加载移动页，无地址栏/无标签页；OAuth 全程应用内完成 |
| 任务提醒 | 任务完成/失败/需要确认 → 系统通知 + 官方同款提示音；前台后台都能收到 |
| 后台提醒 | 退后台/锁屏后仍保持连接（使用期间无常驻通知） |
| 主题跟随系统 | 深色/浅色系统自动适配（页面与外壳一致） |
| 登录自动化 | 授权确认自动完成；授权窗口期内自动续期，输验证码再慢也不会超时 |
| 工具栏 | 顶部常驻：重新扫码 / 刷新 / 设置（后台提醒与提醒声音开关）；长按屏幕也可唤出菜单 |

## 构建

要求：Android Studio（或 JDK 17+）、Android SDK 34。

```bash
# Git Bash 环境需指定 Android Studio 自带 JBR：
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

minSdk 31（Android 12）· targetSdk 34 · 单 Activity Compose + WebView

## 使用

1. ZCode 桌面端打开「手机扫码连接」面板
2. App 首次启动 → 扫码（或把二维码截图发到手机后从相册选择）
3. 点「登录」→ 授权页输手机号 + 验证码（其余全自动）
4. 进入工作区；桌面跑任务，手机收通知

换设备使用注意：一个桌面端配对同时只允许一个手机页面在线，
换设备前请先关闭旧设备上的 App（从最近任务划掉即可）。

## 隐私

- 不收集、不上传任何数据；无第三方统计/广告 SDK
- 登录凭据与绑定信息仅存于本机应用私有目录
- 网络请求仅指向 ZCode/Z.AI 官方域名（白名单外一律拦截）

## 已知限制

- ZCode 官方移动页 OAuth 流程有效窗口约 60 秒（应用内已自动续期，正常无感）
- relay 单页面规则：同配对多设备同开会被互踢（官方服务端行为）
- 相机/麦克风/定位权限页面一律拒绝（扫码用原生实现，不需要页面权限）

## 文档

- 架构与逆向分析、调试方法：[docs/README.md](docs/README.md)

## License

[MIT](LICENSE) © autosquare
