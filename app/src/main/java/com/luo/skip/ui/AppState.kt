package com.luo.skip.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
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
import com.luo.skip.BuildConfig
import com.luo.skip.data.AppRelease
import com.luo.skip.data.MAX_WINDOW_SECONDS
import com.luo.skip.data.MIN_WINDOW_SECONDS
import com.luo.skip.data.SkipBroadcasts
import com.luo.skip.data.SkipConfig
import com.luo.skip.data.SkipFeedback
import com.luo.skip.data.SkipLogData
import com.luo.skip.data.SkipLogEntry
import com.luo.skip.data.SkipServiceState
import com.luo.skip.data.SkipStore
import com.luo.skip.data.SkipUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val preparingInstall: Boolean = false,
    val release: AppRelease? = null,
    val downloaded: File? = null,
    val received: Long = 0,
    val message: String? = null,
) {
    val busy: Boolean get() = checking || downloading || preparingInstall
}

data class ExcludableApp(val packageName: String, val label: String)

/**
 * 主进程 UI 状态：文件为准，IO 线程读写，广播/生命周期触发刷新。
 * 仅在主进程使用，:service 进程不会加载此类。
 */
class AppState(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updater = SkipUpdater(appContext, BuildConfig.UPDATE_MANIFEST_URL)
    private var updateJob: Job? = null

    val updateConfigured: Boolean get() = updater.manifestUrl.isNotEmpty()
    var updateState by mutableStateOf(AppUpdateState())
        private set

    var loaded by mutableStateOf(false)
        private set
    var config by mutableStateOf(SkipConfig())
        private set
    var excludableApps by mutableStateOf<List<ExcludableApp>?>(null)
        private set
    var logs by mutableStateOf(SkipLogData())
        private set
    var customRulesCount by mutableIntStateOf(0)
        private set
    var accessibilityEnabled by mutableStateOf(false)
        private set
    var ignoringBattery by mutableStateOf(false)
        private set

    init {
        if (updateConfigured) {
            updateJob = scope.launch {
                try {
                    updater.restore()?.let { (release, file) ->
                        withContext(Dispatchers.Main) {
                            updateState = updateState.copy(
                                release = release,
                                downloaded = file,
                                received = release.size,
                                message = "安装包已就绪，可继续安装。",
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun refreshExcludableApps() {
        scope.launch {
            val pm = appContext.packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = runCatching { pm.queryIntentActivities(launchIntent, 0) }.getOrDefault(emptyList())
            val apps = activities.mapNotNull { resolved ->
                val info = resolved.activityInfo.applicationInfo ?: return@mapNotNull null
                if (info.packageName == appContext.packageName || info.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    return@mapNotNull null
                }
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                ExcludableApp(info.packageName, label)
            }.distinctBy { it.packageName }.sortedBy { it.label }
            withContext(Dispatchers.Main) { excludableApps = apps }
        }
    }

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
    fun setWindowSeconds(seconds: Int) = updateConfig {
        it.copy(windowSeconds = seconds.coerceIn(MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS))
    }
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

    // ---------- 远程应用内更新 ----------

    fun checkUpdate(onToast: ((String) -> Unit)? = null) {
        if (!updateConfigured) {
            onToast?.invoke("更新服务尚未配置")
            return
        }
        if (updateState.busy) return
        updateState = updateState.copy(
            checking = true,
            release = null,
            downloaded = null,
            received = 0,
            message = null,
        )
        updateJob = scope.launch {
            try {
                val restored = updater.restore()
                val release = updater.check()
                withContext(Dispatchers.Main) {
                    if (release == null) {
                        updateState = updateState.copy(
                            release = null,
                            downloaded = null,
                            received = 0,
                            message = "当前已是最新版本。",
                        )
                        onToast?.invoke("当前已是最新版本")
                    } else if (restored != null && restored.first.versionCode == release.versionCode && restored.first.sha256 == release.sha256) {
                        updateState = updateState.copy(
                            release = release,
                            downloaded = restored.second,
                            received = release.size,
                            message = "安装包已就绪，可继续安装。",
                        )
                    } else {
                        updateState = updateState.copy(
                            release = release,
                            downloaded = null,
                            received = 0,
                            message = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = formatUpdateError(e)
                    updateState = updateState.copy(message = msg)
                    onToast?.invoke(msg)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(checking = false)
                }
            }
        }
    }

    fun downloadUpdate() {
        val current = updateState
        val release = current.release ?: return
        if (current.busy) return
        updateState = current.copy(downloading = true, downloaded = null, received = 0, message = null)
        updateJob = scope.launch {
            try {
                var lastPercent = -1L
                val file = updater.download(release) { count ->
                    val percent = count * 100 / release.size
                    if (percent != lastPercent) {
                        lastPercent = percent
                        scope.launch(Dispatchers.Main) {
                            if (updateState.downloading) {
                                updateState = updateState.copy(received = count)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(
                        downloaded = file,
                        received = release.size,
                        message = "下载完成，安装包已通过校验。",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(message = formatUpdateError(e))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(downloading = false)
                }
            }
        }
    }

    fun cancelUpdateDownload() {
        if (updateState.downloading) {
            updateJob?.cancel()
            updateState = updateState.copy(downloading = false, message = "下载已取消，可重新下载。")
        }
    }

    fun prepareInstallIntent(onReady: (Intent) -> Unit) {
        val current = updateState
        val release = current.release ?: return
        val file = current.downloaded ?: return
        if (current.busy) return
        updateState = current.copy(preparingInstall = true, message = null)
        scope.launch {
            try {
                val intent = updater.installIntent(file, release)
                withContext(Dispatchers.Main) {
                    onReady(intent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(
                        downloaded = null,
                        message = formatUpdateError(e),
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateState = updateState.copy(preparingInstall = false)
                }
            }
        }
    }

    fun setUpdateMessage(msg: String?) {
        updateState = updateState.copy(message = msg)
    }

    fun reportUpdateError(error: Exception) {
        updateState = updateState.copy(message = formatUpdateError(error))
    }

    private fun formatUpdateError(error: Exception): String = when (error) {
        is IllegalArgumentException, is IllegalStateException, is java.io.IOException ->
            error.message?.takeIf { it.any { ch -> ch in '\u4e00'..'\u9fff' } } ?: "更新未完成，请稍后重试。"
        else -> "无法打开安装界面，请稍后重试。"
    }
}

/** 通用系统设置跳转与检测（主进程各页面共用）。 */
object SystemNav {
    val isHonor: Boolean
        get() = Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
            Build.BRAND.equals("HONOR", ignoreCase = true)

    const val honorBackgroundActivityPath =
        "设置 → 应用 → 应用启动管理 → 轻跳。关闭自动管理，开启允许后台活动。"

    fun openHonorBackgroundSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }


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
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refresh()
                state.refreshExcludableApps()
            }
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
