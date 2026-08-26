package com.four.toolboxmobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
 * - 灰色半透明 + 毛玻璃**圆角矩形**（Android 12+ 真模糊，低版本降级半透明灰）
 * - 三项均分：连接 / 工具 / 设置
 * - 选中项被 Toolbox 绿圆角矩形线框框住（框体包住菜单项内容外侧）、文字变绿
 * - 绿框随页面滑动实时跟随；点击菜单弹性滑过去；按住绿框可拖动，松手吸附最近项
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = ToolboxColors.Card,
                        tints = listOf(HazeTint(ToolboxColors.Card.copy(alpha = 0.55f))),
                        blurRadius = 24.dp,
                        fallbackTint = HazeTint(ToolboxColors.Card.copy(alpha = 0.85f)),
                    ),
                )
                .border(
                    width = 1.dp,
                    color = ToolboxColors.Text.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(20.dp),
                ),
        ) {
            // ===== 绿色选中框（可拖动）：尽量贴近槽位边缘，把菜单项内容包在框内 =====
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(density) { itemWidthPx.toDp() })
                    .offset { IntOffset((position.value * itemWidthPx).roundToInt(), 0) }
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .border(
                        width = 1.5.dp,
                        color = ToolboxColors.Accent,
                        shape = RoundedCornerShape(16.dp),
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

            // ===== 三个菜单项 =====
            Row(modifier = Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
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
        }
    }
}
