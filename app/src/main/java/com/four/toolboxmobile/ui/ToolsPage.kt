package com.four.toolboxmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.four.toolboxmobile.ui.theme.ToolboxColors

/**
 * 工具页（中页）：工具入口卡片列表。
 * 每个工具是全屏覆盖层体验（见 MainScreen 的 activeTool），底栏被遮盖。
 */
@Composable
fun ToolsPage(onOpenDsh: () -> Unit, onOpenSteamChat: () -> Unit) {
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
            text = "🧰 工具",
            color = ToolboxColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        ToolEntryCard(
            emoji = "🛰️",
            name = "DSH 远程",
            desc = "在同一局域网远程使用电脑上的 DeepSeek Harness",
            onClick = onOpenDsh,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ToolEntryCard(
            emoji = "💬",
            name = "Steam Mchat",
            desc = "登录 Steam 网页版，在手机上随时回复好友消息",
            onClick = onOpenSteamChat,
        )
    }
}

/** 工具入口卡片：图标瓷贴 + 名称/简介 + ›，整卡可点 */
@Composable
private fun ToolEntryCard(
    emoji: String,
    name: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ToolboxColors.Card)
            .border(
                width = 1.dp,
                color = ToolboxColors.Text.copy(alpha = 0.05f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ToolboxColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = emoji, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = ToolboxColors.Text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = desc, color = ToolboxColors.TextDim, fontSize = 12.sp)
        }
        Text(text = "›", color = ToolboxColors.TextDim, fontSize = 18.sp)
    }
}
