---
name: skip-app-publish
description: 发布轻跳 (Skip) Android App 到既有 Cloudflare R2 下载服务（/skip/ 通道），让手机在「设置 › 检查更新」中直接下载并覆盖更新。用户明确要求“发布 App”“发布新版”“发布手机端”“把安装包更新到远端”时使用。自动递增 versionCode、整理更新说明、构建 Release 正式签名包、校验签名与 SHA-256、上传 APK 与 latest.json 并公网读回验收。
---

# 发布轻跳 (Skip) Android App

本技能位于项目 `.agents/skills/skip-app-publish/`，同目录 [release-config.json](release-config.json) 固化了公开更新通道、R2 桶与 `release.jks` 可信签名证书 SHA-256 摘要（不含任何密钥或令牌）。

## 1. 触发与版本控制规则

- **日常构建不自动升版**：普通开发、修复、测试、本地 `./gradlew assembleRelease` 均保持 `app/build.gradle.kts` 中的当前 `resolvedVersionCode` 与 `resolvedVersionName` 不变。
- **明确要求发布时自动递增 `versionCode`**：
  - 读取线上 `https://yanjiu-android-updates.bojin1229.workers.dev/skip/latest.json`（若存在）与 `app/build.gradle.kts` 中的默认 `resolvedVersionCode`。
  - 选择 `nextCode = max(线上 versionCode, 源码默认 versionCode) + 1`。
  - 用户未指定新 `versionName` 时沿用当前 `versionName`（如 `1.0.0`）；用户明确要求升大/小版本名时同步更新 `versionName`。
- **执行即授权**：用户明确说“发布 App / 发版 / 更新到远端”即包含递增 `versionCode` 与上传 R2 授权，无需重复询问确认。

## 2. 环境与构建命令

本机 JDK 17 与 Android SDK 工具路径：
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- `PATH="$JAVA_HOME/bin:$PATH"`
- Wrangler 认证复用本机已授权的 `/Users/luo/Projects/stock/tools/android-updates/.wrangler/auth`（Keychain 模式）。

### 步骤 1：构建 Release 正式签名 APK

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
./gradlew assembleRelease \
  -PandroidVersionCode=<nextCode> \
  -PandroidVersionName=<versionName> \
  -PandroidUpdateUrl=https://yanjiu-android-updates.bojin1229.workers.dev/skip/latest.json
```

产物路径：`app/build/outputs/apk/release/app-release.apk`。

### 步骤 2：准备更新说明并执行冻结 + 上传 + 公网读回校验

创建 `output/android-publish/run-<timestamp>/notes.txt`（2–5 条用户可感知的简短更新说明），然后执行：

```sh
PATH="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH" \
XDG_CONFIG_HOME="/Users/luo/Projects/stock/tools/android-updates/.wrangler/auth" \
CLOUDFLARE_ACCOUNT_ID="6eda59403731501fd84f31b87397b1e9" \
CLOUDFLARE_AUTH_USE_KEYRING=true \
WRANGLER_SEND_METRICS=false \
NODE_USE_ENV_PROXY=1 \
node scripts/android-release.mjs \
  --apk "$PWD/app/build/outputs/apk/release/app-release.apk" \
  --base-url "https://yanjiu-android-updates.bojin1229.workers.dev/skip/" \
  --notes-file "<本次 notes.txt 绝对路径>" \
  --expected-signer "e7978760b9fd7b3a02d0b3272b5a5a27b09f00efdc97a3604f165b6cf5af7ca7" \
  --output "<本次 output/android-publish/run-.../prepared 目录>" \
  --apply \
  --bucket "yanjiu-android-updates"
```

### 步骤 3：同步源码默认构建号

发布并公网读回校验通过后，将 `app/build.gradle.kts` 中的默认 `resolvedVersionCode`（及 `resolvedVersionName`）定点同步为本次实际已发布值。
