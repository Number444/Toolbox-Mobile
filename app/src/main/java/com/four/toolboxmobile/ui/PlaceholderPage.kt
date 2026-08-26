package com.four.toolboxmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.four.toolboxmobile.ui.theme.ToolboxColors

/** 工具页 / 设置页占位：本期不做具体功能（方案 §7） */
@Composable
fun PlaceholderPage(title: String, icon: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolboxColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = icon, fontSize = 42.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                color = ToolboxColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "敬请期待", color = ToolboxColors.TextDim, fontSize = 13.sp)
        }
    }
}
