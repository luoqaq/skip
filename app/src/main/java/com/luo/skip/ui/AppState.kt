package com.luo.skip.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.luo.skip.data.SkipBroadcasts
import com.luo.skip.data.SkipConfig
import com.luo.skip.data.SkipFeedback
import com.luo.skip.data.SkipLogData
import com.luo.skip.data.SkipLogEntry
import com.luo.skip.data.SkipServiceState
import com.luo.skip.data.SkipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主进程 UI 状态：文件为准，IO 线程读写，广播/生命周期触发刷新。
 * 仅在主进程使用，:service 进程不会加载此类。
 */
class AppState(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var loaded by mutableStateOf(false)
        private set
    var config by mutableStateOf(SkipConfig())
        private set
    var logs by mutableStateOf(SkipLogData())
        private set
    var customRulesCount by mutableIntStateOf(0)
        private set
    var accessibilityEnabled by mutableStateOf(false)
        private set
    var ignoringBattery by mutableStateOf(false)
        private set

    fun refresh() {
        scope.launch {
            var cfg = SkipStore.loadConfig(appContext)
            val logData = SkipStore.loadLogs(appContext)
            val rulesCount = SkipStore.customRulesCount(appContext)
            val enabled = SkipServiceState.isAccessibilityEnabled(appContext)
            val batteryOk = SystemNav.isIgnoringBattery(appContext)
            // 主进程单向记录 serviceEverEnabled，避免与 :service 并发写 config.json
            if (enabled && !cfg.serviceEverEnabled) {
                cfg = cfg.copy(serviceEverEnabled = true)
                SkipStore.saveConfig(appContext, cfg)
            }
            withContext(Dispatchers.Main) {
                config = cfg
                logs = logData
                customRulesCount = rulesCount
                accessibilityEnabled = enabled
                ignoringBattery = batteryOk
                loaded = true
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // ---------- 配置变更（主进程单向写文件 + 通知 :service 热重载） ----------

    fun updateConfig(transform: (SkipConfig) -> SkipConfig) {
        val next = transform(config)
        config = next
        scope.launch {
            SkipStore.saveConfig(appContext, next)
            SkipBroadcasts.sendConfigChanged(appContext)
        }
    }

    fun togglePaused(paused: Boolean) = updateConfig { it.copy(paused = paused) }
    fun setPrecise(on: Boolean) = updateConfig { it.copy(preciseEnabled = on) }
    fun setGeneric(on: Boolean) = updateConfig { it.copy(genericEnabled = on) }
    fun setVibrate(on: Boolean) = updateConfig { it.copy(vibrateEnabled = on) }
    fun setSkipToast(on: Boolean) = updateConfig { it.copy(skipToastEnabled = on) }
    fun setThemeMode(mode: String) = updateConfig { it.copy(themeMode = mode) }
    fun setDevMode(on: Boolean) = updateConfig { it.copy(devMode = on) }
    fun unlockDevMode() = updateConfig { it.copy(devModeUnlocked = true, devMode = true) }
    fun setAutoStartConfirmed(v: Boolean) = updateConfig { it.copy(autoStartConfirmed = v) }
    fun setLockConfirmed(v: Boolean) = updateConfig { it.copy(lockConfirmed = v) }
    fun finishOnboarding() = updateConfig { it.copy(onboarded = true) }

    fun setExcluded(packageName: String, excluded: Boolean) = updateConfig {
        val set = it.excludedPackages.toMutableSet()
        if (excluded) set += packageName else set -= packageName
        it.copy(excludedPackages = set)
    }

    fun setExcludedAll(packages: Set<String>, excluded: Boolean) = updateConfig {
        val set = it.excludedPackages.toMutableSet()
        if (excluded) set += packages else set -= packages
        it.copy(excludedPackages = set)
    }

    // ---------- 日志 ----------

    fun clearLogs() {
        scope.launch {
            SkipStore.clearLogs(appContext)
            val refreshed = SkipStore.loadLogs(appContext)
            withContext(Dispatchers.Main) { logs = refreshed }
        }
    }

    fun addFeedback(entry: SkipLogEntry) {
        scope.launch {
            SkipStore.appendFeedback(
                appContext,
                SkipFeedback(
                    packageName = entry.packageName,
                    appName = entry.appName,
                    timeMillis = entry.timeMillis,
                    buttonText = entry.buttonText,
                    activityName = entry.activityName,
                    viewId = entry.viewId,
                ),
            )
        }
    }

    fun loadFeedbackText(onResult: (String?) -> Unit) {
        scope.launch {
            val list = SkipStore.loadFeedback(appContext)
            val text = if (list.isEmpty()) null else list.joinToString("\n") { fb ->
                "${SkipStore.formatTime(fb.timeMillis)} ${fb.appName}(${fb.packageName}) 按钮=\"${fb.buttonText}\"" +
                    (fb.activityName?.let { " activity=$it" } ?: "") +
                    (fb.viewId?.let { " viewId=$it" } ?: "")
            }
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    fun importRulesFromUri(uri: Uri, onResult: (Int) -> Unit) {
        scope.launch {
            val jsonText = runCatching {
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val count = if (jsonText == null) {
                -1
            } else {
                try { SkipStore.importRules(appContext, jsonText) } catch (_: Exception) { -1 }
            }
            val latestCount = SkipStore.customRulesCount(appContext)
            if (count > 0) SkipBroadcasts.sendConfigChanged(appContext)
            withContext(Dispatchers.Main) {
                customRulesCount = latestCount
                onResult(count)
            }
        }
    }
}

/** 通用系统设置跳转与检测（主进程各页面共用）。 */
object SystemNav {

    fun isIgnoringBattery(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    fun openAppDetails(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) }
        }
    }

    fun requestBatteryIgnore(context: Context) {
        val pkgUri = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    fun tryOpenAutoStart(context: Context) {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openAppDetails(context)
    }
}

/**
 * 根组件挂载：注册本地广播与生命周期刷新。
 * 返回稳定的 AppState 实例。
 */
@Composable
fun rememberAppState(): AppState {
    val context = LocalContext.current
    val state = remember { AppState(context) }

    DisposableEffect(state) {
        onDispose { state.dispose() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                state.refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(SkipBroadcasts.ACTION_LOG_UPDATED)
            addAction(SkipBroadcasts.ACTION_SERVICE_STATE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return state
}
