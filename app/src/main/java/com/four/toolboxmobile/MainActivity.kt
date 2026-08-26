package com.four.toolboxmobile

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.four.toolboxmobile.ui.BottomNavBar
import com.four.toolboxmobile.ui.PlaceholderPage
import com.four.toolboxmobile.ui.RemotePage
import com.four.toolboxmobile.ui.SettingsPage
import com.four.toolboxmobile.ui.theme.ToolboxTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 显式 dark 样式（深色背景 + 浅色图标）：默认 auto 在本主题下会把状态栏
        // 时钟/图标渲染成黑色，深色背景上看不清
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            ToolboxTheme {
                MainScreen()
            }
        }
    }
}

/**
 * 三页骨架（左=远程连接 / 中=工具 / 右=设置，初始页由设置项决定，默认中间工具页）：
 * - HorizontalPager 承载三页，hazeSource 让底栏能模糊其背后的页面内容
 * - RemoteViewModel 为 Activity 级单例：连接/轮询状态常驻内存，
 *   左右切页是纯重组，零加载（方案 §3 关键架构决策）
 */
@Composable
fun MainScreen(viewModel: RemoteViewModel = viewModel()) {
    // 启动默认页（设置页可改，下次启动生效）
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val pagerState = rememberPagerState(
        initialPage = prefs.getInt("default_page", 1).coerceIn(0, 2),
        pageCount = { 3 },
    )
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // 提前组合左右相邻页：远程连接页卡片多，若等滑动时才首次组合会掉帧
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        ) { page ->
            when (page) {
                0 -> RemotePage(viewModel)
                1 -> PlaceholderPage(title = "工具", icon = "🧰")
                2 -> SettingsPage()
            }
        }

        BottomNavBar(
            pagerState = pagerState,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
