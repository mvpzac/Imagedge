package com.imagedge.camera.feature.control

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.lifecycle.ViewModel
import com.imagedge.camera.core.common.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.data.ble.BleShutterState
import com.imagedge.camera.data.ble.SonyBleShutter
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.CapabilityDetail
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.CameraSnapshot
import com.imagedge.camera.data.remote.ChannelConnectionState
import com.imagedge.camera.data.remote.LiveViewRepository
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 相机控制 ViewModel（连接 / 拍照 / 参数调节）
 *     version: 2.0 —— 参数控制全面改为能力驱动（T0）：不再有任何硬编码可写档位
 * </pre>
 */

/**
 * 单个拍摄参数在界面上的呈现。
 *
 * 三态由相机能力快照决定：可写且有档位 → 选择器；只读/不支持 → 只读文本；
 * 未知（未探测或读取超时）→ 禁用并解释。[editable] 为 false 时本 ViewModel 不下发命令，
 * [CameraRepository] 层还有同一份校验兜底——「界面禁用」与「不会发命令」必须是同一件事。
 */
data class ParamUiState(
    val currentLabel: String?,
    val options: List<Pair<String, Long>>,
    val detail: CapabilityDetail,
    val editable: Boolean
)

/** 控制状态 */
data class ControlState(
    val isConnected: Boolean = false,
    val connecting: Boolean = false,
    val taking: Boolean = false,
    val message: String? = null,
    /** 相机身份（型号 / 固件 / 传输方式 / 功能模式），未连接时为 [CameraIdentity.UNKNOWN] */
    val identity: CameraIdentity = CameraIdentity.UNKNOWN,
    /** 能力快照是否陈旧（本轮尚未成功探测）——此时只可展示，不可据此下发命令 */
    val capabilitiesStale: Boolean = true,
    /** 各拍摄参数的呈现状态（键为能力项，未探测时为空表） */
    val params: Map<CameraCapability, ParamUiState> = emptyMap(),
    /** PTP 遥控拍摄是否可用（BLE 快门是另一条独立通路，不由此项决定） */
    val captureAvailable: Boolean = false
)

/**
 * LiveView 取景目标尺寸（3:2）。
 *
 * 从 1280×854 下调到 960×640：单帧内存从 4.4MB 降到 2.4MB（-45%）。
 * 取景用途不需要更高分辨率（真机 960 宽已足够判断构图与对焦），
 * 而每帧都是新分配的软件位图，分辨率直接决定 GC 压力。
 */
private const val LIVEVIEW_TARGET_WIDTH = 960
private const val LIVEVIEW_TARGET_HEIGHT = 640

/**
 * LiveView 最小出帧间隔（约 20fps 上限）。
 *
 * 相机推流约 18~30fps，但手机端没必要全收：每帧都要解码 + 分配位图 + 触发重组，
 * 全速接收会让 GC 与 UI 线程持续高负载。20fps 对取景观感无差别，却把分配速率砍掉三成。
 */
private const val LIVEVIEW_MIN_FRAME_INTERVAL_MS = 50L

/**
 * 节流：保证两次发射之间至少间隔 [periodMs]，超出部分丢弃。
 *
 * 与 [kotlinx.coroutines.flow.conflate] 的区别：conflate 只在**下游来不及消费**时
 * 丢弃中间值（背压处理），本操作符是主动限速，从源头减少位图分配。
 */
private fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    var lastEmitAt = 0L
    collect { value ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEmitAt >= periodMs) {
            lastEmitAt = now
            emit(value)
        }
    }
}

