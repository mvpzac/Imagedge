package com.imagedge.camera.feature.connection

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import com.imagedge.camera.R
import com.imagedge.camera.core.permission.PermissionGate
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.ui.feedback.SnackbarController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 日志 TAG（logcat：CamRemote-qrscan） */
private const val TAG = "qrscan"

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 扫码连接（半屏弹窗内容）——扫描相机屏幕的连接二维码，解析 SSID/密码自动配网。
 *              宿主为主页的 ModalBottomSheet（对齐系统扫码半屏弹窗的交互形态）。
 *     version: 2.0
 * </pre>
 */

/** 二维码解码器（复用 reader 实例，线程封闭于分析协程） */
private fun newQrReader() = MultiFormatReader().apply {
    setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            // TRY_HARDER：对模糊/低对比码做更多采样与旋转尝试，提升暗光成功率
            DecodeHintType.TRY_HARDER to true
        )
    )
}

/**
 * 中心 ROI 占比（边长 / 短边）。
 *
 * 二维码在取景框中央，只取中心区域有两个好处：
 * 1. 像素量降到约 49%，Otsu 直方图与二值化开销同比下降；
 * 2. 直方图不再被四周暗背景拉偏、阈值更准——这正是原注释承诺但从未实现的行为。
 */
private const val QR_ROI_RATIO = 0.7f

/** YUV（ImageProxy）→ 亮度平面 → zxing 来源（中心 ROI 裁剪） */
private fun ImageProxy.toLuminanceSource(): PlanarYUVLuminanceSource? {
    val yPlane = planes[0]
    val buffer = yPlane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    // 取景方向由 Preview 层校正；解码平面直接使用原始宽高
    val crop = (minOf(width, height) * QR_ROI_RATIO).toInt()
    if (crop <= 0) return null
    val left = ((width - crop) / 2).coerceAtLeast(0)
    val top = ((height - crop) / 2).coerceAtLeast(0)
    return PlanarYUVLuminanceSource(bytes, width, height, left, top, crop, crop, false)
}

/**
 * 解码一帧；非 QR/失败返回 null。
 *
 * 先跑正常相位，**失败才跑反相**（白码黑底是少数场景）。原先无条件 ×2 意味着每帧
 * 都要做两遍全帧 Otsu 二值化 + zxing 解码，是扫码 CPU 满载的主因之一。
 */
private fun decodeFrame(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val source = proxy.toLuminanceSource() ?: return null
    return try {
        decodeOnce(reader, source, invert = false) ?: decodeOnce(reader, source, invert = true)
    } finally {
        try { reader.reset() } catch (_: Exception) {}
    }
}

/** 单次解码尝试（失败返回 null，不抛异常） */
private fun decodeOnce(
    reader: MultiFormatReader,
    source: LuminanceSource,
    invert: Boolean
): String? = try {
    reader.decodeWithState(BinaryBitmap(OtsuBinarizer(source, invert)))?.text
} catch (_: Exception) {
    null
}

/**
 * 图像分析专用后台线程。
 *
 * CameraX 的 analyzer 运行在传给 setAnalyzer 的 Executor 上。原先传的是
 * `ContextCompat.getMainExecutor(ctx)`，把全帧二值化 + 解码跑在**主线程**，
 * 单帧耗时可达数百毫秒 → 界面触摸无响应、系统 ANR、手机发烫。必须走后台单线程。
 *
 * 用**守护线程**且为进程级单例：不随弹窗销毁（避免二次打开时 RejectedExecutionException），
 * 守护线程不会阻止进程退出，因此也不需要在 DisposableEffect 里 shutdown。
 */
private val qrAnalyzerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "qr-analyzer").apply { isDaemon = true }
}

/**
 * 数字变焦倍率：放大让二维码占满画面——测光自然以屏幕亮度为准（不再被四周黑暗
 * 牵着过曝），且二维码模块更大更易解码。2x 温和起手，真机按需微调。
 */
private const val QR_ZOOM_RATIO = 2.0f

