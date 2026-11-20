package com.luo.skip.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.ui.AppState
import com.luo.skip.ui.SystemNav
import com.luo.skip.ui.components.ListCardDivider
import com.luo.skip.ui.components.ListRow
import com.luo.skip.ui.components.SectionLabel
import com.luo.skip.ui.components.SkipIcons
import com.luo.skip.ui.components.SkipSwitch
import com.luo.skip.ui.components.SoftCard
import com.luo.skip.ui.components.ToastController
import com.luo.skip.ui.components.ToastHost
import com.luo.skip.ui.theme.LocalSkipColors

/** 按 ROM 识别品牌文案。 */
private fun romLabel(): String {
    val m = Build.MANUFACTURER.lowercase()
    val b = Build.BRAND.lowercase()
    return when {
        m.contains("xiaomi") || b.contains("redmi") || b.contains("xiaomi") || b.contains("poco") -> "小米 HyperOS"
        m.contains("huawei") || b.contains("huawei") -> "华为 HarmonyOS"
        m.contains("honor") || b.contains("honor") -> "荣耀 MagicOS"
        m.contains("oppo") || b.contains("oppo") || m.contains("oneplus") || b.contains("oneplus")
            || b.contains("realme") -> "OPPO / 一加 ColorOS"
        m.contains("vivo") || b.contains("vivo") || b.contains("iqoo") -> "vivo / iQOO OriginOS"
        m.contains("samsung") || b.contains("samsung") -> "三星 One UI"
        else -> "安卓原生系统"
    }
}

/**
 * 06 设置：后台保护（真实电池检测 + 手动确认）、清空记录、关于 + 7 连点开发者模式。
 */
