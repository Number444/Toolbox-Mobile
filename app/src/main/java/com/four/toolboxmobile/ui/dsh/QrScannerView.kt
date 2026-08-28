package com.four.toolboxmobile.ui.dsh

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.four.toolboxmobile.ui.theme.ToolboxColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** 扫码窗边长 / 镂空圆角 / 角框线长 */
private val WindowSize: Dp = 240.dp
private val WindowCorner: Dp = 20.dp
private val CornerArm: Dp = 26.dp

/**
 * 扫码绑定层：CameraX 预览 + ML Kit 二维码识别（仅 QR 格式）。
 * 识别到首个二维码即回调 onResult（内容为 dsh-app 设置页的完整访问地址）。
 */
@Composable
fun QrScannerView(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(onResult = onResult, onCancel = onCancel)
        } else {
            // 权限被拒：说明 + 重试/取消
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "📷", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "需要相机权限才能扫码绑定",
                    color = ToolboxColors.Text,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row {
                    DshOutlineButton(text = "取消", onClick = onCancel)
                    Spacer(modifier = Modifier.width(12.dp))
                    DshPrimaryButton(
                        text = "授予权限",
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val detected = remember { AtomicBoolean(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || detected.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull()?.rawValue?.takeIf { it.isNotBlank() }
                        if (raw != null && detected.compareAndSet(false, true)) onResult(raw)
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, executor)
        onDispose {
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            scanner.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScannerOverlay()
        Text(
            text = "对准 dsh-app 设置页「局域网共享」的二维码",
            color = ToolboxColors.Text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = WindowSize / 2 + 28.dp)
                .padding(horizontal = 32.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
        ) {
            DshOutlineButton(text = "取消", onClick = onCancel)
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                Spacer(modifier = Modifier.width(16.dp))
                DshOutlineButton(
                    text = if (torchOn) "关手电" else "开手电",
                    onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                )
            }
        }
    }
}

/** 半透明遮罩 + 中央镂空扫码窗 + Accent 色四角框 */
@Composable
private fun ScannerOverlay() {
    val density = LocalDensity.current
    val cornerPx = with(density) { WindowCorner.toPx() }
    val armPx = with(density) { CornerArm.toPx() }
    val windowPx = with(density) { WindowSize.toPx() }
    val strokePx = with(density) { 3.dp.toPx() }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // 离屏一层，BlendMode.Clear 才能「掏空」遮罩而不是抹掉相机预览
            .graphicsLayer { alpha = 0.99f },
    ) {
        val left = (size.width - windowPx) / 2
        val top = (size.height - windowPx) / 2
        val right = left + windowPx
        val bottom = top + windowPx

        drawRect(color = Color.Black.copy(alpha = 0.55f))
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(left, top),
            size = Size(windowPx, windowPx),
            cornerRadius = CornerRadius(cornerPx),
            blendMode = BlendMode.Clear,
        )

        // 四角框（L 形，圆头线帽）
        val c = ToolboxColors.Accent
        // 左上
        drawLine(c, Offset(left, top + armPx), Offset(left, top), strokePx, cap = StrokeCap.Round)
        drawLine(c, Offset(left, top), Offset(left + armPx, top), strokePx, cap = StrokeCap.Round)
        // 右上
        drawLine(c, Offset(right - armPx, top), Offset(right, top), strokePx, cap = StrokeCap.Round)
        drawLine(c, Offset(right, top), Offset(right, top + armPx), strokePx, cap = StrokeCap.Round)
        // 左下
        drawLine(c, Offset(left, bottom - armPx), Offset(left, bottom), strokePx, cap = StrokeCap.Round)
        drawLine(c, Offset(left, bottom), Offset(left + armPx, bottom), strokePx, cap = StrokeCap.Round)
        // 右下
        drawLine(c, Offset(right - armPx, bottom), Offset(right, bottom), strokePx, cap = StrokeCap.Round)
        drawLine(c, Offset(right, bottom - armPx), Offset(right, bottom), strokePx, cap = StrokeCap.Round)
    }
}