/**
 * 大津法（Otsu）全局阈值二值化器。
 * 暗光扫码场景：二维码是「黑码白底」双峰分布，Otsu 自适应求阈值，比
 * HybridBinarizer（局部块 + 全局混合）在过曝/欠曝边缘更稳；且配合中心 ROI，
 * 直方图不会被四周暗背景拉偏。
 * @param invert 反相模式：白码黑底时黑块在阈值以上（>threshold 判黑）。
 */
private class OtsuBinarizer(
    private val source: LuminanceSource,
    private val invert: Boolean
) : Binarizer(source) {

    private val threshold: Int by lazy(LazyThreadSafetyMode.NONE) { computeOtsu() }

    private fun computeOtsu(): Int {
        val w = source.width
        val h = source.height
        val total = w * h
        if (total <= 0) return 127
        val hist = IntArray(256)
        val row = ByteArray(w)
        for (y in 0 until h) {
            source.getRow(y, row)
            for (x in 0 until w) hist[row[x].toInt() and 0xFF]++
        }
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var maxVar = -1.0
        var best = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                best = i
            }
        }
        return best
    }

    private fun isBlack(v: Int): Boolean =
        if (invert) v > threshold else v < threshold

    override fun getBlackRow(y: Int, row: BitArray?): BitArray {
        val r = row ?: BitArray(source.width)
        r.clear()
        val buf = ByteArray(source.width)
        source.getRow(y, buf)
        for (x in 0 until source.width) {
            if (isBlack(buf[x].toInt() and 0xFF)) r.set(x)
        }
        return r
    }

    override fun getBlackMatrix(): BitMatrix {
        val m = BitMatrix(source.width, source.height)
        val buf = ByteArray(source.width)
        for (y in 0 until source.height) {
            source.getRow(y, buf)
            for (x in 0 until source.width) {
                if (isBlack(buf[x].toInt() and 0xFF)) m.set(x, y)
            }
        }
        return m
    }

    override fun createBinarizer(source: LuminanceSource): Binarizer = OtsuBinarizer(source, invert)
}