@Composable
fun SettingsScreen(appState: AppState, onBack: () -> Unit) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    val toast = remember { ToastController() }
    val cfg = appState.config
    val batteryOk = appState.ignoringBattery
    val version = remember { "${com.luo.skip.BuildConfig.VERSION_NAME} (${com.luo.skip.BuildConfig.VERSION_CODE})" }
    val upd = appState.updateState

    var showClearDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showLockDemoDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var pendingAutoOpenUpdate by remember { mutableStateOf(false) }
    var devTaps by remember { mutableIntStateOf(0) }

    LaunchedEffect(upd.release, upd.checking) {
        if (pendingAutoOpenUpdate && !upd.checking) {
            pendingAutoOpenUpdate = false
            if (upd.release != null) {
                showUpdateDialog = true
            }
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            appState.setUpdateMessage("已允许安装更新，请点击「安装更新」。")
        } else {
            appState.setUpdateMessage("尚未允许安装更新，开启后可继续安装。")
        }
    }

    val installerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appState.setUpdateMessage("如已完成安装，重新打开即生效；取消后可再次安装。")
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    SkipIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = c.ink2,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onBack() }
                        .padding(9.dp),
                )
                Text("设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                    modifier = Modifier.padding(start = 4.dp))
            }

            SectionLabel("后台保护 · ${romLabel()}", modifier = Modifier.padding(top = 0.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    Column(Modifier.fillMaxWidth()) {
                        // 电池无限制：真实检测
                        ListRow(
                            title = "电池无限制",
                            desc = if (batteryOk) "已检测到" else "未开启",
                            inset = 18.dp,
                            onClick = { if (!batteryOk) SystemNav.requestBatteryIgnore(context) },
                            leading = { CheckDot(ok = batteryOk) },
                        )
                        ListCardDivider()
                        if (SystemNav.isHonor) {
                            ListRow(
                                title = "允许后台活动",
                                desc = SystemNav.honorBackgroundActivityPath,
                                inset = 18.dp,
                                onClick = { SystemNav.openHonorBackgroundSettings(context) },
                            )
                        } else {
                            // 允许自启动：跳转设置 + 手动确认
                            ListRow(
                                title = "允许自启动",
                                desc = if (cfg.autoStartConfirmed) "手动确认 · 系统不提供检测" else "点开系统设置后回来打勾",
                                inset = 18.dp,
                                onClick = {
                                    SystemNav.tryOpenAutoStart(context)
                                    if (!cfg.autoStartConfirmed) {
                                        appState.setAutoStartConfirmed(true)
                                        toast.show("已在系统设置里允许后记为已确认")
                                    }
                                },
                                leading = { CheckDot(ok = cfg.autoStartConfirmed) },
                            )
                        }
                        ListCardDivider()
                        // 多任务锁定：看演示 + 手动确认
                        ListRow(
                            title = "多任务中锁定",
                            desc = "最近任务里下拉轻跳卡片",
                            inset = 18.dp,
                            onClick = { showLockDemoDialog = true },
                            leading = { CheckDot(ok = cfg.lockConfirmed) },
                            trailing = {
                                Text("看演示", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                            },
                        )
                    }
                }
            }

            SectionLabel("外观")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    ListRow(
                        title = "主题模式",
                        desc = when (cfg.themeMode) {
                            "light" -> "浅色模式"
                            "dark" -> "深色模式"
                            else -> "跟随系统"
                        },
                        inset = 18.dp,
                        onClick = { showThemeDialog = true },
                        trailing = {
                            Icon(SkipIcons.ChevronRight, null, tint = c.ink3, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            SectionLabel("数据")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    ListRow(
                        title = "清空跳过记录",
                        inset = 18.dp,
                        onClick = { showClearDialog = true },
                    )
                }
                if (cfg.devMode) {
                    Spacer(Modifier.height(10.dp))
                    SoftCard {
                        ListRow(
                            title = "导出误跳日志",
                            desc = "复制「这次跳错了」的现场记录，用于迭代规则",
                            inset = 18.dp,
                            onClick = {
                                appState.loadFeedbackText { text ->
                                    if (text == null) {
                                        toast.show("还没有误跳记录")
                                    } else {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("skip-feedback", text))
                                        toast.show("已复制到剪贴板")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            SectionLabel("关于")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    Column(Modifier.fillMaxWidth()) {
                        // 版本行：7 连点解锁开发者模式
                        ListRow(
                            title = "版本",
                            inset = 18.dp,
                            onClick = {
                                if (cfg.devModeUnlocked) {
                                    toast.show("已处于开发者模式")
                                } else {
                                    devTaps++
                                    when {
                                        devTaps >= 7 -> {
                                            appState.unlockDevMode()
                                            toast.show("已开启开发者模式")
                                        }
                                        devTaps >= 3 -> toast.show("再点 ${7 - devTaps} 次进入开发者模式")
                                    }
                                }
                            },
                            trailing = {
                                Text(
                                    version, fontSize = 12.5.sp, color = c.ink3,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                            },
                        )
                        ListCardDivider()
                        ListRow(
                            title = "检查更新",
                            desc = when {
                                !appState.updateConfigured -> "更新服务尚未配置"
                                upd.checking -> "正在检查更新…"
                                upd.downloading -> "正在下载更新 ${(upd.received * 100 / (upd.release?.size ?: 1L)).coerceIn(0, 100)}%"
                                upd.downloaded != null && upd.release != null -> "新版本 ${upd.release.versionName} (${upd.release.versionCode}) 已就绪，点击安装"
                                upd.release != null -> "发现新版本 ${upd.release.versionName} (${upd.release.versionCode})"
                                upd.message != null -> upd.message
                                else -> "从云端通道检查并下载新版本"
                            },
                            inset = 18.dp,
                            onClick = {
                                if (upd.release != null) {
                                    showUpdateDialog = true
                                } else if (!upd.busy) {
                                    pendingAutoOpenUpdate = true
                                    appState.checkUpdate { msg -> toast.show(msg) }
                                }
                            },
                            trailing = {
                                if (upd.release != null) {
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(c.accentSoft)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            if (upd.downloaded != null) "可安装" else "有新版",
                                            fontSize = 11.5.sp,
                                            color = c.accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                } else {
                                    Icon(SkipIcons.ChevronRight, null, tint = c.ink3, modifier = Modifier.size(16.dp))
                                }
                            },
                        )
                        ListCardDivider()
                        ListRow(
                            title = "隐私说明",
                            inset = 18.dp,
                            onClick = { showPrivacyDialog = true },
                            trailing = {
                                Icon(SkipIcons.ChevronRight, null, tint = c.ink3, modifier = Modifier.size(16.dp))
                            },
                        )
                        if (cfg.devModeUnlocked) {
                            ListCardDivider()
                            ListRow(
                                title = "开发者模式",
                                desc = "显示命中节点、耗时与误跳导出",
                                inset = 18.dp,
                                trailing = {
                                    SkipSwitch(checked = cfg.devMode, onCheckedChange = { appState.setDevMode(it) })
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        ToastHost(
            toast,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空跳过记录？", fontWeight = FontWeight.SemiBold) },
            text = { Text("今日与累计计数也会一起归零，这个操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    appState.clearLogs()
                    showClearDialog = false
                    toast.show("已清空")
                }) { Text("清空", color = c.warn) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("隐私说明", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "轻跳的无障碍权限只用来识别屏幕上的「跳过」按钮并自动点击：\n\n" +
                        "· 不读取输入内容，不采集任何数据\n" +
                        "· 后台无障碍服务 100% 本地离线运行；网络权限仅用于你手动点击「检查更新」时下载安装包\n" +
                        "· 跳过记录、误跳反馈全部保存在本机，可随时清空\n" +
                        "· 默认排除微信、支付宝和银行类应用"
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("知道了") }
            },
        )
    }

    if (showUpdateDialog && upd.release != null) {
        val release = upd.release
        val sizeMb = String.format(Locale.CHINA, "%.2f", release.size / 1024.0 / 1024.0)
        AlertDialog(
            onDismissRequest = { if (!upd.downloading) showUpdateDialog = false },
            title = {
                Text("发现新版本 ${release.versionName} (${release.versionCode})", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "安装包大小：$sizeMb MB · 覆盖安装保留所有配置与记录",
                        fontSize = 12.5.sp,
                        color = c.ink3,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                    if (release.notes.isNotBlank()) {
                        Text(
                            release.notes,
                            fontSize = 14.sp,
                            color = c.ink,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (upd.downloading) {
                        val progress = (upd.received.toFloat() / release.size.coerceAtLeast(1L)).coerceIn(0f, 1f)
                        val percent = (upd.received * 100 / release.size.coerceAtLeast(1L)).coerceIn(0, 100)
                        LinearProgressIndicator(
                            progress = { progress },
                            color = c.accent,
                            trackColor = c.surface2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                        Text(
                            "正在下载 $percent%",
                            fontSize = 12.5.sp,
                            color = c.ink2,
                            modifier = Modifier.padding(top = 6.dp),
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                    }
                    upd.message?.let { msg ->
                        Text(
                            msg,
                            fontSize = 12.5.sp,
                            color = c.ink2,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    upd.downloading -> {
                        TextButton(onClick = { appState.cancelUpdateDownload() }) {
                            Text("取消下载", color = c.warn)
                        }
                    }
                    upd.downloaded == null -> {
                        TextButton(
                            enabled = !upd.busy,
                            onClick = { appState.downloadUpdate() },
                        ) {
                            Text("下载更新", color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    else -> {
                        TextButton(
                            enabled = !upd.busy,
                            onClick = {
                                try {
                                    if (!context.packageManager.canRequestPackageInstalls()) {
                                        installPermissionLauncher.launch(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:${context.packageName}"),
                                            )
                                        )
                                    } else {
                                        appState.prepareInstallIntent { intent ->
                                            try {
                                                installerLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                appState.reportUpdateError(e)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    appState.reportUpdateError(e)
                                }
                            },
                        ) {
                            Text(
                                if (upd.preparingInstall) "正在核验…" else "安装更新",
                                color = c.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            },
            dismissButton = {
                if (!upd.downloading) {
                    Row {
                        if (upd.downloaded != null) {
                            TextButton(
                                enabled = !upd.busy,
                                onClick = {
                                    pendingAutoOpenUpdate = true
                                    appState.checkUpdate { msg -> toast.show(msg) }
                                },
                            ) {
                                Text("重新检查", color = c.ink2)
                            }
                        }
                        TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
                    }
                }
            },
        )
    }

    if (showLockDemoDialog) {
        AlertDialog(
            onDismissRequest = { showLockDemoDialog = false },
            title = { Text("多任务中锁定", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "① 打开系统「最近任务」\n" +
                        "② 找到轻跳的卡片\n" +
                        "③ 长按或下拉卡片，点「锁定」\n\n锁定后系统就不会顺手清掉轻跳。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    appState.setLockConfirmed(true)
                    showLockDemoDialog = false
                    toast.show("已记为已锁定")
                }) { Text("已锁定") }
            },
            dismissButton = {
                TextButton(onClick = { showLockDemoDialog = false }) { Text("知道了") }
            },
        )
    }

    if (showThemeDialog) {
        val options = listOf(
            "system" to "跟随系统",
            "light" to "浅色模式",
            "dark" to "深色模式",
        )
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题模式", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { idx, (mode, label) ->
                        val selected = cfg.themeMode == mode
                        if (idx > 0) ListCardDivider(inset = 0.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appState.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) c.accent else c.ink,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(SkipIcons.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun CheckDot(ok: Boolean) {
    val c = LocalSkipColors.current
    when {
        ok -> Box(
            Modifier.size(22.dp).clip(CircleShape).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Icon(SkipIcons.Check, null, tint = c.accent, modifier = Modifier.size(13.dp)) }
        else -> Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(c.surface2),
            contentAlignment = Alignment.Center,
        ) { Icon(SkipIcons.Close, null, tint = c.ink3, modifier = Modifier.size(12.dp)) }
    }
}
