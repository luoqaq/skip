package com.luo.skip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.data.SkipLogEntry
import com.luo.skip.ui.AppState
import com.luo.skip.ui.SystemNav
import com.luo.skip.ui.components.AppBadge
import com.luo.skip.ui.components.BreathingDot
import com.luo.skip.ui.components.ListCardDivider
import com.luo.skip.ui.components.ListRow
import com.luo.skip.ui.components.SectionLabel
import com.luo.skip.ui.components.SkipIcons
import com.luo.skip.ui.components.SkipSwitch
import com.luo.skip.ui.components.SoftCard
import com.luo.skip.ui.components.ToastController
import com.luo.skip.ui.components.ToastHost
import com.luo.skip.ui.theme.LocalSkipColors
import java.time.LocalDate
import java.time.ZoneId

/** 首页 4 态状态机。 */
private enum class HeroState { Unauth, On, Paused, Broken }

/**
 * 02 首页 + 03 跳过详情 Bottom Sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appState: AppState,
    onOpenSettings: () -> Unit,
    onOpenExcluded: () -> Unit,
    onOpenStrategy: () -> Unit,
) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    val toast = remember { ToastController() }
    var detailEntry by remember { mutableStateOf<SkipLogEntry?>(null) }

    val hero = when {
        !appState.accessibilityEnabled && !appState.config.serviceEverEnabled -> HeroState.Unauth
        !appState.accessibilityEnabled -> HeroState.Broken
        appState.config.paused -> HeroState.Paused
        else -> HeroState.On
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
                Text("轻跳", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                    modifier = Modifier.weight(1f))
                Icon(
                    SkipIcons.Gear,
                    contentDescription = "设置",
                    tint = c.ink2,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onOpenSettings() }
                        .padding(9.dp),
                )
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                HeroCard(hero, appState, toast)
            }

            // 后台保护卡
            if (hero != HeroState.Unauth) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    GuardCard(hero, appState, onOpenSettings, toast)
                }
            }

            // 双功能卡片
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tile(
                    title = "排除的应用",
                    desc = "${appState.config.excludedPackages.size} 个应用",
                    icon = { Icon(SkipIcons.Ban, null, tint = c.accent, modifier = Modifier.size(18.dp)) },
                    onClick = onOpenExcluded,
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    title = "跳过策略",
                    desc = strategySummary(appState),
                    icon = { Icon(SkipIcons.Bolt, null, tint = c.accent, modifier = Modifier.size(18.dp)) },
                    onClick = onOpenStrategy,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionLabel("最近跳过")
            Column(Modifier.padding(horizontal = 16.dp)) {
                RecentSection(appState, hero) { entry -> detailEntry = entry }
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

        // 03 跳过详情
        detailEntry?.let { entry ->
            ModalBottomSheet(
                onDismissRequest = { detailEntry = null },
                containerColor = c.surface,
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = null,
            ) {
                DetailSheetContent(
                    entry = entry,
                    appState = appState,
                    toast = toast,
                    onClose = { detailEntry = null },
                )
            }
        }
    }
}

private fun strategySummary(appState: AppState): String {
    val p = appState.config.preciseEnabled
    val g = appState.config.genericEnabled
    return when {
        p && g -> "精准 + 通用"
        p -> "仅精准"
        g -> "仅通用"
        else -> "已关闭"
    }
}

// ---------- Hero 状态卡 ----------

@Composable
private fun HeroCard(hero: HeroState, appState: AppState, toast: ToastController) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    val logs = appState.logs

    fun openAccessibility() {
        SystemNav.openAccessibilitySettings(context)
    }

    when (hero) {
        HeroState.Unauth -> SoftCard {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c.ink3))
                    Text("尚未开启", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                        modifier = Modifier.padding(start = 9.dp))
                }
                Text("开启无障碍服务后，开屏广告会被自动跳过。",
                    fontSize = 13.sp, color = c.ink2, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(c.accent)
                        .clickable { openAccessibility() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("去开启", color = c.accentInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        HeroState.On -> SoftCard(
            color = Color.Transparent,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(c.accentSoft, c.surface),
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BreathingDot(c.accent)
                            Text("守护中", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                                modifier = Modifier.padding(start = 9.dp))
                        }
                        Text("开屏广告会被自动跳过", fontSize = 13.sp, color = c.ink2,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    SkipSwitch(checked = true, onCheckedChange = {
                        appState.togglePaused(true)
                        toast.show("已暂停，随时可以恢复")
                    })
                }
                Spacer(Modifier.height(26.dp))
                MetricsRow(logs.todayCount, logs.totalCount, dimmed = false)
            }
        }

        HeroState.Paused -> SoftCard {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(c.ink3))
                            Text("已暂停", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                                modifier = Modifier.padding(start = 9.dp))
                        }
                        Text("开屏广告暂时不会被跳过", fontSize = 13.sp, color = c.ink2,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    SkipSwitch(checked = false, onCheckedChange = {
                        appState.togglePaused(false)
                        toast.show("已开启 · 守护中")
                    })
                }
                Spacer(Modifier.height(26.dp))
                MetricsRow(logs.todayCount, logs.totalCount, dimmed = true)
            }
        }

        HeroState.Broken -> SoftCard(color = c.warnSoft) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c.warn))
                    Text("服务被系统关闭了", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.warn,
                        modifier = Modifier.padding(start = 9.dp))
                }
                Text("通常是被省电策略清理了后台。重新开启，并完成下方的后台保护。",
                    fontSize = 13.sp, color = c.ink2, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(CircleShape)
                        .background(c.warn)
                        .clickable { openAccessibility() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("重新开启", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(today: Int, total: Long, dimmed: Boolean) {
    val c = LocalSkipColors.current
    val numColor = if (dimmed) c.ink3 else c.ink
    val tnum = TextStyle(fontFeatureSettings = "tnum")
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(
                "$today",
                fontSize = 60.sp, fontWeight = FontWeight.SemiBold,
                color = numColor, letterSpacing = (-2.1).sp, lineHeight = 56.sp,
                style = tnum,
            )
            Text("今日跳过", fontSize = 12.5.sp, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatTotal(total), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = numColor, style = tnum,
            )
            Text("累计", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun formatTotal(total: Long): String =
    String.format(java.util.Locale.US, "%,d", total)

// ---------- 后台保护卡 ----------

@Composable
private fun GuardCard(
    hero: HeroState,
    appState: AppState,
    onOpenSettings: () -> Unit,
    toast: ToastController,
) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    val cfg = appState.config
    val batteryOk = appState.ignoringBattery
    val allOk = !SystemNav.isHonor && batteryOk && cfg.autoStartConfirmed && cfg.lockConfirmed

    if (hero == HeroState.Broken || !allOk) {
        // 展开的检查清单
        SoftCard {
            Column(Modifier.fillMaxWidth()) {
                CheckRow(
                    title = "电池无限制",
                    desc = if (batteryOk) "已检测到" else "未开启",
                    ok = batteryOk,
                    trailing = {
                        if (!batteryOk) Text(
                            "去设置", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold,
                        )
                    },
                    onClick = if (!batteryOk) {
                        { SystemNav.requestBatteryIgnore(context) }
                    } else null,
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
                    CheckRow(
                        title = "允许自启动",
                        desc = if (cfg.autoStartConfirmed) "手动确认 · 系统不提供检测" else "未确认",
                        ok = cfg.autoStartConfirmed,
                        trailing = {
                            if (!cfg.autoStartConfirmed) Text(
                                "去设置", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold,
                            )
                        },
                        onClick = {
                            SystemNav.tryOpenAutoStart(context)
                            appState.setAutoStartConfirmed(true)
                            toast.show("已在系统设置里允许后打勾")
                        },
                    )
                }
                ListCardDivider()
                CheckRow(
                    title = "多任务中锁定",
                    desc = "最近任务里下拉轻跳卡片",
                    ok = cfg.lockConfirmed,
                    trailing = {
                        Text(
                            "看演示",
                            fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold,
                        )
                    },
                    onClick = {
                        if (!cfg.lockConfirmed) {
                            appState.setLockConfirmed(true)
                            toast.show("在最近任务里把轻跳下拉锁定后打个勾")
                        } else {
                            toast.show("最近任务里下拉轻跳卡片即可锁定")
                        }
                    },
                )
            }
        }
    } else {
        // 折叠单行
        SoftCard {
            ListRow(
                title = "后台保护已就绪",
                leading = {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(c.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(SkipIcons.Check, null, tint = c.accent, modifier = Modifier.size(13.dp))
                    }
                },
                inset = 18.dp,
                onClick = onOpenSettings,
                trailing = {
                    Text(
                        "3/3", fontSize = 12.5.sp, color = c.ink3,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                    Icon(
                        SkipIcons.ChevronRight, null, tint = c.ink3,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    desc: String?,
    ok: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalSkipColors.current
    ListRow(
        title = title,
        desc = desc,
        inset = 18.dp,
        onClick = onClick,
        leading = {
            when {
                ok -> Box(
                    Modifier.size(22.dp).clip(CircleShape).background(c.accentSoft),
                    contentAlignment = Alignment.Center,
                ) { Icon(SkipIcons.Check, null, tint = c.accent, modifier = Modifier.size(13.dp)) }
                else -> Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c.warnSoft),
                    contentAlignment = Alignment.Center,
                ) { Icon(SkipIcons.Close, null, tint = c.warn, modifier = Modifier.size(12.dp)) }
            }
        },
        trailing = { trailing?.invoke() },
    )
}

// ---------- 双功能卡片 ----------

@Composable
private fun Tile(
    title: String,
    desc: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSkipColors.current
    SoftCard(modifier) {
        Column(
            Modifier
                .clickable { onClick() }
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                modifier = Modifier.padding(top = 12.dp))
            Text(desc, fontSize = 12.5.sp, color = c.ink3, modifier = Modifier.padding(top = 2.dp),
                style = TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

// ---------- 最近跳过 ----------

private data class MergedLog(
    val packageName: String,
    val appName: String,
    val count: Int,
    val latest: SkipLogEntry,
)

private data class DayGroup(val label: String, val items: List<MergedLog>)

@Composable
private fun RecentSection(appState: AppState, hero: HeroState, onOpen: (SkipLogEntry) -> Unit) {
    val c = LocalSkipColors.current
    val entries = appState.logs.entries

    if (hero == HeroState.Unauth || entries.isEmpty()) {
        SoftCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(c.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SkipIcons.Shield, null, tint = c.ink3, modifier = Modifier.size(26.dp))
                }
                Text(
                    "还没有跳过记录\n开启后，打开任意带开屏广告的应用试试",
                    fontSize = 13.sp, color = c.ink3, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        return
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val groups = remember(entries) {
        // 先按天分桶，再在天内同应用合并（保留最新一条作为代表）
        val byDay = java.util.TreeMap<LocalDate, java.util.LinkedHashMap<String, Pair<Int, SkipLogEntry>>>(
            compareByDescending { it }
        )
        for (e in entries) {
            val day = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone).toLocalDate()
            val bucket = byDay.getOrPut(day) { LinkedHashMap() }
            val cur = bucket[e.packageName]
            bucket[e.packageName] = if (cur == null) 1 to e else (cur.first + 1) to cur.second
        }
        byDay.map { (day, bucket) ->
            val label = when (day) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "${day.monthValue}月${day.dayOfMonth}日"
            }
            DayGroup(label, bucket.values.map { MergedLog(it.second.packageName, it.second.appName, it.first, it.second) })
        }
    }

    for (group in groups) {
        Text(
            group.label,
            fontSize = 12.sp, color = c.ink3,
            modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 4.dp),
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        SoftCard {
            Column(Modifier.fillMaxWidth()) {
                group.items.forEachIndexed { index, item ->
                    if (index > 0) ListCardDivider(inset = 64.dp)
                    ListRow(
                        title = item.appName,
                        inset = 64.dp,
                        leading = { AppBadge(item.appName, item.packageName) },
                        onClick = { onOpen(item.latest) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.count > 1) {
                                    Text(
                                        "×${item.count}",
                                        fontSize = 13.sp, color = c.ink3, fontWeight = FontWeight.Normal,
                                        style = TextStyle(fontFeatureSettings = "tnum"),
                                    )
                                }
                                Text(
                                    com.luo.skip.data.SkipStore.formatTime(item.latest.timeMillis),
                                    fontSize = 12.5.sp, color = c.ink3,
                                    modifier = Modifier.padding(start = 10.dp),
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------- 03 详情 Sheet ----------

@Composable
private fun DetailSheetContent(
    entry: SkipLogEntry,
    appState: AppState,
    toast: ToastController,
    onClose: () -> Unit,
) {
    val c = LocalSkipColors.current
    val dev = appState.config.devMode
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val entryDay = java.time.Instant.ofEpochMilli(entry.timeMillis).atZone(zone).toLocalDate()
    val dayLabel = when (entryDay) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${entryDay.monthValue}月${entryDay.dayOfMonth}日"
    }
    val count = appState.logs.entries.count {
        it.packageName == entry.packageName &&
            java.time.Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() == entryDay
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 30.dp)) {
        Box(
            Modifier
                .size(36.dp, 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.ink3.copy(alpha = 0.5f))
                .align(Alignment.CenterHorizontally),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 18.dp),
        ) {
            AppBadge(entry.appName, entry.packageName, size = 52.dp, corner = 14.dp, fontSize = 22.sp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(entry.appName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(
                    "$dayLabel " + com.luo.skip.data.SkipStore.formatTime(entry.timeMillis) +
                        if (count > 1) " · 当日第 $count 次" else "",
                    fontSize = 13.sp, color = c.ink3,
                    modifier = Modifier.padding(top = 3.dp),
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }

        Column(Modifier.padding(top = 16.dp)) {
            KvRow("命中方式", if (entry.hitType == "generic") "通用识别" else "精准规则")
            KvRow("按钮文本", entry.buttonText.ifEmpty { "跳过" })
            KvRow("耗时", "${entry.durationMs} ms")
            if (dev) {
                KvRow("包名", entry.packageName)
                KvRow("Activity", entry.activityName ?: "—")
                KvRow("控件 viewId", entry.viewId ?: "—")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(c.surface2)
                    .clickable {
                        appState.addFeedback(entry)
                        toast.show("已记录，感谢反馈")
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("这次跳错了", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(c.surface2)
                    .clickable {
                        appState.setExcluded(entry.packageName, true)
                        toast.show("已排除${entry.appName}", action = "撤销") {
                            appState.setExcluded(entry.packageName, false)
                        }
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("不再跳过${entry.appName}", color = c.warn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun KvRow(key: String, value: String) {
    val c = LocalSkipColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, fontSize = 14.sp, color = c.ink2, modifier = Modifier.weight(1f))
        Text(
            value, fontSize = 14.sp, color = c.ink,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
    Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.line))
}