/**
 * 扫码连接半屏弹窗内容（配网权限由 Manifest 声明，相机权限在此页内请求）。
 * @param onConnected 配网成功回调（宿主关闭弹窗，用户回主页点「连接」进入相机）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanSheetContent(
    onConnected: () -> Unit = {},
    snackbarController: SnackbarController? = null,
    viewModel: QrScanViewModel = hiltViewModel()
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 相机运行时权限：未授权时 CameraX bind 会失败（黑屏），必须先请求
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            // 未授予（首次进入被拒 / 用户后续关闭）：顶部弹窗说明用途后再申请，
            // 避免用户面对一个「无声黑屏」却不知道缺什么权限
            if (snackbarController != null) {
                PermissionGate.check(
                    context,
                    android.Manifest.permission.CAMERA,
                    snackbarController
                ) { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) }
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    val reader = remember { newQrReader() }
    // 持有 CameraX provider，弹窗关闭时必须 unbind：
    // 否则预览销毁后 ImageAnalysis 仍在跑，二次打开会因重复 bind 导致黑屏/绑定失败
    val cameraProviderRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    // 弹窗存活标记：CameraX 是异步就绪的，若在就绪前就关闭弹窗，需阻止后续 bind
    val sheetAlive = remember { AtomicBoolean(true) }

    // 连接成功后自动关闭弹窗（consumeSuccess 一次性，避免旋转重复触发）
    LaunchedEffect(uiState) {
        if (viewModel.consumeSuccess()) {
            kotlinx.coroutines.delay(800)
            onConnected()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sheetAlive.set(false)
            runCatching { cameraProviderRef.getAndSet(null)?.unbindAll() }
            viewModel.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 不设标题：主页按钮已写「扫码连接」，重复文案只浪费弹窗高度；
        // dragHandle 保留指示可拖动关闭，状态行即弹窗首行内容

        // 状态区：弹窗首行、取景区上方，高度恒定预留。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(QR_STATUS_HEIGHT),
            contentAlignment = Alignment.Center
        ) {
            when (val s = uiState) {
                is QrScanUiState.Error -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QrStatusText(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.reset() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.qr_retry),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                is QrScanUiState.Connecting -> QrStatusText(
                    text = stringResource(R.string.qr_connecting, s.ssid),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is QrScanUiState.Success -> QrStatusText(
                    text = stringResource(R.string.qr_success, s.ssid),
                    color = MaterialTheme.colorScheme.primary
                )
                else -> QrStatusText(
                    text = stringResource(R.string.qr_waiting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 取景区：正方形取景窗，整体缩小、贴顶放置（上移），底部留白与两侧均衡。
        // 几何：左右缝 = (W-s)/2，底缝 = H-s；严格相等要求 s = 2H-W，
        // 在 40% 弹窗高度下该解会小于 0（宽 > 两倍高），故取「短边正方形 × 0.9」
        // 的近似：窗贴顶上移，底部留白自然增大，视觉重心均衡。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val side = minOf(maxWidth, maxHeight) * 0.88f
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(side)
                    .clip(RoundedCornerShape(Radius.Container))
            ) {
                if (!cameraGranted) {
                    Text(
                        text = stringResource(R.string.qr_camera_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                if (cameraGranted) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            // 半屏弹窗有滑入/滑出位移动画：SurfaceView 在动画容器里会出现
                            // z-order 与位置错乱（画面漂移/穿层），强制走 TextureView
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            // FILL_CENTER：填满取景框放大画面，二维码更大更易对准/解码；
                            // 解码走 ImageAnalysis 完整帧，此处的裁切只影响显示不影响解码
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        // 解码节流：单帧解码完成前丢弃后续帧，避免积压加速扫描
                        val decoding = AtomicBoolean(false)
                        val bound = AtomicBoolean(false)
                        // 分析是否仍在运行（弹窗关闭后置 false，异步回调据此跳过后续处理）
                        val analyzing = AtomicBoolean(true)
                        // 弹窗销毁时回收 executor 与 analyzing 标记，避免线程泄漏
                        previewView.addOnAttachStateChangeListener(
                            object : android.view.View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: android.view.View) {}
                                override fun onViewDetachedFromWindow(v: android.view.View) {
                                    analyzing.set(false)
                                }
                            }
                        )

                        // 关键：必须等 PreviewView 完成 layout（拿到真实尺寸）再绑定相机。
                        // 二次打开时 ProcessCameraProvider 已缓存，getInstance 会立即回调，
                        // 若此刻 view 尚未 layout（尺寸 0），CameraX 会按错误尺寸算出变换矩阵，
                        // 表现为预览画面整体上飘——这是「下滑关闭再打开后取景器飘」的根因。
                        previewView.doOnLayout { v ->
                            if (v.width <= 0 || v.height <= 0) return@doOnLayout
                            if (!bound.compareAndSet(false, true)) return@doOnLayout
                            if (!sheetAlive.get()) return@doOnLayout
                            AppLog.i(TAG, "PreviewView 就绪 ${v.width}x${v.height}，绑定相机")
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                                cameraProviderRef.set(provider)
                                // 弹窗在相机就绪前已关闭（用户快速下滑）：立即解绑，避免空跑
                                if (!sheetAlive.get()) {
                                    runCatching { provider.unbindAll() }
                                    return@addListener
                                }
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(
                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                    )
                                    .setResolutionSelector(
                                        ResolutionSelector.Builder()
                                            .setResolutionStrategy(
                                                ResolutionStrategy(
                                                    android.util.Size(1280, 720), // 降分辨率加速解码
                                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                                )
                                            )
                                            .build()
                                    )
                                    .build()
                                // 后台单线程执行：绝不能用 MainExecutor（会导致主线程阻塞 / ANR）
                                analysis.setAnalyzer(qrAnalyzerExecutor) { proxy ->
                                    if (!decoding.compareAndSet(false, true)) {
                                        proxy.close()
                                        return@setAnalyzer
                                    }
                                    // decoding 必须在 finally 复位：原先若 decodeFrame 抛异常
                                    // （OOM、zxing 内部错误等），标志会永久为 true，
                                    // 之后所有帧被丢弃 → 扫码功能静默失效。
                                    val decoded = try {
                                        decodeFrame(reader, proxy)
                                    } finally {
                                        proxy.close()
                                        decoding.set(false)
                                    }
                                    if (decoded != null && analyzing.get() && sheetAlive.get()) {
                                        viewModel.onQrContent(decoded)
                                    }
                                }
                                try {
                                    provider.unbindAll()
                                    val camera = provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis
                                    )
                                    // 数字变焦放大：二维码占满画面，测光自然以屏幕为准 + 码更大易解码
                                    runCatching {
                                        camera.cameraControl.setZoomRatio(QR_ZOOM_RATIO)
                                    }
                                } catch (e: Exception) {
                                    AppLog.e(TAG, "相机绑定失败", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 扫码框（四角 L 形角标，中间透明对准二维码）：容器已是正方形，角标直接贴边
            QrScanFrame()
            // 连接状态由取景框下方的状态文字提示，不在取景区叠加转圈动画
            }
        }

    }
}

/** 状态区预留高度（一行状态文字 + Error 时同行的 TextButton，取 Material3 TextButton 最小高度 40dp） */
private val QR_STATUS_HEIGHT = 40.dp