@HiltViewModel
class CameraControlViewModel @Inject constructor(
    private val liveViewRepository: LiveViewRepository,
    private val cameraRepository: CameraRepository,
    private val bleShutter: SonyBleShutter,
    private val downloadManager: DownloadManager,
    private val haptics: Haptics
) : ViewModel() {

    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()

    /** BLE 快门连接状态（Disconnected/Scanning/Connecting/Connected） */
    val bleState = bleShutter.state

    /** 相机实时状态（ff02 通知：对焦/快门/录像，三态；断线后为未知而非「未录像」） */
    val cameraStatus = bleShutter.cameraStatus

    /** 已认知的相册内容指纹集合，用于「拍摄后增量拉取」差异对比 */
    private val lastKnownThumbKeys = mutableSetOf<String>()

    /**
     * 相册基线是否已就绪（P1-14）。
     *
     * [init] 里的 captureEvents 收集在 ViewModel 创建时就开始，而 [lastKnownThumbKeys]
     * 要等 [connect] 中 `listMedia()`（可达 10~30s）返回后才填充。这个窗口期内若收到
     * CaptureComplete，差集 = 整张相册 → `enqueueAll(全部)`，整卡模式下就是几千个任务
     * 瞬间入队（下载风暴 + UI 卡死）。基线未就绪时直接跳过自动拉回。
     */
    @Volatile
    private var baselineReady = false

    init {
        // 相机 CaptureComplete 事件 → 自动拉回刚拍的照片（P0-1 短期方案）
        viewModelScope.launch {
            cameraRepository.captureEvents.collect { _ -> refreshAfterCapture() }
        }
        // 参数双向同步：相机端拨盘/菜单改动参数 → 0xC203/0x4006 事件推送 →
        // 300ms 合并去抖 → 重读 0x9209 回显（手机显示始终以相机实际状态为准）。
        // 换镜头/换拍摄模式导致的档位变化同样经这条路径刷新。
        viewModelScope.launch {
            cameraRepository.propEvents
                .debounce(300)
                .collect { refreshCameraState() }
        }
        // 断线（保活失败 / 事务超时自愈 forceClose / 用户断开）→ 立即回到「未知」。
        // 否则界面会继续显示上一轮的可写档位，用户点下去才发现命令全部失败。
        viewModelScope.launch {
            cameraRepository.connectionState.collect { channelState ->
                val connected = channelState == ChannelConnectionState.CONNECTED
                _state.update { it.copy(isConnected = connected) }
                if (!connected) {
                    applySnapshot(
                        CameraSnapshot(
                            CameraIdentity.UNKNOWN,
                            CameraSettings(),
                            CameraCapabilities.UNKNOWN
                        )
                    )
                }
            }
        }
    }

    /** 开始扫描蓝牙遥控相机（需蓝牙权限已授予、相机端已开启「蓝牙遥控」） */
    fun startBleScan() = bleShutter.startScan()

    /** 断开蓝牙遥控 */
    fun disconnectBle() = bleShutter.disconnect()

    /** 蓝牙权限被拒绝时给用户明确提示（避免静默失败） */
    fun notifyBlePermissionDenied() {
        _state.update { it.copy(message = "蓝牙权限未授予，无法连接蓝牙遥控——请在系统设置中允许") }
    }

    /**
     * 快门：BLE 已连接时走蓝牙（低延迟可靠，拍摄成功率高）；
     * 否则降级 PTP InitiateCapture（ZV-E10 上固件存在已知怪癖，可能超时）。
     */
    /**
     * 快门按下（手势开始）：半按对焦（0x07）。
     * 与物理快门两段式一致：按下对焦，抬起（[shutterUp]）拍摄。
     * BLE 未连接时降级 PTP 拍摄（一次性触发）。
     */
    fun shutterDown() {
        if (bleShutter.state.value is BleShutterState.Connected) {
            haptics.thud()
            viewModelScope.launch {
                bleShutter.halfPress()
                _state.update { it.copy(message = "对焦中…（松开拍摄）") }
            }
        } else {
            viewModelScope.launch {
                _state.update { it.copy(taking = true) }
                try {
                    cameraRepository.takePicture()
                    _state.update { it.copy(message = "快门已触发（PTP），正在自动拉回…") }
                    haptics.thud()
                    refreshAfterCapture()
                } catch (e: Exception) {
                    _state.update { it.copy(message = "拍摄失败：${e.message}") }
                    haptics.double()
                } finally {
                    _state.update { it.copy(taking = false) }
                }
            }
        }
    }

    /**
     * 快门抬起（手势结束）：全按拍摄（0x09）→ 回位（0x08）→ 半按抬起（0x06，结束对焦）。
     * 码表（alpharemote ButtonCode）：0x07 半按按下 / 0x06 半按松开；0x09 全按按下 / 0x08 全按松开。
     * 缺 0x06 会导致对焦状态残留（状态胶囊一直亮对焦）。
     */
    fun shutterUp() {
        if (bleShutter.state.value is BleShutterState.Connected) {
            viewModelScope.launch {
                bleShutter.shutterPress()     // [01,09]：触发拍摄
                // 等相机确认快门已触发（ff02 状态 feedback shutter=true），最多 3 秒。
                // 状态是三态的，必须显式等 true：null（未知）不算触发。
                // 参考 alpharemote CAWaitFor(SHUTTER)：过早发回位/半按抬起会打断拍摄导致写卡卡死
                val triggered = withTimeoutOrNull(3000) {
                    bleShutter.cameraStatus.first { it.shutter == true }
                } != null
                if (triggered) {
                    delay(500)                    // 快门触发后留出曝光时间
                    bleShutter.shutterRelease()   // [01,08]：快门回位
                    delay(500)                    // 回位稳定后再抬半按，避免干扰写卡
                    bleShutter.halfRelease()      // [01,06]：半按抬起，结束对焦
                } else {
                    AppLog.w("control", "快门未触发（3s 超时或状态未知），仍执行回位")
                    bleShutter.shutterRelease()
                    bleShutter.halfRelease()
                }
                _state.update { it.copy(message = "已拍摄（BLE）") }
                haptics.thud()
                // 拍照后短暂静默 PTP，让相机写卡（相机「静态影像保存目的地」设为「仅拍摄装置」时写卡很快；
                // 若设为「手机+拍摄装置」则拍照后会推照片到手机导致写卡卡住——需用户改相机设置，非协议问题）
                cameraRepository.silencePtp(5000)
                delay(5000)                   // 等写卡完成
                refreshAfterCapture()         // 写卡完成后再尝试拉回
            }
        }
    }

    /** 录像开始/停止切换（按下 0x0F + 松开 0x0E；切换后短暂静默 PTP 让相机写视频） */
    fun recordToggle() {
        haptics.thud()
        bleShutter.record()
        cameraRepository.silencePtp(5000)
    }

    /**
     * 实时取景 Bitmap 流（cold flow：UI collect 时连接，离开页面自动断开）。
     * 电脑遥控/智能手机连接两种模式相机都开放 LiveView（60152）；解码失败帧静默跳过，
     * 流异常不崩溃（停止更新）。帧经 ImageDecoder 下采样到目标尺寸 + Hardware 位图，
     * 避免 18fps 大帧全尺寸解码导致卡顿/耗电。conflate：解码/渲染跟不上帧率时只保留
     * 最新帧（参考 sony_liveview_rust 的 latest-frame slot 设计——WiFi 卡顿后永远显示
     * 当前画面而非陈帧积压）。
     */
    val liveViewFrames: Flow<Bitmap> = flow {
        liveViewRepository.liveViewFrames()
            .throttleLatest(LIVEVIEW_MIN_FRAME_INTERVAL_MS)
            .collect { jpeg ->
                decodeScaled(jpeg, LIVEVIEW_TARGET_WIDTH, LIVEVIEW_TARGET_HEIGHT)?.let { emit(it) }
            }
    }.catch { e ->
        AppLog.w("liveview", "LiveView 流异常（停止取景）：${e.message}")
    }.flowOn(Dispatchers.Default)
        .conflate()

    /**
     * 下采样解码 LiveView JPEG 帧（失败返回 null 静默跳过）。
     *
     * **必须用软件位图**（P1-8）：原先的 ALLOCATOR_HARDWARE 把像素分配在独立
     * GraphicBuffer 池，既不受 Bitmap.recycle() 控制，也不随 Java GC 及时回收。
     * 18fps × 1280×854×4B ≈ **79MB/秒**的分配速率，取景开一会儿就会耗尽
     * GraphicBuffer → 画面卡死乃至进程被系统杀掉。
     * 软件位图挂在 Java 对象上、GC 可回收，配合上面的 [throttleLatest] 限流即可长期稳定。
     *
     * 未采用 inBitmap 复用：Compose 的 Image 可能仍在绘制上一帧，复用同一块内存
     * 会造成画面撕裂，权衡后选择「降分辨率 + 限流 + 可 GC 的软件位图」这一更稳的组合。
     */
    private fun decodeScaled(jpeg: ByteArray, targetW: Int, targetH: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(jpeg))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            if (srcW > 0 && srcH > 0) {
                val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
                if (scale < 1f) {
                    decoder.setTargetSize((srcW * scale).toInt(), (srcH * scale).toInt())
                }
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        AppLog.d("liveview", "帧解码失败（静默跳过）：${e.message}")
        null
    }

    /**
     * 进入控制面板工作态：真正建立相机连接，再建相册基线并探测能力。
     *
     * 参数走 PTP DeviceProp、快门走 BLE/PTP、LiveView 走 60152 裸流，
     * 均不依赖索尼 Web API（ZV-E10 无此服务，相关代码已于 2026-08-29 清除）。
     *
     * 修复（P1-1）：原实现**从不调用** `cameraRepository.connect()`，只是把
     * isConnected 直接置 true —— 一个纯粹的假状态。相机实际没连上时，
     * 参数读取静默返回，用户看到空白面板且没有任何错误提示，无法判断到底是
     * 没连上还是相机不支持。
     */
    fun connect() {
        if (_state.value.connecting) return
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, message = null) }
            val result = runCatching { cameraRepository.connect() }
                .onFailure { AppLog.w("control", "连接相机失败：${it.message}") }
                .getOrNull()
            val ok = result != null
            _state.update {
                it.copy(
                    connecting = false,
                    isConnected = ok,
                    message = if (ok) null
                    else "连接相机失败：请确认手机已连上相机 WiFi，且相机已进入对应模式"
                )
            }
            if (!ok) return@launch
            // 先同步通道**自我声明**的能力（不发 0x9209 往返）：遥控拍摄不依赖描述符，
            // 而下面的相册基线扫描可达 10~30s，等它跑完再同步会让快门一直压在禁用态
            applySnapshot(
                CameraSnapshot(
                    cameraRepository.identity.value,
                    CameraSettings(),
                    cameraRepository.capabilities.value
                )
            )
            // 基线相册内容（供拍摄后增量拉取）+ 探测参数能力并回显
            runCatching { cameraRepository.listMedia() }.getOrNull()?.let { items ->
                lastKnownThumbKeys.clear()
                lastKnownThumbKeys.addAll(items.map { it.thumbKey })
                // P1-14：基线就绪后才允许「拍摄后自动拉回」，否则首个 CaptureComplete
                // 会把整张相册当成新照片灌进下载队列
                baselineReady = true
            }
            refreshCameraState()
        }
    }

    /** 离开控制面板工作态 */
    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(isConnected = false) }
        }
    }

    /** 离开遥控页时终止 BLE 扫描/配对/GATT，不断开仍供相册使用的 Wi-Fi/PTP 会话。 */
    fun leaveScreen() {
        bleShutter.disconnect()
        _state.update { it.copy(isConnected = false, taking = false) }
    }

    override fun onCleared() {
        // UI 正常导航会调 leaveScreen；此处覆盖进程内非正常销毁路径。
        bleShutter.disconnect()
        super.onCleared()
    }

    // ── 参数下发（全部经能力校验）──────────────────────────────────────

    /** 设置 ISO（原始值：低 24 位 = ISO，0x00FFFFFF = Auto） */
    fun setIso(raw: Long) = writeParam(CameraCapability.ISO, raw, "ISO") { cameraRepository.setIso(it) }

    /** 设置光圈（原始值 = f 值 ×100） */
    fun setFNumber(raw: Long) =
        writeParam(CameraCapability.F_NUMBER, raw, "光圈") { cameraRepository.setFNumber(it) }

    /** 设置快门（原始值：高 16 分子 / 低 16 分母） */
    fun setShutterSpeed(raw: Long) =
        writeParam(CameraCapability.SHUTTER_SPEED, raw, "快门") { cameraRepository.setShutterSpeed(it) }

    /** 设置白平衡 */
    fun setWhiteBalance(code: Long) =
        writeParam(CameraCapability.WHITE_BALANCE, code, "白平衡") { cameraRepository.setWhiteBalance(it) }

    /** 设置曝光补偿（有符号 EV×1000） */
    fun setExposureBias(raw: Long) =
        writeParam(CameraCapability.EXPOSURE_BIAS, raw, "曝光补偿") { cameraRepository.setExposureBias(it) }

    /** 设置照相模式 */
    fun setShootMode(raw: Long) =
        writeParam(CameraCapability.EXPOSURE_PROGRAM_MODE, raw, "照相模式") {
            cameraRepository.setExposureProgramMode(it)
        }

    /**
     * 参数下发唯一入口。
     *
     * 能力未确认可写（未知 / 只读 / 不支持 / 快照陈旧 / 相机没给可选档位）时**不发命令**，
     * 只提示原因。不做「先乐观更新 UI 再等相机」——相机拒绝时界面会留下一个假值。
     */
    private fun writeParam(
        capability: CameraCapability,
        raw: Long,
        label: String,
        send: suspend (Long) -> Boolean
    ) {
        val param = _state.value.params[capability]
        if (param?.editable != true) {
            val state = param?.detail?.state ?: CapabilityState.UNKNOWN
            AppLog.w("control", "界面阻止下发 $label：能力状态=$state（${param?.detail?.note}）")
            _state.update { it.copy(message = "$label 当前不可调整") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching { send(raw) }.getOrDefault(false)
            _state.update {
                it.copy(
                    message = if (ok) "$label 已设为 ${CameraCapabilities.labelOf(capability, raw)}"
                    else "$label 设置失败（相机未响应或已断开）"
                )
            }
            refreshCameraState()
        }
    }

    // ── 能力探测与回显 ─────────────────────────────────────────────────

    /**
     * 探测能力并回显参数（一次 0x9209 往返同时得到能力快照与当前值）。
     *
     * 探测失败时回落到仓库当前快照（未连接即全 UNKNOWN），界面随之禁用全部控件——
     * 绝不保留上一轮的「可写」态。
     */
    private suspend fun refreshCameraState() {
        val snapshot = runCatching { cameraRepository.refreshCapabilities() }
            .onFailure { AppLog.w("control", "读取相机能力失败：${it.message}") }
            .getOrNull()
            ?: CameraSnapshot(
                cameraRepository.identity.value,
                CameraSettings(),
                cameraRepository.capabilities.value
            )
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: CameraSnapshot) {
        val capabilities = snapshot.capabilities
        _state.update {
            it.copy(
                identity = snapshot.identity,
                capabilitiesStale = capabilities.stale,
                captureAvailable = capabilities.canWrite(CameraCapability.CAPTURE),
                params = PARAMETERS.associateWith { capability ->
                    paramOf(capabilities, capability, snapshot.settings)
                }
            )
        }
    }

    private fun paramOf(
        capabilities: CameraCapabilities,
        capability: CameraCapability,
        settings: CameraSettings
    ): ParamUiState {
        val raw = when (capability) {
            CameraCapability.ISO -> settings.isoRaw
            CameraCapability.F_NUMBER -> settings.fNumberRaw
            CameraCapability.SHUTTER_SPEED -> settings.shutterRaw
            CameraCapability.EXPOSURE_PROGRAM_MODE -> settings.exposureProgramMode
            CameraCapability.WHITE_BALANCE -> settings.whiteBalance
            CameraCapability.EXPOSURE_BIAS -> settings.exposureBias
            CameraCapability.CAPTURE -> null
        }
        val options = capabilities.optionsFor(capability)
        return ParamUiState(
            currentLabel = raw?.let { CameraCapabilities.labelOf(capability, it) },
            options = options,
            detail = capabilities.detail(capability),
            // 可写但相机没给档位（既无枚举表也无取值范围）时同样禁用：
            // 没有相机确认过的取值可发，硬凑一份档位表就是「未经验证的可写参数」
            editable = capabilities.canWrite(capability) && options.isNotEmpty()
        )
    }

    /** 拍摄完成后：重扫相册，把新照片增量加入下载队列；未发现新照片时给出明确的限制说明。 */
    private suspend fun refreshAfterCapture() {
        // 基线未就绪：此刻的差集等于「整张相册」，不能据此入队（详见 baselineReady 注释）
        if (!baselineReady) {
            AppLog.d("control", "相册基线尚未就绪，跳过本次自动拉回")
            return
        }
        val items = runCatching { cameraRepository.listMedia() }.getOrNull() ?: return
        val newItems = items.filter { it.thumbKey !in lastKnownThumbKeys }
        lastKnownThumbKeys.clear()
        lastKnownThumbKeys.addAll(items.map { it.thumbKey })
        if (newItems.isNotEmpty()) {
            downloadManager.enqueueAll(newItems)
            _state.update { it.copy(message = "已自动拉回 ${newItems.size} 张照片到下载队列") }
        } else {
            // 智能手机连接 + BLE 快门的已知限制：拍下的照片不会进 0xF10001 待传集
            // （电脑遥控 + PTP InitiateCapture 路径上本方法可生效）
            _state.update {
                it.copy(
                    message = "照片已拍，但未进入待传内容集——请到相机端「发送到智能手机」选片后到相册下载"
                )
            }
        }
    }

    private companion object {
        /** 遥控面板展示的参数（固定顺序）；CAPTURE 不是参数，单独由 captureAvailable 表达 */
        val PARAMETERS = listOf(
            CameraCapability.EXPOSURE_PROGRAM_MODE,
            CameraCapability.ISO,
            CameraCapability.F_NUMBER,
            CameraCapability.SHUTTER_SPEED,
            CameraCapability.WHITE_BALANCE,
            CameraCapability.EXPOSURE_BIAS
        )
    }
}
