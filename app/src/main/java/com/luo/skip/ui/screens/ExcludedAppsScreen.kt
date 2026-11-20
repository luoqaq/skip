package com.luo.skip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.data.DEFAULT_EXCLUDED_WHY
import com.luo.skip.ui.AppState
import com.luo.skip.ui.components.AppBadge
import com.luo.skip.ui.components.ListCardDivider
import com.luo.skip.ui.components.ListRow
import com.luo.skip.ui.components.SectionLabel
import com.luo.skip.ui.components.SkipIcons
import com.luo.skip.ui.components.ToastController
import com.luo.skip.ui.components.ToastHost
import com.luo.skip.ui.theme.LocalSkipColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * 04 排除的应用：搜索 + 分组（已排除 / 最近跳过过 / 其他应用）+ 胶囊按钮切换。
 */
@Composable
fun ExcludedAppsScreen(appState: AppState, onBack: () -> Unit) {
    val c = LocalSkipColors.current
    val toast = remember { ToastController() }
    var query by remember { mutableStateOf("") }
    val apps = appState.excludableApps

    val excluded = appState.config.excludedPackages
    val todaySkipped = remember(appState.logs) {
        val today = LocalDate.now()
        appState.logs.entries
            .filter {
                java.time.Instant.ofEpochMilli(it.timeMillis).atZone(ZoneId.systemDefault()).toLocalDate() == today
            }
            .groupBy { it.packageName }
            .mapValues { it.value.size }
    }

    val filtered = remember(apps, query) {
        apps
            ?.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
    }
    val groups = remember(filtered, excluded, todaySkipped) {
        if (filtered == null) emptyList()
        else listOf(
            "已排除" to filtered.filter { excluded.contains(it.packageName) },
            "最近跳过过" to filtered.filter { !excluded.contains(it.packageName) && todaySkipped.containsKey(it.packageName) },
            "其他应用" to filtered.filter { !excluded.contains(it.packageName) && !todaySkipped.containsKey(it.packageName) },
        ).filter { it.second.isNotEmpty() }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item(key = "header") {
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
                    Text("排除的应用", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                        modifier = Modifier.padding(start = 4.dp))
                }

                Column(Modifier.padding(horizontal = 16.dp)) {
                    // 搜索框（浅色 surface，深色 surface2）
                    val searchBg = if (c.isDark) c.surface2 else c.surface
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(searchBg)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(SkipIcons.Search, null, tint = c.ink3, modifier = Modifier.size(17.dp))
                        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("搜索应用", fontSize = 14.sp, color = c.ink3)
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = c.ink, fontSize = 14.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text(
                        "排除后，打开这些应用时轻跳完全不工作。支付、银行类默认排除，避免付款时误点。",
                        fontSize = 12.5.sp, color = c.ink2, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                    )
                }
            }

            if (filtered == null) {
                item(key = "loading") { Spacer(Modifier.height(40.dp)) }
            } else if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "暂无可显示的应用" else "没有找到「$query」",
                        fontSize = 13.sp, color = c.ink3,
                        modifier = Modifier.padding(top = 40.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                groups.forEach { (title, list) ->
                    item(key = "section_$title") {
                        SectionLabel(title, trailing = {
                            Text(
                                "${list.size}",
                                fontSize = 12.5.sp, color = c.ink3,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                            )
                        })
                    }
                    itemsIndexed(
                        items = list,
                        key = { _, app -> "${title}_${app.packageName}" },
                    ) { index, app ->
                        val isFirst = index == 0
                        val isLast = index == list.lastIndex
                        val shape = RoundedCornerShape(
                            topStart = if (isFirst) 22.dp else 0.dp,
                            topEnd = if (isFirst) 22.dp else 0.dp,
                            bottomStart = if (isLast) 22.dp else 0.dp,
                            bottomEnd = if (isLast) 22.dp else 0.dp,
                        )
                        val isEx = excluded.contains(app.packageName)
                        val why = DEFAULT_EXCLUDED_WHY[app.packageName]
                            ?: todaySkipped[app.packageName]?.let { "今天跳过 $it 次" }
                        Column(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .clip(shape)
                                .background(c.surface),
                        ) {
                            if (!isFirst) ListCardDivider(inset = 64.dp)
                            ListRow(
                                title = app.label,
                                desc = why,
                                inset = 64.dp,
                                leading = { AppBadge(app.label, app.packageName) },
                                trailing = {
                                    ExChip(isEx) { next ->
                                        appState.setExcluded(app.packageName, next)
                                        if (next) toast.show("已排除${app.label}")
                                        else toast.show("${app.label} 恢复跳过")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        ToastHost(
            toast,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun ExChip(excluded: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalSkipColors.current
    val bg = if (excluded) c.ink else c.surface2
    val fg = if (excluded) c.bg else c.ink
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable { onToggle(!excluded) }
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            if (excluded) "已排除" else "排除",
            color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}
