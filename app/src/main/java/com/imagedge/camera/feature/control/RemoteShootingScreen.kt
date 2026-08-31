package com.imagedge.camera.feature.control

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.core.permission.PermissionGate
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.components.StatusBanner
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.data.ble.BleShutterState
import com.imagedge.camera.data.model.CameraSettings
import androidx.compose.ui.platform.LocalContext

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 遥控拍摄二级页（实时取景 + PTP 快门 + PTP DeviceProp 参数区）
 *     version: 1.1
 *     note   : 实时取景走相机 60152 LiveView；快门经 BLE（优先）或 PTP InitiateCapture；
 *              参数（ISO/光圈/快门）经 PTP DeviceProp（0x9207/0x9209）调节，
 *              不依赖 Camera Remote API（ZV-E10 无此服务）。
 * </pre>
 */

/**
 * 遥控拍摄屏幕
 * @param onBack 返回主页回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteShootingScreen(
    onBack: () -> Unit = {},
    snackbarController: SnackbarController? = null,
    viewModel: CameraControlViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    val cameraStatus by viewModel.cameraStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 蓝牙权限：API 31+ 用 CONNECT/SCAN；29/30 用定位（manifest 已按版本声明，
    // 请求未声明的权限会被系统静默拒绝——必须按版本区分）
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startBleScan()
        } else {
            // 被拒：顶部弹窗诚实说明缺的是哪个权限、用来做什么
            // （首次启动已统一申请过，这里只说明用途，不重复弹系统框）
            val denied = grants.filterValues { !it }.keys.firstOrNull()
            if (denied != null && snackbarController != null) {
                PermissionGate.check(context, denied, snackbarController)
            }
            viewModel.notifyBlePermissionDenied()
        }
    }

    val blePermissionRequest: () -> Unit = {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        blePermissionLauncher.launch(permissions)
    }

    // 进入页面即进入工作态（建相册基线 + 读参数），不阻塞 UI；
    // LiveView 由 UI collect liveViewFrames 时按需连接（60152 裸流）
    LaunchedEffect(Unit) {
        viewModel.connect()
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.remote_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                // 页面可滚动：小屏/横屏时拍摄参数不被截断
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 实时取景（电脑遥控/智能手机连接模式下相机开放 LiveView 流）──
                    val liveFrame: Bitmap? by viewModel.liveViewFrames
                        .collectAsStateWithLifecycle(initialValue = null)
                    val frame = liveFrame
                    if (frame != null) {
                        Image(
                            bitmap = frame.asImageBitmap(),
                            contentDescription = stringResource(R.string.control_liveview),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 2f),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.control_liveview_waiting),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // ── 蓝牙遥控快门连接区（位于取景与快门之间：连接动作紧邻拍摄操作）──
                    when (val ble = bleState) {
                        is BleShutterState.Connected -> {
                            Text(
                                text = stringResource(R.string.ble_connected_prefix) + ble.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // 相机实时状态（BLE ff02 通知：对焦/快门/录像）
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusChip("对焦", cameraStatus.focus)
                                StatusChip("快门", cameraStatus.shutter)
                                StatusChip("录像", cameraStatus.recording)
                            }
                        }
                        is BleShutterState.Scanning -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.ble_scanning),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is BleShutterState.Connecting -> {
                            Text(
                                text = stringResource(R.string.ble_connecting_prefix) + ble.name,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> {
                            Button(
                                onClick = blePermissionRequest,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small
                            ) {
                                LucideIcon(Lucide.Bluetooth, contentDescription = null, size = 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ble_connect_btn))
                            }
                            Text(
                                text = stringResource(R.string.ble_connect_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── 遥控快门（蓝牙已连走 BLE；否则降级 PTP）──
                    // 手势式快门：按下开始拍摄（半按对焦+全按），松开结束曝光；
                    // 按住期间相机按自身连拍设置持续曝光（长按连拍）
                    val shutterEnabled = !state.taking &&
                        (bleState is BleShutterState.Connected || state.isConnected)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(shutterEnabled) {
                                    detectTapGestures(
                                        onPress = {
                                            if (shutterEnabled) {
                                                viewModel.shutterDown()   // 按下：对焦
                                                tryAwaitRelease()
                                                viewModel.shutterUp()     // 抬起：拍摄
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.control_btn_shoot),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    // ── 录像切换（仅蓝牙遥控可用）──
                    if (bleState is BleShutterState.Connected) {
                        Button(
                            onClick = { viewModel.recordToggle() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small
                        ) {
                            LucideIcon(Lucide.Video, contentDescription = null, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.control_btn_record))
                        }
                    }

                    // ── PTP DeviceProp 参数区（ISO/光圈/快门，经 PTP 0x9205/0x9209 调节）──
                    // 分组标题：明确这块是"参数"，与上面的快门/录像区分开
                    Text(
                        text = stringResource(R.string.control_params_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 照相模式（官方称呼）：实时读取 0x500E ExposureProgramMode
                            // 并映射官方名称（M/A/S/P/AUTO/STILL/MOVIE）。
                            // 相机经 0x9209 上报该属性可写（GetSet=0x01）且带 supported
                            // 枚举表时（官方 APP 同款），渲染为下拉选择器经 0x9205 切换；
                            // 否则保持只读展示（需在相机上切换）。
                            if (state.shootModeOptions.isNotEmpty()) {
                                ParamSelectorCode(
                                    label = stringResource(R.string.control_shoot_mode),
                                    currentLabel = state.shootModeLabel,
                                    options = state.shootModeOptions,
                                    onSelect = viewModel::setShootMode
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.control_shoot_mode),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = state.shootModeLabel
                                            ?: stringResource(R.string.control_shoot_mode_on_camera),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.shootModeLabel != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            // ISO/光圈/快门：与照相模式/白平衡/曝光补偿同款能力驱动选择器。
                            // 可选项来自相机 0x9209 上报的 supported 枚举表（换镜头/机型档位自动变化），
                            // supported 为空时回退到硬编码预设（CameraPresets.*_PRESETS）。
                            ParamSelectorCode(
                                label = stringResource(R.string.control_iso),
                                currentLabel = state.isoRaw?.let { CameraSettings.formatIso(it) },
                                options = state.isoOptions,
                                onSelect = viewModel::setIso
                            )
                            ParamSelectorCode(
                                label = stringResource(R.string.control_fnumber),
                                currentLabel = state.fNumberRaw?.let { CameraSettings.formatFNumber(it) },
                                options = state.fNumberOptions,
                                onSelect = viewModel::setFNumber
                            )
                            ParamSelectorCode(
                                label = stringResource(R.string.control_shutter),
                                currentLabel = state.shutterRaw?.let { CameraSettings.formatShutter(it) },
                                options = state.shutterOptions,
                                onSelect = viewModel::setShutterSpeed
                            )
                            // ── 扩展参数（值表来自官方 PlayMemories 逆向）──
                            ParamSelectorCode(
                                label = stringResource(R.string.control_wb),
                                currentLabel = state.whiteBalance,
                                options = CameraPresets.WB_OPTIONS,
                                onSelect = viewModel::setWhiteBalance
                            )
                            ParamSelectorCode(
                                label = stringResource(R.string.control_eb),
                                currentLabel = state.exposureBias,
                                options = CameraPresets.EB_OPTIONS,
                                onSelect = viewModel::setExposureBias
                            )
                        }
                    }
                }
            }

            // 断连提示横幅：未连接且未在尝试连接时给出「重连」入口。
            // 连接尝试中（state.connecting）横幅整体隐藏，等效 AlbumScreen 的防抖守卫；
            // ViewModel.connect() 内部另有 connecting 去重，重复点击不会发起并发连接
            if (!state.isConnected && !state.connecting) {
                StatusBanner(
                    message = stringResource(R.string.album_disconnected_banner),
                    actionLabel = stringResource(R.string.album_disconnected_retry),
                    onAction = { viewModel.connect() },
                    modifier = Modifier.padding(12.dp)
                )
            }

            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 状态小标签（激活=高亮容器色，未激活=暗淡）
 */
@Composable
private fun StatusChip(label: String, active: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * 带原始值映射的参数选择器（白平衡/曝光补偿等枚举参数）：
 * 显示官方名称标签，选择时把对应原始值回传给 ViewModel（0x9205 设置）。
 */
@Composable
private fun ParamSelectorCode(
    label: String,
    currentLabel: String?,
    options: List<Pair<String, Long>>,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = currentLabel ?: "--",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (name, code) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
