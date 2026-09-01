package com.four.toolboxmobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.four.toolboxmobile.BuildConfig
import com.four.toolboxmobile.DshStore
import com.four.toolboxmobile.DshViewModel
import com.four.toolboxmobile.ui.components.ToolboxDropdownItem
import com.four.toolboxmobile.ui.components.ToolboxDropdownPopup
import com.four.toolboxmobile.ui.theme.ToolboxColors

/** 设置页键名（MainScreen 读取同一键决定初始页） */
private const val PREFS_NAME = "settings"
private const val KEY_DEFAULT_PAGE = "default_page"

private const val REPO_URL = "https://github.com/Number444/Toolbox-Mobile"

private val PAGE_NAMES = listOf("远程连接", "工具", "设置")

/**
 * 设置页（右页）。样式：Android 标准 Preference 列表形态（标题 + 摘要 + 点击弹单选对话框），
 * 配色与卡片语言沿用 Toolbox 色板。
 */
@Composable
fun SettingsPage(dshViewModel: DshViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val dshStore = remember { DshStore(context) }

    var defaultPage by remember { mutableStateOf(prefs.getInt(KEY_DEFAULT_PAGE, 1).coerceIn(0, 2)) }
    var showPageDialog by remember { mutableStateOf(false) }

    // DSH 绑定展示态：与工具内 ViewModel（Activity 级）共享同一实例，清除即全局生效。
    // 监听其状态跃迁刷新展示——首次在工具里绑定成功（Unbound→Searching→Connected）
    // 后切回本页也能立即看到新绑定，不靠重建页面
    var dshBinding by remember { mutableStateOf(dshStore.load()) }
    val dshState by dshViewModel.state.collectAsState()
    LaunchedEffect(dshState) { dshBinding = dshStore.load() }
    var showClearDshDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolboxColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 110.dp),
    ) {
        Text(
            text = "⚙️ 设置",
            color = ToolboxColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ===== 启动分组 =====
        SettingsGroup(title = "启动") {
            // 锚点 = 右侧值文本（"工具 ›"）的窗口位置：弹层右上角对齐它并盖住文本
            var valueAnchor by remember { mutableStateOf<IntOffset?>(null) }
            Box {
                SettingsRow(
                    title = "默认打开页面",
                    summary = "启动 App 时首先显示的页面",
                    value = PAGE_NAMES[defaultPage],
                    onClick = { showPageDialog = true },
                    onValueBounds = { rect -> valueAnchor = IntOffset(rect.right, rect.top) },
                )
                ToolboxDropdownPopup(
                    expanded = showPageDialog,
                    onDismissRequest = { showPageDialog = false },
                    anchorPoint = valueAnchor,
                ) { dismiss ->
                    PAGE_NAMES.forEachIndexed { index, name ->
                        ToolboxDropdownItem(
                            text = name,
                            selected = defaultPage == index,
                            onClick = {
                                defaultPage = index
                                prefs.edit().putInt(KEY_DEFAULT_PAGE, index).apply()
                                dismiss()
                            },
                        )
                    }
                }
            }
        }

        // ===== 工具分组 =====
        SettingsGroup(title = "工具") {
            SettingsRow(
                title = "DSH 绑定",
                summary = "远程工具记住的访问密钥与入口",
                value = dshBinding?.let { b -> "${b.host ?: "未连接过"}:${b.port}" } ?: "未绑定",
                onClick = if (dshBinding != null) ({ showClearDshDialog = true }) else null,
            )
        }

        // ===== 关于分组 =====
        SettingsGroup(title = "关于") {
            SettingsRow(
                title = "版本",
                summary = "ToolboxMobile",
                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = null,
            )
            SettingsRow(
                title = "GitHub 仓库",
                summary = "源码 · 问题反馈",
                value = "Toolbox-Mobile",
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "默认页面更改后下次启动 App 时生效",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    // 清除 DSH 绑定确认弹窗
    if (showClearDshDialog) {
        AlertDialog(
            onDismissRequest = { showClearDshDialog = false },
            containerColor = ToolboxColors.Card,
            title = { Text(text = "清除 DSH 绑定？", color = ToolboxColors.Text, fontSize = 16.sp) },
            text = {
                Text(
                    text = "将删除已保存的密钥与入口地址，下次打开 DSH 远程需要重新扫码绑定。",
                    color = ToolboxColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dshViewModel.clearBinding()
                    dshBinding = null
                    showClearDshDialog = false
                }) {
                    Text(text = "清除", color = ToolboxColors.Danger, fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDshDialog = false }) {
                    Text(text = "取消", color = ToolboxColors.TextDim, fontSize = 14.sp)
                }
            },
        )
    }
}

/** 分组卡片：小标题 + 圆角深色容器 */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        color = ToolboxColors.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ToolboxColors.Card)
            .border(
                width = 1.dp,
                color = ToolboxColors.Text.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        content()
    }
}

/** 单条设置项：标题 + 摘要，右侧当前值（可点击时带 ›，onClick=null 为纯展示行）。
 *  [onValueBounds] 非空时回报右侧值文本的窗口坐标（供锚点弹层定位） */
@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    value: String,
    onClick: (() -> Unit)?,
    onValueBounds: ((IntRect) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = ToolboxColors.Text, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = summary, color = ToolboxColors.TextDim, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = if (onClick != null) ToolboxColors.Accent else ToolboxColors.TextDim,
                fontSize = 13.sp,
                modifier = if (onValueBounds != null) {
                    Modifier.onGloballyPositioned { lc ->
                        onValueBounds(IntRect(lc.positionInWindow().round(), lc.size))
                    }
                } else {
                    Modifier
                },
            )
            if (onClick != null) {
                Text(
                    text = " ›",
                    color = ToolboxColors.TextDim,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
