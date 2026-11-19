package com.luo.skip.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luo.skip.ui.theme.LocalSkipColors
import kotlinx.coroutines.delay

// ---------- 柔和阴影卡片（浅色弥散阴影，深色无阴影靠色差） ----------

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    color: Color? = null,
    corner: Dp = 22.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = LocalSkipColors.current
    val bg = color ?: c.surface
    val shadowMod = if (!c.isDark) {
        Modifier.shadow(
            elevation = 10.dp,
            shape = RoundedCornerShape(corner),
            ambientColor = Color(0x0A1E1C14),
            spotColor = Color(0x141E1C14),
        )
    } else Modifier
    Box(
        modifier
            .then(shadowMod)
            .clip(RoundedCornerShape(corner))
            .background(bg),
    ) {
        Column(content = content)
    }
}

// ---------- 胶囊开关 ----------

@Composable
fun SkipSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSkipColors.current
    val track by animateColorAsState(
        if (checked) c.accent else c.surface2,
        animationSpec = tween(200), label = "track",
    )
    val offset by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        animationSpec = spring(stiffness = 500f), label = "thumb",
    )
    val thumbColor = when {
        c.isDark && checked -> c.accentInk
        c.isDark -> Color(0xFFD9D7D1)
        else -> Color.White
    }
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = 3.dp + offset)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

// ---------- 应用字母徽标 ----------

private val BADGE_COLORS = listOf(
    Color(0xFF111111), Color(0xFF12A06B), Color(0xFF1677FF), Color(0xFFFB7299),
    Color(0xFFFF5000), Color(0xFFE1251B), Color(0xFFFF2442), Color(0xFF1D7BFF),
    Color(0xFF0084FF), Color(0xFFE60026), Color(0xFF7A5AF8), Color(0xFFD7000F),
    Color(0xFF8A6BE0), Color(0xFF2F6FED), Color(0xFF0AA072), Color(0xFFC2410C),
)

fun badgeColor(packageName: String): Color =
    BADGE_COLORS[(packageName.hashCode() and 0x7fffffff) % BADGE_COLORS.size]

fun badgeLetter(name: String): Char = name.firstOrNull { !it.isWhitespace() }?.uppercaseChar() ?: '?'

@Composable
fun AppBadge(name: String, packageName: String, size: Dp = 36.dp, corner: Dp = 10.dp, fontSize: TextUnit = 15.sp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(badgeColor(packageName)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            badgeLetter(name).toString(),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------- 列表行（带左缩进分隔线） ----------

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color? = null,
    desc: String? = null,
    descColor: Color? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    inset: Dp = 64.dp,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalSkipColors.current
    if (divider) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = inset)
                .height(0.75.dp)
                .background(c.line),
        )
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = if (leading != null) 12.dp else 0.dp,
                    end = if (trailing != null) 14.dp else 0.dp,
                ),
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor ?: c.ink,
            )
            if (desc != null) {
                Text(
                    desc,
                    fontSize = 12.5.sp,
                    color = descColor ?: c.ink3,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
fun ListCardDivider(inset: Dp = 18.dp) {
    val c = LocalSkipColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.75.dp)
            .background(c.line),
    )
}

// ---------- 分组标题 ----------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalSkipColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.5.sp, color = c.ink3, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ---------- 呼吸状态点（尊重系统减弱动画） ----------

@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduced = remember {
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }
    Box(modifier.size(9.dp)) {
        if (!reduced) {
            val transition = rememberInfiniteTransition(label = "breathe")
            val scale by transition.animateFloat(
                initialValue = 0.6f, targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "scale",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.25f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "alpha",
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(19.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

// ---------- 轻量胶囊 Toast（支持撤销动作） ----------

class ToastController {
    var message by mutableStateOf<String?>(null)
        private set
    var actionText by mutableStateOf<String?>(null)
        private set
    var onAction by mutableStateOf<(() -> Unit)?>(null)
        private set
    /** 变更序号，ToastHost 用作自动隐藏计时依据。 */
    var seq = 0
        private set

    fun show(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        seq++
        message = msg
        actionText = action
        this.onAction = onAction
    }

    fun dismiss() {
        seq++
        message = null
        actionText = null
        onAction = null
    }
}

@Composable
fun ToastHost(controller: ToastController, modifier: Modifier = Modifier) {
    val c = LocalSkipColors.current
    val msg = controller.message
    LaunchedEffect(msg, controller.seq) {
        if (msg != null) {
            delay(2400)
            controller.dismiss()
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = msg != null,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(c.ink)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg ?: "", color = c.bg, fontSize = 13.sp)
                controller.actionText?.let { action ->
                    Text(
                        action,
                        color = c.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clickable {
                                val cb = controller.onAction
                                controller.dismiss()
                                cb?.invoke()
                            },
                    )
                }
            }
        }
    }
}
