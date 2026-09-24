package com.imagedge.camera.feature.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.core.permission.PermissionGate
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.theme.OnViewer
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.ViewerBackdrop
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.components.StatusBanner
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.data.ble.BleShutterState
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.feature.control.monitoring.MonitoringWorkstation
import com.imagedge.camera.feature.control.monitoring.ViewportTransform
import com.imagedge.camera.feature.control.monitoring.drawFrame
import com.imagedge.camera.feature.control.monitoring.drawMarkers
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 遥控拍摄二级页（实时取景 + PTP 快门 + PTP DeviceProp 参数区）
 *     version: 2.0 —— 参数区改为能力驱动三态渲染（T0）
 *     note   : 实时取景走相机 60152 LiveView；快门经 BLE（优先）或 PTP InitiateCapture；
 *              参数（ISO/光圈/快门/照相模式/白平衡/曝光补偿）经 PTP DeviceProp（0x9205/0x9209）
 *              调节，是否可调、有哪些档位**全部以相机上报的描述符为准**，
 *              不依赖 Camera Remote API（ZV-E10 无此服务）。
 * </pre>
 */

/** 参数区展示顺序与标题（能力项 → 标题字符串资源） */
private val PARAM_ROWS = listOf(
    CameraCapability.EXPOSURE_PROGRAM_MODE to R.string.control_shoot_mode,
    CameraCapability.ISO to R.string.control_iso,
    CameraCapability.F_NUMBER to R.string.control_fnumber,
    CameraCapability.SHUTTER_SPEED to R.string.control_shutter,
    CameraCapability.WHITE_BALANCE to R.string.control_wb,
    CameraCapability.EXPOSURE_BIAS to R.string.control_eb
)

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
    var workstationOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 蓝牙权限：API 31+ 用 CONNECT/SCAN；29/30 用定位（manifest 已按版本声明，
    // 请求未声明的权限会被系统静默拒绝——必须按版本区分）
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startBleScan()
        } else {
            // 被拒：顶部弹窗诚实说明缺的是哪个权限、用来做什么。
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

    // 进入页面即进入工作态（建相册基线 + 探测能力 + 读参数），不阻塞 UI；
    // 取景帧由 ViewModel 的单一采集 Job 提供（见 CameraControlViewModel.frame）
    LaunchedEffect(Unit) {
        viewModel.connect()
    }
    // BLE 扫描、配对广播和 GATT 只服务于当前遥控页。离页立即释放，
    // 避免长时间占用蓝牙连接槽和在后台意外控制相机。
    DisposableEffect(Unit) {
        onDispose { viewModel.leaveScreen() }
    }
    // 采集开关由页面可见性驱动：ViewModel 感知不到生命周期，而退到后台必须
    // 真的停掉 60152 采集（不是只停渲染），否则射频与耗电都还在跑。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setViewfinderVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setViewfinderVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setViewfinderVisible(false)
        }
    }

    // 监看工作台是对话框（独立 window），放在这里即可整屏覆盖，
    // 不需要把下面那棵 Scaffold 重排成 Box 兄弟节点
    if (workstationOpen) {
        MonitoringWorkstation(
            viewModel = viewModel,
            onExit = { workstationOpen = false }
        )
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
            // 断连提示横幅（内容首部，视口内始终可见）：未连接即显示，
            // 连接中切「重连中…」文案并禁用点击，等效 AlbumScreen 的防抖守卫；
            // ViewModel.connect() 内部另有 connecting 去重，重复点击不会发起并发连接
            if (!state.isConnected) {
                StatusBanner(
                    message = stringResource(R.string.album_disconnected_banner),
                    actionLabel = stringResource(
                        if (state.connecting) R.string.album_disconnected_reconnecting
                        else R.string.album_disconnected_retry
                    ),
                    onAction = { if (!state.connecting) viewModel.connect() },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.S)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 实时取景（电脑遥控/智能手机连接模式下相机开放 LiveView 流）──
                    LiveViewPreview(
                        viewModel = viewModel,
                        // 工作台打开时这块画面被完全遮住，连绘制都不必再挂着我们白烧一帧
                        active = !workstationOpen
                    )
                    AppButton(
                        text = stringResource(R.string.monitoring_enter),
                        subtitle = stringResource(R.string.monitoring_entry_desc),
                        onClick = { workstationOpen = true },
                        leadingIcon = Lucide.Maximize,
                        type = AppButtonType.SECONDARY
                    )

                    // ── 蓝牙遥控快门连接区（位于取景与快门之间：连接动作紧邻拍摄操作）──
                    when (val ble = bleState) {
                        is BleShutterState.Connected -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.ble_connected_prefix) + ble.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                AppLink(
                                    text = stringResource(R.string.ble_disconnect_btn),
                                    onClick = viewModel::disconnectBle,
                                )
                            }
                            // 相机实时状态（BLE ff02 通知：对焦/快门/录像）。
                            // 三态：null = 未知（尚未收到通知或已断线），不能显示成「否」
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatusChip(stringResource(R.string.control_status_focus), cameraStatus.focus)
                                StatusChip(stringResource(R.string.control_status_shutter), cameraStatus.shutter)
                                StatusChip(stringResource(R.string.control_status_recording), cameraStatus.recording)
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
                            AppButton(
                                text = stringResource(R.string.ble_connect_btn),
                                onClick = blePermissionRequest,
                                leadingIcon = Lucide.Bluetooth
                            )
                            Text(
                                text = stringResource(R.string.ble_connect_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── 遥控快门（蓝牙已连走 BLE；否则降级 PTP，且需通道声明支持遥控拍摄）──
                    // 手势式快门：按下开始拍摄（半按对焦+全按），松开结束曝光；
                    // 按住期间相机按自身连拍设置持续曝光（长按连拍）
                    val bleConnected = bleState is BleShutterState.Connected
                    val shutterEnabled = !state.taking && (bleConnected || state.captureAvailable)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (shutterEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
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
                                color = if (shutterEnabled) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 已连接但通道不支持遥控拍摄（UPnP「发送到智能手机」模式）：明说，
                    // 而不是让快门按下去毫无反应
                    if (state.isConnected && !state.captureAvailable && !bleConnected) {
                        Text(
                            text = stringResource(R.string.control_shutter_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ── 录像切换（仅蓝牙遥控可用）──
                    if (bleConnected) {
                        AppButton(
                            text = stringResource(R.string.control_btn_record),
                            onClick = { viewModel.recordToggle() },
                            leadingIcon = Lucide.Video,
                            type = AppButtonType.SECONDARY
                        )
                    }

                    // ── PTP DeviceProp 参数区（经 0x9205/0x9209 调节）──
                    // 分组标题：明确这块是"参数"，与上面的快门/录像区分开
                    Text(
                        text = stringResource(R.string.control_params_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 相机身份：能力快照按「型号 + 固件 + 连接方式 + 功能模式」归档，
                    // 下面每一项能不能调都由它决定，必须让用户看见当前是哪台相机
                    IdentitySummary(state.identity)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PARAM_ROWS.forEach { (capability, labelRes) ->
                                CapabilityParamRow(
                                    label = stringResource(labelRes),
                                    param = state.params[capability],
                                    stale = state.capabilitiesStale,
                                    onSelect = { raw -> dispatch(viewModel, capability, raw) }
                                )
                            }
                        }
                    }
                }
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

/** 把选项原始值派发到对应的 ViewModel 设置方法 */
private fun dispatch(viewModel: CameraControlViewModel, capability: CameraCapability, raw: Long) {
    when (capability) {
        CameraCapability.ISO -> viewModel.setIso(raw)
        CameraCapability.F_NUMBER -> viewModel.setFNumber(raw)
        CameraCapability.SHUTTER_SPEED -> viewModel.setShutterSpeed(raw)
        CameraCapability.EXPOSURE_PROGRAM_MODE -> viewModel.setShootMode(raw)
        CameraCapability.WHITE_BALANCE -> viewModel.setWhiteBalance(raw)
        CameraCapability.EXPOSURE_BIAS -> viewModel.setExposureBias(raw)
        CameraCapability.CAPTURE -> Unit
    }
}

/**
 * 遥控页的嵌入取景预览。
 *
 * 单独成一个 composable 有两个理由：
 * ① 取景帧以约 20fps 变化。若在读帧的位置就地组合，失效范围是**整张卡片**——
 *    身份行、BLE 行、快门、参数区都跟着每帧重组。收进本函数后只有它自己重来。
 * ② 监看工作台打开时这块画面被完全遮住，[active] 为 false 后连 Canvas 都不再挂进组合。
 *
 * 显示复用监看工作台的 `drawFrame`/`drawMarkers`：两处各写一套的话，
 * 旋转与镜像一定会在两个界面之间对不上。
 */
@Composable
private fun LiveViewPreview(viewModel: CameraControlViewModel, active: Boolean) {
    val settings by viewModel.monitoringSettings.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val image = remember(frame) { frame?.asImageBitmap() }
    // semantics 块不是 composable 上下文，字符串先在外层解析好
    val description = stringResource(R.string.control_liveview)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(MaterialTheme.shapes.small)
            .background(ViewerBackdrop),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            // Canvas 是纯绘制，读屏软件拿不到内容，必须显式给语义
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = description }
            ) {
                val bitmap = frame
                val source = image
                if (bitmap == null || source == null) return@Canvas
                val dimensions = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                val viewport = Size(this.size.width, this.size.height)
                val view = ViewportTransform(
                    rotation = settings.rotation,
                    mirrored = settings.mirrored
                )
                val content = view.contentRect(dimensions, viewport)
                if (content.width <= 0f) return@Canvas
                drawFrame(source, view, dimensions, viewport)
                drawMarkers(content, settings.gridMode, settings.aspectMarker)
            }
        }
        if (active && frame == null) {
            Text(
                text = stringResource(R.string.control_liveview_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = OnViewer
            )
        }
    }
}

/**
 * 相机身份摘要（型号 · 固件 · 传输方式 · 功能模式）。
 *
 * internal：监看工作台同样要显示它——用户在那里发现参数不可调时，
 * 第一个要确认的就是「我连的到底是哪台机器、什么模式」。
 * 未连接时明确显示「未连接」，而不是留白让用户以为参数区坏了。
 */
@Composable
internal fun IdentitySummary(identity: CameraIdentity) {
    val text = if (!identity.isKnown) {
        stringResource(R.string.control_identity_unknown)
    } else {
        buildList {
            add(identity.model)
            if (identity.firmware.isNotBlank()) {
                add(stringResource(R.string.control_identity_firmware, identity.firmware))
            }
            identity.transport?.let { add(it.label()) }
            when (identity.mode) {
                CameraIdentity.MODE_REMOTE_CONTROL -> add(stringResource(R.string.control_identity_mode_remote))
                CameraIdentity.MODE_CONTENTS_TRANSFER -> add(stringResource(R.string.control_identity_mode_fullcard))
            }
        }.joinToString(" · ")
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 传输方式展示名（协议名本身就是用户能核对的事实，不做意译）。
 *
 * internal：档案页按 (传输方式, 功能模式) 展示历史能力快照，两处必须叫同一个名字。
 */
internal fun CameraTransport.label(): String = when (this) {
    CameraTransport.PTP_IP -> "PTP/IP"
    CameraTransport.UPNP -> "UPnP"
}

/**
 * 单个拍摄参数行（三态渲染）。
 *
 * 可写且相机给了档位 → 下拉选择器；其余一律只读文本 + 原因说明。
 * 「未知」必须与「不支持」分开显示：前者是链路/探测问题（重连可能就好），
 * 后者才是相机给出的事实。两者都禁用控件，但用户需要知道该怪谁。
 */
@Composable
private fun CapabilityParamRow(
    label: String,
    param: ParamUiState?,
    stale: Boolean,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = param?.options.orEmpty()
    val editable = param?.editable == true && options.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
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
            if (editable) {
                Box {
                    AppLink(
                        text = param.currentLabel ?: "--",
                        onClick = { expanded = true }
                    )
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
            } else {
                Text(
                    text = param?.currentLabel ?: "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!editable) {
            Text(
                text = stringResource(blockReasonOf(param, stale)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 不可调整的原因 → 文案资源（顺序即优先级） */
private fun blockReasonOf(param: ParamUiState?, stale: Boolean): Int = when {
    param == null -> R.string.control_capability_unknown
    param.detail.state == CapabilityState.UNKNOWN -> R.string.control_capability_unknown
    param.detail.state == CapabilityState.UNSUPPORTED -> R.string.control_capability_unsupported
    param.detail.state == CapabilityState.READ_ONLY -> R.string.control_capability_read_only
    stale -> R.string.control_capability_stale
    else -> R.string.control_capability_no_options
}

/**
 * 状态小标签（三态）。
 *
 * null = 未知（尚未收到 ff02 通知，或 BLE 已断开）。断线后显示「未录像」是伪造事实——
 * 相机可能仍在录制，只是我们看不见了，因此未知必须在视觉上与「否」区分开。
 */
@Composable
private fun StatusChip(label: String, active: Boolean?) {
    val textColor: Color
    val background: Color
    when (active) {
        true -> {
            textColor = MaterialTheme.colorScheme.primary
            background = MaterialTheme.colorScheme.primaryContainer
        }
        false -> {
            textColor = MaterialTheme.colorScheme.onSurfaceVariant
            background = MaterialTheme.colorScheme.surfaceVariant
        }
        null -> {
            textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    }
    Text(
        text = if (active == null) {
            "$label · ${stringResource(R.string.control_status_unknown)}"
        } else {
            label
        },
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier
            .background(background, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
