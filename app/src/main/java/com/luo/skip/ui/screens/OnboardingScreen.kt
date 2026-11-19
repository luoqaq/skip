package com.luo.skip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.ui.AppState
import com.luo.skip.ui.SystemNav
import com.luo.skip.ui.components.AppBadge
import com.luo.skip.ui.components.SkipIcons
import com.luo.skip.ui.components.SoftCard
import com.luo.skip.ui.theme.LocalSkipColors

/**
 * 01 首次引导：3 步进度条（无障碍 → 受限设置（仅开关置灰时）→ 后台保护）。
 * 回到 App 自动检测权限，已开启自动推进。
 */
@Composable
fun OnboardingScreen(appState: AppState, onDone: () -> Unit) {
    val c = LocalSkipColors.current
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(if (appState.accessibilityEnabled) 3 else 1) }

    // PRD 3.1：回到 App 时自动检测权限，已开启则自动推进到后台保护步骤
    LaunchedEffect(appState.accessibilityEnabled, step) {
        if (appState.accessibilityEnabled && step < 3) step = 3
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp),
        ) {
            // 顶部进度条
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (i < step) c.accent else c.surface2),
                    )
                }
            }
            Text(
                "第 $step 步，共 3 步",
                fontSize = 12.5.sp, color = c.ink3,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )

            when (step) {
                1 -> StepAccessibility()
                2 -> StepRestricted()
                else -> StepGuard()
            }

            // 底部按钮区
            Column(Modifier.padding(top = 26.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    1 -> {
                        PrimaryButton("去开启") { SystemNav.openAccessibilitySettings(context) }
                        GhostButton("开关是灰色的？") { step = 2 }
                    }
                    2 -> {
                        PrimaryButton("去允许受限设置") { SystemNav.openAppDetails(context) }
                        GhostButton("返回上一步") { step = 1 }
                    }
                    else -> {
                        PrimaryButton("去设置电池无限制") { SystemNav.requestBatteryIgnore(context) }
                        PrimaryButtonTonal("完成，开始守护") {
                            appState.finishOnboarding()
                            onDone()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepAccessibility() {
    val c = LocalSkipColors.current
    Text("开启无障碍服务", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.ink, letterSpacing = (-0.4).sp)
    Text(
        "轻跳靠系统无障碍识别「跳过」按钮。只在打开应用的前 5 秒工作，其余时间休眠。",
        fontSize = 14.sp, color = c.ink2, lineHeight = 22.sp,
        modifier = Modifier.padding(top = 10.dp),
    )

    // 模拟系统设置列表
    Column(Modifier.padding(top = 26.dp)) {
        SoftCard(corner = 20.dp, color = c.surface2) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "系统设置 › 无障碍 › 已下载的应用",
                    fontSize = 12.sp, color = c.ink3,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppBadge("跳", "com.luo.skip", size = 30.dp, corner = 9.dp, fontSize = 13.sp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("轻跳 · 开屏广告跳过", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
                        Text("已关闭", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(top = 1.dp))
                    }
                    Box(
                        Modifier
                            .size(48.dp, 28.dp)
                            .clip(CircleShape)
                            .background(c.surface2),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .alpha(0.45f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF8A8780)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("T", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("TalkBack", fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }

    // 信任背书
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FactRow("不读取输入内容，不联网")
        FactRow("默认不处理微信、支付宝和银行类应用")
    }
}

@Composable
private fun StepRestricted() {
    val c = LocalSkipColors.current
    Text("开关是灰色的？", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.ink, letterSpacing = (-0.4).sp)
    Text(
        "Android 13 之后，侧载安装的应用默认被标记为「受限」，无障碍开关可能是灰色的。跟着下面做：",
        fontSize = 14.sp, color = c.ink2, lineHeight = 22.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FactRow("打开系统「应用信息」页面，找到轻跳")
        FactRow("点右上角的「⋮」菜单")
        FactRow("选择「允许受限设置」，输入锁屏密码确认")
        FactRow("回到无障碍设置，开关就能打开了")
    }
}

@Composable
private fun StepGuard() {
    val c = LocalSkipColors.current
    Text("别让系统清理后台", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.ink, letterSpacing = (-0.4).sp)
    Text(
        "省电策略可能悄悄关掉轻跳。完成下面几项，守护就能长期稳定运行。",
        fontSize = 14.sp, color = c.ink2, lineHeight = 22.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FactRow("电池：允许轻跳「无限制」后台活动")
        FactRow("自启动：在系统设置里允许轻跳自启动")
        FactRow("多任务：在最近任务里把轻跳卡片下拉锁定")
    }
}

@Composable
private fun FactRow(text: String) {
    val c = LocalSkipColors.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            SkipIcons.Check,
            contentDescription = null,
            tint = c.accent,
            modifier = Modifier.size(16.dp).padding(top = 3.dp),
        )
        Text(text, fontSize = 13.sp, color = c.ink2, lineHeight = 19.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val c = LocalSkipColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(c.accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.accentInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PrimaryButtonTonal(text: String, onClick: () -> Unit) {
    val c = LocalSkipColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .background(c.surface2)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val c = LocalSkipColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
