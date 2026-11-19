# 轻跳 (Skip) 开发与版本发布规范

## 1. 架构与进程边界

- **双进程隔离**：
  - 主进程（`com.luo.skip`）：Jetpack Compose UI、规则/排除列表配置、手动检查并下载应用更新。
  - 无障碍服务进程（`com.luo.skip:service`）：纯 Kotlin 轻量进程，**绝不加载任何 Compose/UI 组件，也绝不发起任何网络请求**。
- **零重型三方依赖**：JSON 解析统一使用 Android 内置 `org.json`，应用内更新统一使用系统 `HttpsURLConnection` + `FileProvider`，保持 Release APK 体积在 ~1.1 MB。

## 2. 版本控制与远程更新（Cloudflare R2 + Worker）

- **版本号单一来源**：[app/build.gradle.kts](app/build.gradle.kts) 中的 `resolvedVersionCode` 与 `resolvedVersionName`，支持通过 `-PandroidVersionCode`、`-PandroidVersionName`、`-PandroidUpdateUrl` 注入。
- **升版原则**：
  - 日常代码修改、本地编译或调试**不自动递增**版本号。
  - 仅在用户明确要求“发布 App / 发版 / 更新到远端”或明确要求新增版本时递增 `versionCode`（未指定新 `versionName` 时沿用当前 `versionName`）。
- **固定签名要求**：
  - 手机日常安装与云端更新通道统一使用项目 `release.jks` 正式签名（证书 SHA-256：`e7978760b9fd7b3a02d0b3272b5a5a27b09f00efdc97a3604f165b6cf5af7ca7`）。
  - 切勿用 Debug 签名包上传远端更新通道，否则客户端签名校验会拒绝安装。
- **远端发布入口**：
  - 遵循 [.agents/skills/skip-app-publish/SKILL.md](.agents/skills/skip-app-publish/SKILL.md)，调用 `scripts/android-release.mjs` 自动完成 APK 元数据与签名核验、不可变 APK 上传、公网下载 SHA-256 校验、`latest.json` 更新及读回核对。
  - 线上更新地址：`https://yanjiu-android-updates.bojin1229.workers.dev/skip/latest.json`。