/** 单行状态文字：maxLines=1 + 省略号，保证任何文案都不会撑高预留区 */
@Composable
private fun QrStatusText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 扫码框：四角 L 形角标（中间透明对准二维码）。
 * 取景窗已由外部约束为正方形；角标留 16dp 内衬悬浮在窗内，不贴边缘（更美观，
 * 也避免顶到圆角裁切）。
 */
@Composable
private fun QrScanFrame(modifier: Modifier = Modifier) {
    val cornerColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height
        // 弯头弧度收小（12dp）：比弹窗圆角更挺直，但保留圆角呼应
        val r = 12.dp.toPx()
        val cornerLen = r + 14.dp.toPx()
        val strokeWidth = 5.dp.toPx()

        fun drawCorner(path: Path) {
            drawPath(path, cornerColor, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        }

        // 左上：(0,leg)→(0,r)→弧→(r,0)→(leg,0)
        drawCorner(Path().apply {
            moveTo(0f, cornerLen); lineTo(0f, r)
            arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            lineTo(cornerLen, 0f)
        })
        // 右上
        drawCorner(Path().apply {
            moveTo(w - cornerLen, 0f); lineTo(w - r, 0f)
            arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
            lineTo(w, cornerLen)
        })
        // 左下
        drawCorner(Path().apply {
            moveTo(0f, h - cornerLen); lineTo(0f, h - r)
            arcTo(androidx.compose.ui.geometry.Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
            lineTo(cornerLen, h)
        })
        // 右下：从右侧直边起笔，保证 arcTo 起点与当前点重合（forceMoveTo=false
        // 会先 lineTo 弧首，方向不匹配会在角上画出斜线）
        drawCorner(Path().apply {
            moveTo(w, h - cornerLen); lineTo(w, h - r)
            arcTo(androidx.compose.ui.geometry.Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(w - cornerLen, h)
        })
    }
}

/**
 * 扫码连接半屏弹窗：从底部弹出/收回。ModalBottomSheet 默认动画为
 * tween + FastOutSlowInEasing（两端慢、中间快的加速度缓动），符合预期手感。
 * 宽度铺满，固定高度为屏幕的 40%，顶部圆角。
 * @param onDismiss 用户下滑/点外部关闭
 * @param onConnected 配网成功关闭后回调（宿主据此自动连接相机）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanDialog(
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
    snackbarController: SnackbarController? = null,
    viewModel: QrScanViewModel = hiltViewModel()
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 内容高度固定 40%，禁用半展开中间态：确保下滑只走 dismiss，
        // 不会停在 half-expanded 锚点导致内容被裁剪或产生相对位移
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = Radius.Sheet, topEnd = Radius.Sheet)
    ) {
        // 高度用父约束的 40%（而非 LocalConfiguration）：UI 锁定缩放下
        // Configuration 的 dp 值不随 density 缩放，约束值才是缩放一致的
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxHeight * 0.4f)
            ) {
                QrScanSheetContent(
                    onConnected = onConnected,
                    snackbarController = snackbarController,
                    viewModel = viewModel
                )
            }
        }
    }
}
