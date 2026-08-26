package com.four.toolboxmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Toolbox 色板：与桌面端 control_panel.html 的 CSS 变量 1:1 对齐。
 * 所有自绘界面统一从这里取色，禁止散落硬编码。
 */
object ToolboxColors {
    val Bg = Color(0xFF1C1C1C)          // --bg
    val Card = Color(0xFF2D2D2D)        // --card
    val Accent = Color(0xFF76B580)      // --accent（Toolbox 绿）
    val AccentHover = Color(0xFF92CD9B) // --accent-hover
    val Text = Color(0xFFF0F0F0)        // --text
    val TextDim = Color(0xFF999999)     // --text-dim
    val Border = Color(0xFF3F3F3F)      // --border
    val Danger = Color(0xFFF07070)      // --danger
    val Warning = Color(0xFFE0A030)     // --warning
    val GrayDim = Color(0xFF4A4A4A)     // --gray-dim
    val OnAccent = Color(0xFF1A1A1A)    // accent 按钮上的深色文字（对齐 WPF）
}

private val ToolboxDarkScheme = darkColorScheme(
    primary = ToolboxColors.Accent,
    onPrimary = ToolboxColors.OnAccent,
    background = ToolboxColors.Bg,
    onBackground = ToolboxColors.Text,
    surface = ToolboxColors.Card,
    onSurface = ToolboxColors.Text,
    surfaceVariant = ToolboxColors.Bg,
    onSurfaceVariant = ToolboxColors.TextDim,
    outline = ToolboxColors.Border,
    error = ToolboxColors.Danger,
)

@Composable
fun ToolboxTheme(content: @Composable () -> Unit) {
    // 本应用恒为深色主题（对齐 Toolbox 桌面端），不跟随系统浅色
    MaterialTheme(colorScheme = ToolboxDarkScheme, content = content)
}
