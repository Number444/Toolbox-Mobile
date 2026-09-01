package com.four.toolboxmobile.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.four.toolboxmobile.ui.theme.ToolboxColors

/**
 * Toolbox 锚点下拉弹层（**公共样式**，供全部设置项/下拉选择复用）。
 *
 * 视觉与动效（对齐 HyperOS 风格下拉菜单）：
 * - 锚定被点击控件右侧的值文本：弹层右上角对齐它并盖住文本
 * - 进入（370ms，2026-09-01 Four 要求提速 100ms）：模糊（API 31+ 半径 20，前 65% 保持、末段消退）+ 渐变淡入 + 从右上角向下、向左拉伸放大
 * - 退出（310ms）：动画倒放
 *
 * 用法：把调用方包在 Box 里，Popup 会自动以调用处布局为锚点——
 * ```
 * Box {
 *     SettingsRow(..., onClick = { expanded = true })
 *     ToolboxDropdownPopup(expanded, onDismissRequest = { expanded = false }) { dismiss ->
 *         ToolboxDropdownItem("选项", selected = ...) { ...; dismiss() }
 *     }
 * }
 * ```
 * 传入 [anchorPoint]（窗口坐标）时**弹层右上角对齐到该点**展开（典型用法：锚定行项右侧
 * 的值文本，弹层盖住该文本）；不传则锚定调用方布局中心下方。
 * 变换原点在弹层右上角：进入时从右上角向下、向左拉伸放大。
 */
@Composable
fun ToolboxDropdownPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorPoint: IntOffset? = null,
    width: Dp = 230.dp,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    // rendered 让弹层在退出动画倒放期间保持挂载，播完才真正移除
    var rendered by remember { mutableStateOf(expanded) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            rendered = true
            progress.animateTo(1f, tween(370, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(310, easing = FastOutSlowInEasing))
            rendered = false
        }
    }
    if (!rendered) return

    val density = LocalDensity.current
    val positionProvider = remember(density, anchorPoint) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val margin = with(density) { 16.dp.roundToPx() }
                val gap = with(density) { 4.dp.roundToPx() }
                // 弹层右上角对齐锚点（默认锚点 = 调用方布局右上角）
                val anchor = anchorPoint ?: IntOffset(anchorBounds.right, anchorBounds.top)
                val x = (anchor.x - popupContentSize.width)
                    .coerceIn(margin, windowSize.width - popupContentSize.width - margin)
                // 轻微上移让弹层盖住锚点文本；越界时钳制在屏幕内
                val y = (anchor.y - gap)
                    .coerceIn(margin, windowSize.height - popupContentSize.height - margin)
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest, // 点击外部 / 返回键 → 外部把 expanded 置 false，触发放回动画
        properties = PopupProperties(focusable = true),
    ) {
        val p = progress.value
        // 模糊消退进度：0 = 满模糊（前 65%），1 = 无模糊（末段）
        val blurFade = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)

        Column(
            modifier = Modifier
                .width(width)
                .graphicsLayer {
                    alpha = p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.7f + 0.3f * p
                    transformOrigin = TransformOrigin(1f, 0f) // 右上角为原点：向下、向左拉伸
                    translationY = (1f - p) * -12.dp.toPx()
                    // 模糊后硬裁剪到圆角矩形：渗出卡片轮廓的光晕被整体切掉
                    clip = true
                    shape = RoundedCornerShape(14.dp)
                    // 整层模糊（背景+内容）。MIRROR 采样：边缘镜像反射内容，
                    // 不用 CLAMP——它会把外缘像素单向拉长成白色条状边
                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p < 1f) {
                        val r = (1f - blurFade) * 20f
                        RenderEffect.createBlurEffect(r, r, Shader.TileMode.MIRROR).asComposeRenderEffect()
                    } else {
                        null
                    }
                }
                // 白边来源不是模糊本身，而是被模糊的浅色描边/投影。
                // 二者随模糊消退同步淡入：模糊期不画硬边 → 无白色条状光晕
                .shadow(12.dp * blurFade, RoundedCornerShape(14.dp))
                .background(ToolboxColors.Card)
                .border(
                    width = 1.dp,
                    color = ToolboxColors.Text.copy(alpha = 0.08f * blurFade),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(vertical = 6.dp),
        ) {
            content(onDismissRequest)
        }
    }
}

/** 下拉弹层选项：选中项文字变 Toolbox 绿 + 右侧 ✓（配 [ToolboxDropdownPopup] 使用） */
@Composable
fun ToolboxDropdownItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (selected) ToolboxColors.Accent else ToolboxColors.Text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "✓", color = ToolboxColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
