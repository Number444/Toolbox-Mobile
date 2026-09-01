package com.four.toolboxmobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.four.toolboxmobile.ui.theme.ToolboxColors
import com.four.toolboxmobile.updater.AppUpdater.UpdateState

/**
 * Toolbox 统一确认对话框（2026-09-01 确立，弹层体系第 2 层：确认决策类）。
 *
 * - 弃用 M3 AlertDialog（动画不可控）与各处自绘裸 Dialog——全局只此一处
 * - 动效：中心放大 0.92→1 + 「失焦→对焦」模糊 16px（见 ToolboxMotion）；
 *   遮罩压暗走平台窗口 dim；退出倒放，`rendered` 标记保证退出动画播完再卸载
 * - 调用方式：常驻组合 + [visible] 开关（不要 if 包住整个组件，否则退出动画播不出来）；
 *   退出期间文案自动留住（[title]/[message] 只在 visible=true 时更新快照）
 * - [danger] = true 时确认按钮用 Danger 红（清除/关机等破坏性操作）
 */
@Composable
fun ToolboxConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确认",
    dismissText: String = "取消",
    danger: Boolean = false,
) {
    // 退出动画期间保留最后展示的文案（visible 已 false、调用方状态可能已清空）
    var shownTitle by remember { mutableStateOf(title) }
    var shownMessage by remember { mutableStateOf(message) }
    LaunchedEffect(visible, title, message) {
        if (visible) {
            shownTitle = title
            shownMessage = message
        }
    }

    ToolboxDialogShell(visible = visible, onDismiss = onDismiss) {
        Text(
            text = shownTitle,
            color = ToolboxColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = shownMessage,
            color = ToolboxColors.TextDim,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolboxDialogButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                primary = false,
            )
            ToolboxDialogButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                primary = true,
                danger = danger,
            )
        }
    }
}

/**
 * 应用更新对话框（与确认对话框同外壳、同动效；内容随 [UpdateState] 切换）：
 * - Available：版本 + 更新说明 + 「下载更新 / 暂不」
 * - Downloading：SteamChat 同款细进度条 + 百分比与体积 + 「取消下载」（取消后回到 Available 视图）
 * - Downloaded：「返回 / 安装」
 * - Failed：错误文案 + 「重试 / 关闭」
 * 调用方式同 ToolboxConfirmDialog：常驻组合 + visible 开关；退出期间快照最后状态。
 */
@Composable
fun ToolboxUpdateDialog(
    visible: Boolean,
    state: UpdateState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    var shownState by remember { mutableStateOf(state) }
    LaunchedEffect(visible, state) {
        if (visible) shownState = state
    }

    ToolboxDialogShell(visible = visible, onDismiss = onDismiss) {
        when (val s = shownState) {
            is UpdateState.Available -> {
                DialogTitle("发现新版本 v${s.version}")
                Spacer(modifier = Modifier.height(10.dp))
                DialogMessage(
                    (s.notes ?: "包含最新改动与修复。") +
                        "\n\n安装包大小：${formatMb(s.sizeBytes)} MB",
                )
                Spacer(modifier = Modifier.height(18.dp))
                DialogButtons(
                    dismissText = "暂不",
                    confirmText = "下载更新",
                    onDismiss = onDismiss,
                    onConfirm = onDownload,
                )
            }
            is UpdateState.Downloading -> {
                DialogTitle("正在下载更新…")
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ToolboxColors.Accent,
                    trackColor = ToolboxColors.Bg,
                )
                Spacer(modifier = Modifier.height(10.dp))
                DialogMessage(
                    "${(s.progress * 100).toInt()}%  ·  " +
                        "${formatMb(s.doneBytes)} / ${formatMb(s.totalBytes)} MB",
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolboxDialogButton(
                        text = "取消下载",
                        onClick = onCancelDownload,
                        modifier = Modifier.weight(1f),
                        primary = false,
                    )
                }
            }
            is UpdateState.Downloaded -> {
                DialogTitle("下载完成")
                Spacer(modifier = Modifier.height(10.dp))
                DialogMessage("已保存到系统下载目录：\n${s.fileName}")
                Spacer(modifier = Modifier.height(18.dp))
                DialogButtons(
                    dismissText = "返回",
                    confirmText = "安装",
                    onDismiss = onDismiss,
                    onConfirm = onInstall,
                )
            }
            is UpdateState.Failed -> {
                DialogTitle("更新失败")
                Spacer(modifier = Modifier.height(10.dp))
                DialogMessage(s.message)
                Spacer(modifier = Modifier.height(18.dp))
                DialogButtons(
                    dismissText = "关闭",
                    confirmText = "重试",
                    onDismiss = onDismiss,
                    onConfirm = onDownload,
                )
            }
            else -> {
                // 检查中/已最新等状态不应在此展示（打开对话框前已是 Available/Downloaded）
                DialogTitle("检查更新")
                Spacer(modifier = Modifier.height(10.dp))
                DialogMessage("正在确认更新状态…")
            }
        }
    }
}

/** 对话框动画外壳（2026-09-01 抽取）：常驻组合 + visible 开关，中心放大 + 失焦→对焦模糊，
 *  退出动画播完再卸载；内容槽由调用方填充（文案快照由调用方负责） */
@Composable
private fun ToolboxDialogShell(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    var rendered by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rendered = true
            progress.animateTo(1f, tween(ToolboxMotion.ENTER_MS, easing = ToolboxMotion.EASING))
        } else {
            progress.animateTo(0f, tween(ToolboxMotion.EXIT_MS, easing = ToolboxMotion.EASING))
            rendered = false
        }
    }
    if (!rendered) return

    val p = progress.value
    val stroke = focusStrokeAlpha(p)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                // 路径：中心轻微放大（无空间来源的弹层不从任何锚点生长）
                .graphicsLayer {
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                }
                // 质感：失焦→对焦（模糊 16px，层级半径表见 ToolboxMotion）
                .toolboxFocusIn(progress = p, maxBlur = 16f, cornerRadius = 14.dp)
                // 描边/投影随模糊同步淡入，防白色光晕边
                .shadow(12.dp * stroke, RoundedCornerShape(14.dp))
                .background(ToolboxColors.Card, RoundedCornerShape(14.dp))
                .border(
                    width = 1.dp,
                    color = ToolboxColors.Text.copy(alpha = 0.08f * stroke),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(text = text, color = ToolboxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DialogMessage(text: String) {
    Text(text = text, color = ToolboxColors.TextDim, fontSize = 13.sp, lineHeight = 20.sp)
}

/** 双按钮行：次按钮（描边灰）+ 主按钮（实心） */
@Composable
private fun DialogButtons(
    dismissText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolboxDialogButton(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            primary = false,
        )
        ToolboxDialogButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            primary = true,
        )
    }
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)

/** 对话框按钮：主按钮实心（danger 红 / 否则 Accent 绿），次按钮描边灰 */
@Composable
private fun ToolboxDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean,
    danger: Boolean = false,
) {
    val bg = when {
        !primary -> androidx.compose.ui.graphics.Color.Transparent
        danger -> ToolboxColors.Danger
        else -> ToolboxColors.Accent
    }
    val fg = when {
        !primary -> ToolboxColors.TextDim
        else -> ToolboxColors.OnAccent
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
        ),
        border = if (!primary) {
            androidx.compose.foundation.BorderStroke(1.dp, ToolboxColors.Border)
        } else {
            null
        },
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}
