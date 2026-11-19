# 轻跳 (Skip)

一款极简、低功耗的 Android 开屏广告跳过工具，基于系统无障碍服务（`AccessibilityService`）实现。

## 下载与安装

前往 [Releases 页面](../../releases/latest) 下载最新的 `app-release.apk` 直接安装。

安装后按应用内引导开启「无障碍服务」即可使用；建议同时在系统电池设置中允许后台运行（忽略电池优化），避免被系统清理。

## 特性

- **低功耗设计**：仅在切换应用后的前 5 秒窗口内检测“跳过”按钮，其余时间保持休眠；无障碍服务独立运行在轻量 `:service` 进程，不常驻加载 Compose UI 内存。
- **零网络权限**：未申请 `INTERNET` 权限，所有规则与跳过记录仅保存在本地，不上传任何数据。
- **内置常用规则**：内置常见国内应用开屏跳过规则（见 `app/src/main/assets/default_rules.json`），支持白名单与自定义规则管理。
- **轻量反馈**：可选开启跳过成功时的轻微震动反馈。

## 权限说明

应用仅申请实现核心功能所需的最小权限：

| 权限 | 用途 |
| :--- | :--- |
| 无障碍服务 (`BIND_ACCESSIBILITY_SERVICE`) | 在应用切换时识别并自动点击开屏页的“跳过”按钮 |
| 读取已安装应用列表 (`QUERY_ALL_PACKAGES`) | 在应用内展示已安装应用，用于管理白名单 |
| 忽略电池优化 (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | 降低后台保活被系统清理的概率 |
| 震动 (`VIBRATE`) | 可选的跳过成功震动反馈 |

## 环境要求与构建

- **JDK**: 17
- **Android SDK**: `compileSdk 36` / `minSdk 26` (Android 8.0+)
- **技术栈**: Kotlin + Jetpack Compose + Material 3

### 本地构建

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（未配置正式签名时自动回退为 debug 签名）
./gradlew assembleRelease
```

### 配置正式 Release 签名

在项目根目录创建 `keystore.properties`（已加入 `.gitignore`，不会提交到仓库）：

```properties
storeFile=release.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

或在 CI 中通过环境变量注入：`KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

### GitHub Actions 自动发布

仓库内置 `.github/workflows/build.yml`：
- 推送到 `main` 或发起 PR 时自动编译 Debug APK 验证构建。
- 推送 `v*` 标签（如 `v1.0.0`）时自动构建 Release APK 并创建 GitHub Release。如需使用正式签名发布，请在仓库 Settings → Secrets 中配置 `SIGNING_KEY`（`.jks` 文件的 Base64 编码）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 免责声明

本项目仅供学习与个人日常辅助使用。请勿将其用于任何违反相关法律法规或第三方服务条款的用途。

## 开源协议

基于 [MIT License](LICENSE) 开源。
