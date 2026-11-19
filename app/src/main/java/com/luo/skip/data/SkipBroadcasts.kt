package com.luo.skip.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

/**
 * 双进程轻量同步：主进程与 :service 进程之间用非导出本地广播通知文件变更，
 * 真正的数据全部走 filesDir 下的原子 JSON 文件。
 */
object SkipBroadcasts {
    const val ACTION_CONFIG_CHANGED = "com.luo.skip.ACTION_CONFIG_CHANGED"
    const val ACTION_LOG_UPDATED = "com.luo.skip.ACTION_LOG_UPDATED"
    const val ACTION_SERVICE_STATE_CHANGED = "com.luo.skip.ACTION_SERVICE_STATE_CHANGED"

    fun sendConfigChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName))
    }

    fun sendLogUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_LOG_UPDATED).setPackage(context.packageName))
    }

    fun sendServiceStateChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_SERVICE_STATE_CHANGED).setPackage(context.packageName))
    }
}

/** 无障碍服务开启状态检测（纯框架 API，UI 与引擎共用，不涉及 Compose）。 */
object SkipServiceState {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!am.isEnabled) return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }

    /** 检测系统「无障碍快捷方式」是否被意外关闭等异常场景。 */
    fun isAccessibilityFeatureEnabled(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }
}
