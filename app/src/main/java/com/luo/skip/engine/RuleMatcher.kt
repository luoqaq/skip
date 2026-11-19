package com.luo.skip.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.luo.skip.data.SkipConfig
import com.luo.skip.data.SkipRule

/**
 * 两级剪枝匹配器：
 * 1) 精准规则：包名 O(1) 查表，优先 viewId 直达目标节点，退化用文本匹配；
 * 2) 通用识别：屏幕角落热区内的「跳过 / Skip」短文本节点，严格过滤防误触。
 */
object RuleMatcher {

    /** 命中结果。 */
    data class Match(
        val node: AccessibilityNodeInfo,
        val rule: SkipRule?,
        /** true = 通用识别命中。 */
        val generic: Boolean,
        val buttonText: String,
    )

    // 通用识别的负面词：点了会导致用户进入不想要的流程
    private val NEGATIVE_WORDS = listOf(
        "跳过登录", "跳过引导", "跳过此步", "暂不跳过", "跳过验证",
    )

    /** 通用识别总开关入口：在 root 内寻找可点击的跳过节点。 */
    fun genericMatch(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): Match? {
        for (text in listOf("跳过", "Skip")) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                if (!isGenericCandidate(node, screenW, screenH)) continue
                val label = node.text?.toString()?.trim().takeIf { it?.isNotEmpty() == true }
                    ?: node.contentDescription?.toString()?.trim().takeIf { it?.isNotEmpty() == true }
                    ?: text
                return Match(node, null, generic = true, buttonText = label)
            }
        }
        return null
    }

    private fun isGenericCandidate(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> return false
        }
        if (label.length > 8) return false
        if (NEGATIVE_WORDS.any { label.contains(it) }) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        // 非超大容器
        if (rect.width() >= screenW * 0.5) return false
        if (rect.height() >= screenH * 0.15) return false
        // 开屏角落热区：顶部 25% 或底部 25% 的左右边缘
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val verticalHot = cy < screenH * 0.25f || cy > screenH * 0.75f
        val horizontalHot = cx < screenW * 0.40f || cx > screenW * 0.60f
        return verticalHot && horizontalHot
    }

    /** 精准规则：viewId 优先；无 viewId 退化为文本匹配时，同样应用长度、尺寸与热区防误触过滤。 */
    fun preciseMatch(root: AccessibilityNodeInfo, rule: SkipRule, screenW: Int, screenH: Int): Match? {
        if (rule.viewId != null) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(rule.viewId)
            } catch (_: Exception) {
                null
            }
            nodes?.firstOrNull { it.isVisibleToUser }?.let {
                val label = it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }
                    ?: it.contentDescription?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }
                    ?: "跳过"
                return Match(it, rule, generic = false, buttonText = label)
            }
        }
        for (text in rule.texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                if (!isGenericCandidate(node, screenW, screenH)) continue
                val label = node.text?.toString()?.trim().takeIf { it?.isNotEmpty() == true }
                    ?: node.contentDescription?.toString()?.trim().takeIf { it?.isNotEmpty() == true }
                    ?: text
                return Match(node, rule, generic = false, buttonText = label)
            }
        }
        return null
    }

    /** 统一入口：按配置在 root 中找匹配。 */
    fun findMatch(
        root: AccessibilityNodeInfo,
        packageName: String,
        preciseRules: Map<String, List<SkipRule>>,
        config: SkipConfig,
        screenW: Int,
        screenH: Int,
    ): Match? {
        if (config.preciseEnabled) {
            preciseRules[packageName]?.forEach { rule ->
                preciseMatch(root, rule, screenW, screenH)?.let { return it }
            }
        }
        if (config.genericEnabled) {
            genericMatch(root, screenW, screenH)?.let { return it }
        }
        return null
    }

    /**
     * 执行点击：优先节点自身可点击，其次向上找可点击祖先（最多 4 层），
     * 都失败则用 dispatchGesture 点节点中心。
     */
    fun performClick(service: AccessibilityService, match: Match): Boolean {
        var node: AccessibilityNodeInfo? = match.node
        var depth = 0
        while (node != null && depth <= 4) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        return clickByGesture(service, match.node)
    }

    private fun clickByGesture(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) return false
            val x = rect.exactCenterX()
            val y = rect.exactCenterY()
            val path = android.graphics.Path().apply {
                moveTo(x, y)
                lineTo(x + 0.1f, y + 0.1f)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
            service.dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                null,
                null,
            )
        } catch (_: Exception) {
            false
        }
    }
}
