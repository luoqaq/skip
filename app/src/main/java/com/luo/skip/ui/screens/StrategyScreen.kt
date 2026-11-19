package com.luo.skip.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.data.MAX_WINDOW_SECONDS
import com.luo.skip.data.MIN_WINDOW_SECONDS
import com.luo.skip.data.SkipStore
import com.luo.skip.ui.AppState
import com.luo.skip.ui.components.ListCardDivider
import com.luo.skip.ui.components.ListRow
import com.luo.skip.ui.components.SectionLabel
import com.luo.skip.ui.components.SkipIcons
import com.luo.skip.ui.components.SkipSwitch
import com.luo.skip.ui.components.SoftCard
import com.luo.skip.ui.components.ToastController
import com.luo.skip.ui.components.ToastHost
import com.luo.skip.ui.theme.LocalSkipColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 05 跳过策略：精准规则 / 通用识别 / 轻微震动 / 导入规则文件。
 */
@Composable
fun StrategyScreen(appState: AppState, onBack: () -> Unit) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    val toast = remember { ToastController() }
    val cfg = appState.config
    var ruleCount by remember { mutableIntStateOf(0) }
    var showWindowDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ruleCount = withContext(Dispatchers.IO) { SkipStore.loadDefaultRules(context).size }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            appState.importRulesFromUri(uri) { count ->
                when {
                    count > 0 -> toast.show("已导入 $count 条规则")
                    count == 0 -> toast.show("文件里没有有效规则")
                    else -> toast.show("文件解析失败，请确认是规则 JSON")
                }
            }
        }
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
                Text("跳过策略", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
                    modifier = Modifier.padding(start = 4.dp))
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    Column(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "精准规则",
                            desc = if (ruleCount > 0) "内置 $ruleCount 个常用应用 · 规则 v1" else "内置常用应用精准规则 · 规则 v1",
                            inset = 18.dp,
                            trailing = {
                                SkipSwitch(checked = cfg.preciseEnabled, onCheckedChange = { appState.setPrecise(it) })
                            },
                        )
                        ListCardDivider()
                        ListRow(
                            title = "通用识别",
                            desc = "规则库里没有的应用，识别屏幕角落的「跳过」按钮",
                            inset = 18.dp,
                            trailing = {
                                SkipSwitch(checked = cfg.genericEnabled, onCheckedChange = { appState.setGeneric(it) })
                            },
                        )
                        ListCardDivider()
                        ListRow(
                            title = "开屏检测时长",
                            desc = "切换应用后在此时间内检测跳过按钮，超时休眠省电",
                            inset = 18.dp,
                            onClick = { showWindowDialog = true },
                            trailing = {
                                Text(
                                    "${cfg.windowSeconds} 秒",
                                    fontSize = 13.sp,
                                    color = c.ink3,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                                Icon(
                                    SkipIcons.ChevronRight,
                                    null,
                                    tint = c.ink3,
                                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            SectionLabel("跳过时提示")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    Column(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "顶部跳过提示",
                            desc = "跳过成功时在屏幕顶部轻提示，可随时隐藏",
                            inset = 18.dp,
                            trailing = {
                                SkipSwitch(checked = cfg.skipToastEnabled, onCheckedChange = { appState.setSkipToast(it) })
                            },
                        )
                        ListCardDivider()
                        ListRow(
                            title = "轻微震动",
                            desc = "跳过成功时震一下，确认它在工作",
                            inset = 18.dp,
                            trailing = {
                                SkipSwitch(checked = cfg.vibrateEnabled, onCheckedChange = { appState.setVibrate(it) })
                            },
                        )
                    }
                }
            }

            SectionLabel("自定义规则")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SoftCard {
                    ListRow(
                        title = "导入规则文件",
                        desc = "选择 .json 文件，与内置规则合并",
                        inset = 18.dp,
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        trailing = {
                            Icon(SkipIcons.ChevronRight, null, tint = c.ink3, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                Text(
                    if (appState.customRulesCount > 0) {
                        "已导入 ${appState.customRulesCount} 条自定义规则。后续的弹窗、青少年模式等规则也会出现在这里。"
                    } else {
                        "尚未导入。后续的弹窗、青少年模式等规则也会出现在这里。"
                    },
                    fontSize = 12.5.sp, color = c.ink2, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
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

    if (showWindowDialog) {
        val presets = listOf(5, 10, 15, 20)
        var inputText by remember(cfg.windowSeconds) { mutableStateOf(cfg.windowSeconds.toString()) }
        val parsed = inputText.toIntOrNull()
        val isValid = parsed != null && parsed in MIN_WINDOW_SECONDS..MAX_WINDOW_SECONDS

        AlertDialog(
            onDismissRequest = { showWindowDialog = false },
            title = { Text("开屏检测时长", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "部分应用冷启动较慢，可适当调长检测窗口（${MIN_WINDOW_SECONDS}–${MAX_WINDOW_SECONDS} 秒）。",
                        fontSize = 13.sp,
                        color = c.ink2,
                        lineHeight = 18.sp,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { sec ->
                            val selected = parsed == sec
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) c.accent else c.surface2)
                                    .clickable { inputText = sec.toString() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${sec}秒",
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) c.accentInk else c.ink,
                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.surface2)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自定义", fontSize = 14.sp, color = c.ink2)
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (inputText.isEmpty()) {
                                Text("${MIN_WINDOW_SECONDS}–${MAX_WINDOW_SECONDS}", fontSize = 14.sp, color = c.ink3)
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { next ->
                                    if (next.length <= 2 && next.all { it.isDigit() }) {
                                        inputText = next
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = TextStyle(
                                    color = c.ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFeatureSettings = "tnum",
                                ),
                                cursorBrush = SolidColor(c.accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("秒", fontSize = 14.sp, color = c.ink3)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isValid,
                    onClick = {
                        val target = parsed ?: return@TextButton
                        appState.setWindowSeconds(target)
                        showWindowDialog = false
                        toast.show("已设为 $target 秒")
                    },
                ) {
                    Text("确定", color = if (isValid) c.accent else c.ink3, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWindowDialog = false }) { Text("取消") }
            },
        )
    }
}
