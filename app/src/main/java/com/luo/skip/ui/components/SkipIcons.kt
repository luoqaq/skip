package com.luo.skip.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 轻量手写图标（Feather 风格描边路径），避免引入 material-icons-extended。
 * 全部 24x24 视口、圆头描边；绘制时用 Icon(tint = ...) 上色。
 */
object SkipIcons {

    private fun iconOf(vararg paths: String, strokeWidth: Float = 1.8f): ImageVector {
        val builder = ImageVector.Builder(
            name = "SkipIcon" + paths.joinToString().hashCode(),
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    val Gear: ImageVector = iconOf(
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    )
    val ChevronRight: ImageVector = iconOf("m9 18 6-6-6-6", strokeWidth = 2f)
    val ChevronLeft: ImageVector = iconOf("M15 18l-6-6 6-6", strokeWidth = 2f)
    val Check: ImageVector = iconOf("M20 6 9 17l-5-5", strokeWidth = 3f)
    val Close: ImageVector = iconOf("M18 6 6 18M6 6l12 12", strokeWidth = 3f)
    val Shield: ImageVector = iconOf("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
    val Search: ImageVector = iconOf("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "m20 20-3.5-3.5", strokeWidth = 2f)
    val Bolt: ImageVector = iconOf("M13 2 4 14h7l-1 8 9-12h-7z", strokeWidth = 1.9f)
    val Ban: ImageVector = iconOf("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", "M5.6 5.6l12.8 12.8", strokeWidth = 1.9f)
    val Eye: ImageVector = iconOf(
        "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    )
}
