package com.four.toolboxmobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.four.toolboxmobile.ui.theme.ToolboxColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 悬浮毛玻璃底部切换栏（对齐设计草图与方案 §6）：
 * - 灰色半透明 + 毛玻璃**圆角矩形**底栏（Android 12+ 实时模糊，低版本降级半透明灰）
 * - 栏宽为屏幕宽度的 4/5，居中悬浮；选中框尺寸随槽位自动同步
 * - 三项均分：连接 / 工具 / 设置
 * - 选中框（Toolbox 绿圆角矩形线框 + 淡绿填充）与底栏**同高、同槽宽、同圆角（20dp）**，
 *   静止时沿底栏边缘刚好完整包住所选槽位
 * - 绿框随页面滑动实时跟随；点击菜单弹性滑过去；按住绿框可拖动，松手吸附最近项；
 *   移动过程中绿框整体放大 10%，停稳后回弹
 */
@Composable
fun BottomNavBar(
    pagerState: PagerState,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val icons = listOf("🔗", "🧰", "⚙️")
    val labels = listOf("连接", "工具", "设置")
    val pageCount = labels.size

    val barHeight = 64.dp
    val frameHeight = barHeight // 绿框与底栏同高：静止时刚好沿底栏边缘完整包住槽位

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.8f) // 栏宽 = 屏宽 4/5，居中悬浮
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
    ) {
        val barWidthPx = constraints.maxWidth.toFloat()
        val itemWidthPx = barWidthPx / pageCount

        // 绿框位置（单位：页索引，浮点）。初始对齐初始页
        val position = remember { Animatable(pagerState.currentPage.toFloat()) }
        var dragging by remember { mutableStateOf(false) }

        // 页面滑动 → 绿框实时跟随（拖动绿框期间暂停跟随，避免互相抢）
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                .collect { p ->
                    if (!dragging) position.snapTo(p.coerceIn(0f, (pageCount - 1).toFloat()))
                }
        }

        // 选中项 = 绿框当前最近的一页（驱动文字变绿）
        val selectedIndex by remember(position) {
            derivedStateOf { position.value.roundToInt().coerceIn(0, pageCount - 1) }
        }

        // 移动中（拖动 / 页面滚动 / 吸附动画进行中）→ 绿框放大 10%
        val moving = dragging || pagerState.isScrollInProgress || position.isRunning
        val frameScale by animateFloatAsState(
            targetValue = if (moving) 1.1f else 1f,
            animationSpec = tween(150),
            label = "frame-scale",
        )

        // 毛玻璃：实时模糊（2026-08-14 Four 决定保留质感，不做滑动期降级）
        val blurRadius = 16.dp

        Box(modifier = Modifier.fillMaxWidth().height(frameHeight)) {
            // ===== 毛玻璃底栏（圆角矩形，垂直居中，让绿框能溢出它的上下缘） =====
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = ToolboxColors.Card,
                            tints = listOf(HazeTint(ToolboxColors.Card.copy(alpha = 0.55f))),
                            blurRadius = blurRadius,
                            fallbackTint = HazeTint(ToolboxColors.Card.copy(alpha = 0.85f)),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = ToolboxColors.Text.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(20.dp),
                    ),
            )

            // ===== 三个菜单项 =====
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(barHeight),
            ) {
                labels.forEachIndexed { index, label ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { scope.launch { pagerState.animateScrollToPage(index) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = icons[index], fontSize = 15.sp)
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (selectedIndex == index) ToolboxColors.Accent
                            else ToolboxColors.TextDim,
                            fontWeight = if (selectedIndex == index) FontWeight.SemiBold
                            else FontWeight.Normal,
                        )
                    }
                }
            }

            // ===== 绿色选中框（最上层，可拖动） =====
            // 置于菜单行之上：detectDragGestures 不过触摸斜率不消费事件，
            // 短按仍落到下层菜单项触发点击；拖动超斜率后消费位移事件，下层点击自动取消。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((position.value * itemWidthPx).roundToInt(), 0) }
                    .width(with(density) { itemWidthPx.toDp() })
                    .height(frameHeight)
                    .graphicsLayer {
                        scaleX = frameScale
                        scaleY = frameScale
                    }
                    .background(
                        color = ToolboxColors.Accent.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .border(
                        width = 1.5.dp,
                        color = ToolboxColors.Accent,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .pointerInput(itemWidthPx) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val next = (position.value + dragAmount.x / itemWidthPx)
                                    .coerceIn(0f, (pageCount - 1).toFloat())
                                scope.launch { position.snapTo(next) }
                            },
                            onDragEnd = {
                                dragging = false
                                val target = position.value.roundToInt().coerceIn(0, pageCount - 1)
                                scope.launch { pagerState.animateScrollToPage(target) }
                            },
                            onDragCancel = {
                                dragging = false
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                            },
                        )
                    },
            )
        }
    }
}
