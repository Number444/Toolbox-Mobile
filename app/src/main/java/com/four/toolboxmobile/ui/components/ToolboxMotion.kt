package com.four.toolboxmobile.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ToolboxMobile 运动系统单源（2026-09-01 确立）——全 App 弹层的手感只改这里。
 *
 * 签名动效「失焦→对焦」三件套（Four 拍板）：
 *   模糊 maxBlur→0（前 holdFraction 保持、末段消退）+ 透明度 0→1 + 各层自己的位移路径
 *
 * 三层体系（动画路径匹配空间来源，质感/时长/缓动统一）：
 * - 锚点弹层（ToolboxDropdownPopup）：模糊 20px，从锚点拉伸生长
 * - 确认对话框（ToolboxConfirmDialog）：模糊 16px，中心放大 0.92→1
 * - 全屏覆盖层（工具页/扫码层）：模糊 10px（hold 0.40），底部 1/10 屏上浮
 *
 * 降级规则：API < 31 无 RenderEffect → 自动退化为纯淡入/缩放，不报错。
 */
object ToolboxMotion {
    const val ENTER_MS = 370
    const val EXIT_MS = 310
    val EASING = FastOutSlowInEasing

    /** 全屏覆盖层过渡：底部 1/10 屏上浮 + 淡入，退出倒放（弹簧默认刚度） */
    val overlayEnter: EnterTransition = slideInVertically(initialOffsetY = { it / 10 }) + fadeIn()
    val overlayExit: ExitTransition = slideOutVertically(targetOffsetY = { it / 10 }) + fadeOut()

    /** 覆盖层模糊开关：WebView 页若实测掉帧，置 false 即整体退回纯位移+淡入 */
    const val OVERLAY_BLUR_ENABLED = true
}

/**
 * 「失焦→对焦」公共件：透明度 + 模糊消退 + 圆角裁剪（不含位移/缩放，路径由各层自加）。
 * [progress] 0=完全失焦（满模糊、透明）→ 1=对焦完成；[holdFraction] 之前保持满模糊，之后线性消退。
 * 模糊采用 MIRROR 边缘采样（CLAMP 会把外缘像素拉成白色亮边）。
 */
fun Modifier.toolboxFocusIn(
    progress: Float,
    maxBlur: Float,
    cornerRadius: Dp,
    holdFraction: Float = 0.70f, // 2026-09-01 Four：模糊保持期后移 5%（0.65→0.70）
): Modifier {
    val cornerShape = RoundedCornerShape(cornerRadius)
    return this.graphicsLayer {
        alpha = progress
        clip = true
        shape = cornerShape
        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress < 1f) {
            val blurFade = ((progress - holdFraction) / (1f - holdFraction)).coerceIn(0f, 1f)
            val r = (1f - blurFade) * maxBlur
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.MIRROR).asComposeRenderEffect()
        } else {
            null
        }
    }
}

/** 描边/投影随模糊同步淡入的透明度（白边来源是被模糊的浅色描边与投影，二者必须跟着模糊走） */
fun focusStrokeAlpha(progress: Float, holdFraction: Float = 0.70f): Float =
    ((progress - holdFraction) / (1f - holdFraction)).coerceIn(0f, 1f)
