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
        /** 自绘/无文本控件常只响应真实触摸事件，优先走手势点击。 */
        val preferGesture: Boolean = false,
    )

    // 通用识别的负面词：点了会导致用户进入不想要的流程
    private val NEGATIVE_WORDS = listOf(
        "跳过登录", "跳过引导", "跳过此步", "暂不跳过", "跳过验证",
    )

    // 方案 A：无文本跳过按钮常见的 viewId 关键词（仅匹配小尺寸角落可点击节点）
    private val SKIP_VIEW_ID_KEYWORDS = listOf(
        "skip", "advert", "count_down", "countdown",
    )

    private const val MAX_TRAVERSE_NODES = 200

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
        return genericVisualFallback(root, screenW, screenH)
    }

    /**
     * 无文本兜底识别（A + B 组合）：
     * - 方案 A：角落热区内的小尺寸可点击节点，且 viewId 包含跳过/倒计时/广告特征词；
     * - 方案 B：当前页存在独立的「广告 / AD」角标时，匹配右侧角落热区内的小尺寸可点击节点。
     */
    private fun genericVisualFallback(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): Match? {
        var hasAdBadge = false
        var adBadgeFallbackMatch: Match? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val rect = Rect()

        while (queue.isNotEmpty() && visited < MAX_TRAVERSE_NODES) {
            val node = queue.removeFirst()
            visited++

            if (node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val label = node.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
                    ?: node.contentDescription?.toString()?.trim().takeIf { !it.isNullOrEmpty() }

                if (!hasAdBadge && isAdBadge(label, rect, screenW, screenH)) {
                    hasAdBadge = true
                }

                if (isSmallCornerButton(node, label, rect, screenW, screenH)) {
                    val idPart = node.viewIdResourceName
                        ?.substringAfterLast(":id/")
                        ?.lowercase()
                    if (idPart != null && SKIP_VIEW_ID_KEYWORDS.any { idPart.contains(it) }) {
                        return Match(node, null, generic = true, buttonText = label ?: "跳过(ID)", preferGesture = true)
                    }
                    // 方案 B 仅取右侧角落（右上/右下），避开左上角返回按钮
                    if (adBadgeFallbackMatch == null && rect.centerX() > screenW * 0.60f) {
                        adBadgeFallbackMatch = Match(node, null, generic = true, buttonText = label ?: "跳过(广告页)", preferGesture = true)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return if (hasAdBadge) adBadgeFallbackMatch else null
    }

    private fun isAdBadge(label: String?, rect: Rect, screenW: Int, screenH: Int): Boolean {
        if (label == null || label.length > 4) return false
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.width() >= screenW * 0.4 || rect.height() >= screenH * 0.1) return false
        return label == "广告" || label.equals("AD", ignoreCase = true)
    }

    private fun isSmallCornerButton(
        node: AccessibilityNodeInfo,
        label: String?,
        rect: Rect,
        screenW: Int,
        screenH: Int,
    ): Boolean {
        if (!node.isClickable) return false
        if (label != null && (label.length > 8 || NEGATIVE_WORDS.any { label.contains(it) })) return false
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.width() >= screenW * 0.35 || rect.height() >= screenH * 0.12) return false
        return isInCornerHotZone(rect, screenW, screenH)
    }

    private fun isInCornerHotZone(rect: Rect, screenW: Int, screenH: Int): Boolean {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val verticalHot = cy < screenH * 0.25f || cy > screenH * 0.75f
        val horizontalHot = cx < screenW * 0.40f || cx > screenW * 0.60f
        return verticalHot && horizontalHot
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
        return isInCornerHotZone(rect, screenW, screenH)
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
                val text = it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }
                val desc = it.contentDescription?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() }
                val label = text ?: desc ?: "跳过"
                // 无文本的自绘 View（如倒计时圆环）常只响应坐标触摸而不响应 ACTION_CLICK
                val needGesture = text == null && desc == null
                return Match(it, rule, generic = false, buttonText = label, preferGesture = needGesture)
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
     * 执行点击：
     * 1) 若规则配置了 intentUri（针对校验 MotionEvent.toolType 屏蔽无障碍点击的广告页），直接触发其首页路由；
     * 2) 若 preferGesture=true 则优先走手势点击；
     * 3) 优先节点自身及最多 4 层祖先 ACTION_CLICK，兜底 dispatchGesture。
     */
    fun performClick(service: AccessibilityService, match: Match): Boolean {
        val uri = match.rule?.intentUri
        if (!uri.isNullOrEmpty() && launchIntentUri(service, match.rule.packageName, uri)) {
            return true
        }
        if (match.preferGesture && clickByGesture(service, match.node)) return true
        var node: AccessibilityNodeInfo? = match.node
        var depth = 0
        while (node != null && depth <= 4) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        return clickByGesture(service, match.node)
    }

    private fun launchIntentUri(service: AccessibilityService, packageName: String, uri: String): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                setPackage(packageName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
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
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
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
