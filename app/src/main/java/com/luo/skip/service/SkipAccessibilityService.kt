package com.luo.skip.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.luo.skip.data.DEFAULT_WINDOW_SECONDS
import com.luo.skip.data.MAX_WINDOW_SECONDS
import com.luo.skip.data.MIN_WINDOW_SECONDS
import com.luo.skip.data.SkipBroadcasts
import com.luo.skip.data.SkipConfig
import com.luo.skip.data.SkipLogEntry
import com.luo.skip.data.SkipStore
import com.luo.skip.engine.RuleMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * :service 独立进程的无障碍服务 —— 低功耗窗口期引擎（默认 10 秒，支持用户自定义）。
 *
 * 事件策略：
 * - 仅当「前台切换为新的非排除 App」时打开检测窗口并立即扫描一次；
 * - 窗口内 TYPE_WINDOW_CONTENT_CHANGED 按 120ms 节流扫描；
 * - 命中或超时立即关闭窗口；休眠期所有事件 O(1) return，CPU 占用趋近 0%。
 */
class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val THROTTLE_MS = 120L
        private const val SYSTEM_UI = "com.android.systemui"
        private const val PULSE_INTERVAL_MS = 220L
        private const val MAX_PULSE_COUNT = 12
        private const val RETRY_DELAY_MS = 450L
        private const val MAX_GESTURE_RETRIES = 4

        /** 各品牌桌面负一屏/全局搜索/系统浮层包名：切桌面或启动应用过渡期会触发窗口事件，调用 rootInActiveWindow 易阻塞 AMS。 */
        private val SYSTEM_OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
            "com.hihonor.hiboard",
            "com.hihonor.search",
            "com.huawei.intelligent",
            "com.huawei.search",
            "com.miui.personalassistant",
            "com.coloros.assistantscreen",
            "com.vivo.hiboard",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 内存态（:service 进程私有，不加载任何 UI） ----------
    @Volatile private var config: SkipConfig = SkipConfig()
    @Volatile private var rules: Map<String, List<com.luo.skip.data.SkipRule>> = emptyMap()

    private var foregroundPkg: String? = null
    private var currentActivity: String? = null
    private var windowOpenedAt = 0L
    private var lastScanAt = 0L
    private var windowOpen = false
    private var receiverRegistered = false

    private var launchers: Set<String>? = null
    private var imePackages: Set<String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bannerView: View? = null
    private var bannerHideTask: Runnable? = null
    private var ttsKeepAlive: android.speech.tts.TextToSpeech? = null
    private var pulseCount = 0
    private val pulseScanTask = object : Runnable {
        override fun run() {
            if (!windowOpen) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - windowOpenedAt > windowDurationMs()) {
                closeWindow()
                return
            }
            lastScanAt = now
            scan()
            if (windowOpen && ++pulseCount < MAX_PULSE_COUNT) {
                mainHandler.postDelayed(this, PULSE_INTERVAL_MS)
            }
        }
    }

    private val configReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { reloadConfig() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        initTtsKeepAlive()
        scope.launch { reloadConfig() }
        if (!receiverRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                configReceiver,
                IntentFilter(SkipBroadcasts.ACTION_CONFIG_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        // 通知主进程服务已连接（主进程单向写 serviceEverEnabled，避免双进程并发写 config.json）
        SkipBroadcasts.sendServiceStateChanged(this)
    }

    override fun onDestroy() {
        releaseTtsKeepAlive()
        removeTopBanner()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(configReceiver) }
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 被系统/用户关闭时通知主进程刷新状态
        SkipBroadcasts.sendServiceStateChanged(this)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private fun reloadConfig() {
        config = SkipStore.loadConfig(this)
        rules = SkipStore.loadAllRules(this).groupBy { it.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val now = android.os.SystemClock.elapsedRealtime()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 系统浮层（通知栏/音量条/负一屏）、输入法及本应用悬浮提示不视为切换前台 App
                if (pkg in SYSTEM_OVERLAY_PACKAGES || isIme(pkg)) return
                if (pkg == packageName && event.className?.toString()?.contains("Activity") != true) return
                event.className?.toString()?.takeIf { it.isNotEmpty() }?.let { currentActivity = it }
                if (pkg == foregroundPkg) {
                    // 同一 App 内窗口切换（如开屏 Activity 是独立窗口），窗口期内继续节流扫描
                    if (windowOpen) {
                        if (now - windowOpenedAt > windowDurationMs()) closeWindow() else throttledScan(now)
                    }
                    return
                }
                // 前台切换到新 App（含桌面/本应用/排除名单）
                foregroundPkg = pkg
                closeWindow()
                if (shouldIgnore(pkg)) return
                if (config.paused) return
                openWindow(now)
                lastScanAt = now
                scan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!windowOpen) return // 休眠期 O(1) 丢弃
                if (now - windowOpenedAt > windowDurationMs()) { closeWindow(); return }
                throttledScan(now)
            }
        }
    }

    private fun windowDurationMs(): Long =
        config.windowSeconds.coerceIn(MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS).toLong() * 1000L

    private fun openWindow(now: Long) {
        windowOpen = true
        windowOpenedAt = now
        lastScanAt = 0L
        pulseCount = 0
        mainHandler.removeCallbacks(pulseScanTask)
        mainHandler.postDelayed(pulseScanTask, PULSE_INTERVAL_MS)
    }

    private fun closeWindow() {
        windowOpen = false
        mainHandler.removeCallbacks(pulseScanTask)
    }

    private fun throttledScan(now: Long) {
        if (now - lastScanAt < THROTTLE_MS) return
        lastScanAt = now
        scan()
    }

    /** 需要忽略的包：本应用、桌面、输入法、系统 UI/负一屏、排除名单。 */
    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg in SYSTEM_OVERLAY_PACKAGES) return true
        if (config.excludedPackages.contains(pkg)) return true
        if (isLauncher(pkg)) return true
        if (isIme(pkg)) return true
        return false
    }

    private fun isLauncher(pkg: String): Boolean {
        val cached = launchers ?: run {
            val set = try {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
                ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
            } catch (_: Exception) {
                emptySet()
            }
            launchers = set
            set
        }
        return pkg in cached
    }

    private fun isIme(pkg: String): Boolean {
        val cached = imePackages ?: run {
            val set = try {
                packageManager.queryIntentServices(Intent("android.view.InputMethod"), 0)
                    .mapTo(mutableSetOf()) { it.serviceInfo.packageName }
            } catch (_: Exception) {
                emptySet()
            }
            imePackages = set
            set
        }
        return pkg in cached
    }

    /** 执行一次扫描匹配，命中即点击 + 记录 + 关窗。 */
    private fun scan() {
        if (config.paused) { closeWindow(); return }
        val pkg = foregroundPkg ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val metrics = resources.displayMetrics
        val start = android.os.SystemClock.elapsedRealtime()
        val match = RuleMatcher.findMatch(root, pkg, rules, config, metrics.widthPixels, metrics.heightPixels)
            ?: return

        val clicked = RuleMatcher.performClick(this, match)
        if (!clicked) return

        val duration = android.os.SystemClock.elapsedRealtime() - start
        closeWindow()
        if (config.vibrateEnabled) vibrateLight()

        val appName = match.rule?.appName ?: try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val activityName = currentActivity
        if (match.preferGesture && match.rule?.intentUri.isNullOrEmpty()) {
            // 自绘控件首次点击可能发生在状态栏收起前或广告就绪前，延后弹窗并静默补点
            scheduleRetryIfStuck(pkg, activityName, attempt = 1)
            if (config.skipToastEnabled) {
                mainHandler.postDelayed({ showTopBanner(appName) }, 300L)
            }
        } else {
            if (config.skipToastEnabled) showTopBanner(appName)
        }
        val viewId = match.node.viewIdResourceName?.takeIf { it.isNotEmpty() }
            ?: if (!match.generic) match.rule?.viewId else null
        val entry = SkipLogEntry(
            packageName = pkg,
            appName = appName,
            timeMillis = System.currentTimeMillis(),
            hitType = if (match.generic) "generic" else "precise",
            buttonText = match.buttonText,
            durationMs = duration,
            activityName = activityName,
            viewId = viewId,
        )
        scope.launch {
            SkipStore.appendLog(this@SkipAccessibilityService, entry)
            SkipBroadcasts.sendLogUpdated(this@SkipAccessibilityService)
        }
    }

    private fun scheduleRetryIfStuck(targetPkg: String, targetActivity: String?, attempt: Int) {
        mainHandler.postDelayed({
            if (config.paused) return@postDelayed
            if (foregroundPkg != targetPkg) return@postDelayed
            if (targetActivity != null && currentActivity != targetActivity) return@postDelayed
            val root = rootInActiveWindow ?: return@postDelayed
            if (root.packageName?.toString() != targetPkg) return@postDelayed

            val metrics = resources.displayMetrics
            val match = RuleMatcher.findMatch(root, targetPkg, rules, config, metrics.widthPixels, metrics.heightPixels)
                ?: return@postDelayed
            val clicked = RuleMatcher.performClick(this, match)
            if (clicked && attempt < MAX_GESTURE_RETRIES) {
                scheduleRetryIfStuck(targetPkg, targetActivity, attempt + 1)
            }
        }, RETRY_DELAY_MS)
    }

    private fun vibrateLight() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /**
     * 建立静默 TextToSpeech 绑定（不发声）：
     * 荣耀/华为 iAware (AwareIntelligentRecg.checkBlind) 仅将同时开启无障碍 (mAccessPkg)
     * 且绑定了 TTS 服务 (mTtsPkg) 的应用识别为无障碍读屏白名单，豁免应用启动时的 3 秒 cgroup 冻结。
     */
    private fun initTtsKeepAlive() {
        if (ttsKeepAlive != null) return
        try {
            ttsKeepAlive = android.speech.tts.TextToSpeech(applicationContext) {}
        } catch (_: Exception) {}
    }

    private fun releaseTtsKeepAlive() {
        val tts = ttsKeepAlive ?: return
        ttsKeepAlive = null
        try { tts.shutdown() } catch (_: Exception) {}
    }

    /** 跳过成功后在屏幕顶部弹出轻量无障碍胶囊提示（零额外权限、零 Compose 依赖）。 */
    private fun showTopBanner(appName: String) {
        mainHandler.post {
            removeTopBanner()
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return@post
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val statusBarH = resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: dp(28)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(24).toFloat()
                    setColor(0xEB1C1D1B.toInt())
                    setStroke(dp(1), 0x24FFFFFF)
                }
                elevation = dp(6).toFloat()
                alpha = 0f
                translationY = -dp(16).toFloat()
            }

            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF3DCB92.toInt())
                }
            }
            container.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            })

            val label = TextView(this).apply {
                text = "已跳过 · $appName"
                setTextColor(0xFFEDEBE6.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            }
            container.addView(label)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarH + dp(10)
            }

            try {
                wm.addView(container, params)
                bannerView = container
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start()

                val hideTask = Runnable {
                    container.animate()
                        .alpha(0f)
                        .translationY(-dp(12).toFloat())
                        .setDuration(180)
                        .withEndAction { removeTopBanner() }
                        .start()
                }
                bannerHideTask = hideTask
                mainHandler.postDelayed(hideTask, 1300)
            } catch (_: Exception) {
                bannerView = null
            }
        }
    }

    private fun removeTopBanner() {
        bannerHideTask?.let { mainHandler.removeCallbacks(it) }
        bannerHideTask = null
        val view = bannerView ?: return
        bannerView = null
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(view) }
    }
}
