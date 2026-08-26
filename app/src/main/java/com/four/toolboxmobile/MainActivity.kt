package com.four.toolboxmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.four.toolboxmobile.ui.BottomNavBar
import com.four.toolboxmobile.ui.PlaceholderPage
import com.four.toolboxmobile.ui.RemotePage
import com.four.toolboxmobile.ui.theme.ToolboxTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToolboxTheme {
                MainScreen()
            }
        }
    }
}

/**
 * 三页骨架（左=远程连接 / 中=工具 / 右=设置，初始停在中间工具页）：
 * - HorizontalPager 承载三页，hazeSource 让底栏能模糊其背后的页面内容
 * - RemoteViewModel 为 Activity 级单例：连接/轮询状态常驻内存，
 *   左右切页是纯重组，零加载（方案 §3 关键架构决策）
 */
@Composable
fun MainScreen(viewModel: RemoteViewModel = viewModel()) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState),
        ) { page ->
            when (page) {
                0 -> RemotePage(viewModel)
                1 -> PlaceholderPage(title = "工具", icon = "🧰")
                2 -> PlaceholderPage(title = "设置", icon = "⚙️")
            }
        }

        BottomNavBar(
            pagerState = pagerState,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
