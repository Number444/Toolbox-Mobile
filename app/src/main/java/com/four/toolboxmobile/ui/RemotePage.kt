package com.four.toolboxmobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.four.toolboxmobile.ConnUiState
import com.four.toolboxmobile.ConfirmRequest
import com.four.toolboxmobile.DeviceEntry
import com.four.toolboxmobile.EventItem
import com.four.toolboxmobile.LanScanner
import com.four.toolboxmobile.RemoteViewModel
import com.four.toolboxmobile.ShareItem
import com.four.toolboxmobile.SystemStatus
import com.four.toolboxmobile.UploadItem
import com.four.toolboxmobile.ui.theme.ToolboxColors
import kotlin.math.roundToInt

/**
 * 远程连接页（左页，自绘）。
 * 全部状态来自 [RemoteViewModel]——状态常驻内存，切入本页零加载。
 * 已连接后面板功能与 toolbox control_panel.html 对齐：
 * 快捷操作 / 文件传输 / 电源控制 / 系统状态 / 操作日志 / 已连接设备 / 关闭 Toolbox。
 */
@Composable
fun RemotePage(viewModel: RemoteViewModel) {
    val state by viewModel.uiState.collectAsState()
    val flash by viewModel.flash.collectAsState()
    val confirm by viewModel.confirm.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ToolboxColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 110.dp), // 底部避让悬浮导航栏
    ) {
        // ===== 顶栏：标题 + 呼吸灯连接状态 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "🛰️ Toolbox 远程连接",
                color = ToolboxColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            ConnBadge(state)
        }

        // 一次性操作提示（4s 自动消失，对齐 HTML flash 行为）
        flash?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it.text,
                color = if (it.ok) ToolboxColors.Accent else ToolboxColors.Danger,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val s = state) {
            is ConnUiState.Scanning -> {
                ScanCard(s)
                if (s.found.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FoundHeader(count = s.found.size)
                    Spacer(modifier = Modifier.height(8.dp))
                    s.found.forEach { ip ->
                        DeviceCard(ip, onConnect = { viewModel.connect(ip) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            is ConnUiState.DevicesFound -> {
                if (s.found.isEmpty()) {
                    EmptyScanCard()
                } else {
                    FoundHeader(count = s.found.size)
                    Spacer(modifier = Modifier.height(8.dp))
                    s.found.forEach { ip ->
                        DeviceCard(ip, onConnect = { viewModel.connect(ip) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                GhostButton(text = "🔄 重新扫描", onClick = viewModel::startScan)
            }

            is ConnUiState.Connecting -> {
                ToolboxCard {
                    Text(
                        text = "正在连接 ${s.ip}:${LanScanner.DEFAULT_PORT} …",
                        color = ToolboxColors.TextDim,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ToolboxColors.Accent,
                        trackColor = ToolboxColors.Bg,
                    )
                }
            }

            is ConnUiState.AuthRequired -> {
                AuthCard(s, onLogin = viewModel::login, onCancel = viewModel::startScan)
            }

            is ConnUiState.Connected -> {
                ConnectedPanel(s, viewModel)
            }

            is ConnUiState.Error -> {
                ToolboxCard {
                    Text(text = "⚠️ 连接失败", color = ToolboxColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = s.message, color = ToolboxColors.TextDim, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                GhostButton(text = "🔄 重新扫描", onClick = viewModel::startScan)
            }
        }
    }

    // ===== 自绘二次确认弹窗（对齐 HTML 深色模态，不用系统原生 confirm） =====
    confirm?.let { req ->
        ConfirmDialog(req, onConfirm = viewModel::confirmNow, onDismiss = viewModel::dismissConfirm)
    }
}

// ==================== 已连接面板 ====================

@Composable
private fun ConnectedPanel(s: ConnUiState.Connected, vm: RemoteViewModel) {
    val events by vm.events.collectAsState()
    val devices by vm.devices.collectAsState()
    val shares by vm.shares.collectAsState()
    val uploads by vm.uploads.collectAsState()

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.uploadFiles(uris)
    }

    // ⚡ 快捷操作
    ToolboxCard {
        CardTitle("⚡ 快捷操作")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("锁屏", { vm.quick("lock") }, Modifier.weight(1f))
            AccentButton("睡眠", { vm.quick("sleep") }, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("关显示器", { vm.quick("monitor_off") }, Modifier.weight(1f))
            AccentButton("重启资源管理器", { vm.quick("explorer_restart") }, Modifier.weight(1f))
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 📁 文件传输
    ToolboxCard {
        CardTitle("📁 文件传输")
        // 上传投放区（虚线框大点击目标，对齐 HTML drop-zone）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.5.dp, ToolboxColors.Border, RoundedCornerShape(10.dp))
                .clickable { pickLauncher.launch(arrayOf("*/*")) }
                .padding(vertical = 18.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "📤", fontSize = 26.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "点按选择文件，发送到电脑", color = ToolboxColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(3.dp))
                Text(text = "支持多选 · 大文件流式传输", color = ToolboxColors.TextDim, fontSize = 12.sp)
            }
        }

        // 上传进度列表
        uploads.forEach { u ->
            Spacer(modifier = Modifier.height(10.dp))
            UploadRow(u)
        }

        Divider()

        Text(text = "💾 电脑共享的文件", color = ToolboxColors.TextDim, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        val list = shares
        when {
            list == null -> Text(text = "（加载中…）", color = ToolboxColors.TextDim, fontSize = 12.sp)
            list.isEmpty() -> Text(text = "（暂无共享文件）", color = ToolboxColors.TextDim, fontSize = 12.sp)
            else -> list.forEach { share -> ShareRow(share, onDownload = { vm.download(share) }) }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 🔌 电源控制（2x3 快捷网格 + 自定义分钟，对齐 HTML 面板）
    ToolboxCard {
        CardTitle("🔌 电源控制")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("1 分钟", { vm.requestShutdown(60) }, Modifier.weight(1f))
            AccentButton("5 分钟", { vm.requestShutdown(300) }, Modifier.weight(1f))
            AccentButton("10 分钟", { vm.requestShutdown(600) }, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("30 分钟", { vm.requestShutdown(1800) }, Modifier.weight(1f))
            AccentButton("1 小时", { vm.requestShutdown(3600) }, Modifier.weight(1f))
            AccentButton("2 小时", { vm.requestShutdown(7200) }, Modifier.weight(1f))
        }

        Divider()

        var minutes by remember { mutableStateOf("") }
        // 输入行与按钮行分离：窄屏上单行放不下"输入框+双按钮"，会把占位文字挤成竖排
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "分钟：", color = ToolboxColors.TextDim, fontSize = 13.sp)
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("1~1440", color = ToolboxColors.TextDim, fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("🔌 定时关机", { vm.customShutdown(minutes) }, Modifier.weight(1f))
            DangerButton("🛑 取消关机", { vm.quick("cancel_shutdown") }, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DangerButton("立即关机", { vm.requestShutdown(0) }, Modifier.weight(1f))
            DangerButton("重启电脑", { vm.requestRestart() }, Modifier.weight(1f))
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 📊 系统状态
    StatusCard(s)

    Spacer(modifier = Modifier.height(12.dp))

    // 📜 操作日志
    ToolboxCard {
        CardTitle("📜 操作日志")
        if (events.isEmpty()) {
            Text(text = "（暂无操作）", color = ToolboxColors.TextDim, fontSize = 12.sp)
        } else {
            events.forEach { e -> EventRow(e) }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 📱 已连接设备
    ToolboxCard {
        CardTitle("📱 已连接设备")
        if (devices.isEmpty()) {
            Text(text = "（暂无设备）", color = ToolboxColors.TextDim, fontSize = 12.sp)
        } else {
            devices.forEachIndexed { index, d ->
                DeviceRow(d, onKick = { vm.kick(d.ip) })
                if (index < devices.lastIndex) ThinDivider()
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 收尾操作：断开连接（安全，居左）+ 关闭 Toolbox（危险，居右），等宽同高成对排布
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GhostButton(
            text = "断开连接",
            onClick = vm::disconnect,
            modifier = Modifier.weight(1f).height(46.dp),
        )
        DangerButton(
            "关闭 Toolbox",
            onClick = vm::requestShutdownApp,
            modifier = Modifier.weight(1f).height(46.dp),
        )
    }
}

// ==================== 面板子组件 ====================

@Composable
private fun UploadRow(u: UploadItem) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "⬆ ${u.name}${if (u.sizeText.isNotEmpty()) " · ${u.sizeText}" else ""}",
            color = ToolboxColors.Text,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = when {
                u.error != null -> "❌ ${u.error}"
                u.done -> "✅ 已完成"
                else -> "${u.percent}%"
            },
            color = if (u.error != null) ToolboxColors.Danger else ToolboxColors.TextDim,
            fontSize = 12.sp,
        )
    }
    Spacer(modifier = Modifier.height(3.dp))
    Meter(
        percent = u.percent / 100f,
        color = if (u.error != null) ToolboxColors.Danger else ToolboxColors.Accent,
    )
}

@Composable
private fun ShareRow(share: ShareItem, onDownload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ToolboxColors.Bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "📄 ${share.name}", color = ToolboxColors.Text, fontSize = 13.sp)
            Text(text = formatSize(share.size), color = ToolboxColors.TextDim, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        AccentButton("下载", onDownload)
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun EventRow(e: EventItem) {
    Text(
        text = "${e.time}  ${e.command}  from ${e.fromIp}",
        color = ToolboxColors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 3.dp),
    )
    ThinDivider()
}

@Composable
private fun DeviceRow(d: DeviceEntry, onKick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${if (d.online) "🟢" else "⚪"} ${d.name} · ${d.ip} · ${d.subtitle}",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onKick,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ToolboxColors.Danger),
            border = BorderStroke(1.dp, ToolboxColors.Danger),
        ) {
            Text(text = if (d.online) "踢出" else "移除", fontSize = 12.sp)
        }
    }
}

/** 已连接状态卡片：系统状态四表（CPU/内存/磁盘/电池） */
@Composable
private fun StatusCard(s: ConnUiState.Connected) {
    ToolboxCard {
        CardTitle("📊 系统状态 · ${s.ip}")

        val st = s.status
        if (st == null) {
            Text(text = "状态加载中…", color = ToolboxColors.TextDim, fontSize = 13.sp)
        } else {
            val cpu = st.cpuPercent
            MeterRow(
                label = "CPU",
                valueText = cpu?.let { "${it.roundToInt()}%" } ?: "--",
                percent = ((cpu ?: 0.0) / 100.0).toFloat(),
            )
            val memPct = if (st.memoryTotalGB != null && st.memoryTotalGB > 0 && st.memoryUsedGB != null)
                st.memoryUsedGB / st.memoryTotalGB else 0.0
            MeterRow(
                label = "内存",
                valueText = if (st.memoryUsedGB != null && st.memoryTotalGB != null)
                    "${fmt1(st.memoryUsedGB)} / ${fmt1(st.memoryTotalGB)} GB" else "--",
                percent = memPct.toFloat(),
            )
            val diskPct = if (st.diskTotalGB != null && st.diskTotalGB > 0 && st.diskFreeGB != null)
                (st.diskTotalGB - st.diskFreeGB) / st.diskTotalGB else 0.0
            MeterRow(
                label = "磁盘",
                valueText = if (st.diskName != null && st.diskFreeGB != null && st.diskTotalGB != null)
                    "${st.diskName} 剩余 ${fmt1(st.diskFreeGB)} / ${fmt1(st.diskTotalGB)} GB" else "--",
                percent = diskPct.toFloat(),
            )
            if (st.hasBattery) {
                MeterRow(
                    label = "电池",
                    valueText = (st.batteryPercent?.let { "$it%" } ?: "--") +
                        (st.batteryStatus?.let { " · $it" } ?: ""),
                    percent = ((st.batteryPercent ?: 0) / 100.0).toFloat(),
                )
            } else {
                MeterRow(label = "电池", valueText = "无电池", percent = 1f, fixedBarColor = ToolboxColors.GrayDim)
            }
            Text(
                text = "运行时长：${st.uptime ?: "--"}　公网 IP：${st.ipv4 ?: "--"}",
                color = ToolboxColors.TextDim,
                fontSize = 12.sp,
            )
        }

        if (!s.reachable) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "⚠️ 连接不稳定，正在重试…", color = ToolboxColors.Warning, fontSize = 12.sp)
        }
    }
}

// ==================== 扫描 / 认证 ====================

/** 呼吸灯状态点：已连接绿色呼吸 / 断开红色常亮 / 扫描中灰色 */
@Composable
private fun ConnBadge(state: ConnUiState) {
    val (dotColor, label, pulse) = when (state) {
        is ConnUiState.Connected ->
            if (state.reachable) Triple(ToolboxColors.Accent, "已连接：${state.ip}", true)
            else Triple(ToolboxColors.Warning, "重连中…", false)
        is ConnUiState.Scanning -> Triple(ToolboxColors.TextDim, "扫描中…", false)
        is ConnUiState.Connecting -> Triple(ToolboxColors.TextDim, "连接中…", false)
        is ConnUiState.AuthRequired -> Triple(ToolboxColors.Warning, "等待密钥", false)
        is ConnUiState.DevicesFound -> Triple(ToolboxColors.TextDim, "未连接", false)
        is ConnUiState.Error -> Triple(ToolboxColors.Danger, "无法连接", false)
    }

    val alpha by if (pulse) {
        rememberInfiniteTransition(label = "conn-pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "conn-pulse-alpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = dotColor, fontSize = 12.sp)
    }
}

/** 扫描进度卡片 */
@Composable
private fun ScanCard(s: ConnUiState.Scanning) {
    ToolboxCard {
        CardTitle("🔍 正在扫描局域网设备")
        val subnet = s.prefix?.let { "$it.0/24" } ?: "获取网段中…"
        Text(
            text = "$subnet · 端口 ${LanScanner.DEFAULT_PORT} · ${s.scanned}/${s.total}",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (s.total > 0) s.scanned.toFloat() / s.total else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = ToolboxColors.Accent,
            trackColor = ToolboxColors.Bg,
        )
    }
}

/** 发现设备的分区标题：明确告知"点击连接"，补足引导性 */
@Composable
private fun FoundHeader(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ToolboxColors.Accent),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "已发现 $count 台设备 · 点击连接",
            color = ToolboxColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 设备卡片：图标 + IP/端口 + 实心"连接"按钮（整卡也可点），引导性明确的现代列表项 */
@Composable
private fun DeviceCard(ip: String, onConnect: () -> Unit) {
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
            .clickable(onClick = onConnect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标底座：淡绿圆角方块
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ToolboxColors.Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🖥️", fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = ip, color = ToolboxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "端口 ${LanScanner.DEFAULT_PORT} · Toolbox 服务端",
                color = ToolboxColors.TextDim,
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        AccentButton(text = "连接", onClick = onConnect)
    }
}

/** 扫描完成但无设备的空态卡片：大图标 + 说明 + 排查提示 */
@Composable
private fun EmptyScanCard() {
    ToolboxCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "📡", fontSize = 34.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "未发现 Toolbox 设备",
                color = ToolboxColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "请确认：\n· 电脑端 Toolbox 已启动，远程控制已开启\n· 手机与电脑连接同一局域网",
                color = ToolboxColors.TextDim,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

/** 密钥认证卡片 */
@Composable
private fun AuthCard(
    s: ConnUiState.AuthRequired,
    onLogin: (ip: String, token: String) -> Unit,
    onCancel: () -> Unit,
) {
    var key by remember(s.ip) { mutableStateOf("") }

    ToolboxCard {
        CardTitle("🔒 输入访问密钥")
        Text(
            text = "设备 ${s.ip} 要求认证（密钥在 Toolbox 远程控制面板查看）",
            color = ToolboxColors.TextDim,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("访问密钥", color = ToolboxColors.TextDim, fontSize = 14.sp) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            colors = darkFieldColors(),
        )
        if (s.error != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = s.error, color = ToolboxColors.Danger, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(text = "登录", onClick = { onLogin(s.ip, key) }, modifier = Modifier.weight(1f))
            GhostButton(text = "返回", onClick = onCancel, modifier = Modifier.weight(1f))
        }
    }
}

// ==================== 确认弹窗 ====================

/** 自绘二次确认弹窗（Toolbox 深色模态风格，对齐 HTML #confirm-box） */
@Composable
private fun ConfirmDialog(req: ConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ToolboxColors.Card)
                .padding(18.dp),
        ) {
            Text(text = req.title, color = ToolboxColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = req.text, color = ToolboxColors.TextDim, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DangerButton("确认", onConfirm, Modifier.weight(1f))
                GhostButton("取消", onDismiss, Modifier.weight(1f))
            }
        }
    }
}

// ==================== 通用小部件 ====================

@Composable
private fun CardTitle(text: String) {
    Text(text = text, color = ToolboxColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(10.dp))
}

/** 分区分割细线（对齐 HTML .divider，浅灰 --gray-dim） */
@Composable
private fun Divider() {
    Spacer(modifier = Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ToolboxColors.GrayDim))
    Spacer(modifier = Modifier.height(12.dp))
}

/** 列表行间的细分隔线（对齐 HTML #3a3a3a 行底线） */
@Composable
private fun ThinDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ToolboxColors.Border))
}

/**
 * 单条状态仪表：标签行 + 8dp 圆角进度条。
 * 占用率色彩语义（对齐 HTML 面板）：≥85% 警告黄，≥95% 危险红。
 */
@Composable
private fun MeterRow(
    label: String,
    valueText: String,
    percent: Float,
    fixedBarColor: Color? = null,
) {
    val clamped = percent.coerceIn(0f, 1f)
    val barColor by animateColorAsState(
        targetValue = fixedBarColor ?: when {
            clamped >= 0.95f -> ToolboxColors.Danger
            clamped >= 0.85f -> ToolboxColors.Warning
            else -> ToolboxColors.Accent
        },
        label = "meter-color-$label",
    )

    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = ToolboxColors.TextDim, fontSize = 12.sp)
            Text(text = valueText, color = ToolboxColors.TextDim, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Meter(percent = clamped, color = barColor)
    }
}

/** 8dp 圆角进度条（宽度变化 400ms 动画，对齐 HTML bar transition） */
@Composable
private fun Meter(percent: Float, color: Color) {
    val animated by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "meter",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(ToolboxColors.Bg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

/** 卡片容器：圆角 8dp + 顶部微高光（对齐 HTML .card） */
@Composable
private fun ToolboxCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ToolboxColors.Card)
            .border(
                width = 1.dp,
                color = ToolboxColors.Text.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(14.dp),
    ) {
        content()
    }
}

/** 实心绿按钮（深色文字，对齐 HTML button） */
@Composable
private fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ToolboxColors.Accent,
            contentColor = ToolboxColors.OnAccent,
        ),
    ) {
        Text(text = text, fontSize = 13.sp, maxLines = 1)
    }
}

/** 实心红按钮（危险操作，对齐 HTML button.danger；红底白字保证对比度） */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ToolboxColors.Danger,
            contentColor = ToolboxColors.Text,
        ),
    ) {
        Text(text = text, fontSize = 14.sp, maxLines = 1)
    }
}

/** 幽灵按钮（灰底细边框，对齐 HTML button.ghost） */
@Composable
private fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ToolboxColors.Text),
        border = BorderStroke(1.dp, ToolboxColors.Border),
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

/** 深色输入框配色（聚焦边框变 Accent 绿，对齐 HTML :focus） */
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ToolboxColors.Accent,
    unfocusedBorderColor = ToolboxColors.Border,
    focusedTextColor = ToolboxColors.Text,
    unfocusedTextColor = ToolboxColors.Text,
    cursorColor = ToolboxColors.Accent,
)

private fun fmt1(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}
