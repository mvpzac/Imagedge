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
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.LiveViewRepository
import com.imagedge.camera.data.transfer.DownloadManager
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
 *     time   : 2026/08/27
 *     desc   : 相机控制 ViewModel（连接 / 拍照 / 参数调节）
 *     version: 1.0
 * </pre>
 */

/** 控制状态 */
data class ControlState(
    val isConnected: Boolean = false,
    val connecting: Boolean = false,
    val taking: Boolean = false,
    val message: String? = null,
    // ── ISO/光圈/快门：能力驱动（相机 0x9209 上报 supported 枚举表 → UI 下拉可选项）──
    // 当前原始值 + 可选项（标签 → 原始值）；supported 为空时回退到硬编码预设（见 CameraPresets）
    val isoRaw: Long? = null,
    val isoOptions: List<Pair<String, Long>> = emptyList(),
    val fNumberRaw: Long? = null,
    val fNumberOptions: List<Pair<String, Long>> = emptyList(),
    val shutterRaw: Long? = null,
    val shutterOptions: List<Pair<String, Long>> = emptyList(),
    // ── 扩展参数回显（0x9209 相机上报为准；null=相机未返回）──
    val shootModeLabel: String? = null,
    /** 照相模式原始值（选中态判断用） */
    val shootModeCode: Long? = null,
    /** 照相模式可选项（相机 0x9209 上报的 supported 枚举表，空/只读 = 不可远程调整） */
    val shootModeOptions: List<Pair<String, Long>> = emptyList(),
    val whiteBalance: String? = null,
    val exposureBias: String? = null
)

/** 参数预设档位（照相模式不在此列：其选项以相机 0x9209 上报的枚举表为准，见 ControlState）。
 *  ISO/光圈/快门 fallback 预设：相机未上报 supported 时使用，值为可直接下发的原始值。
 *  注意 Auto 的 raw 是 0x00FFFFFF（旧的 isoToRaw("Auto")=0 会导致无法设回 Auto，已修正）。 */
object CameraPresets {
    val ISO_PRESETS: List<Pair<String, Long>> = listOf(
        "Auto" to 0x00FFFFFFL,
        "100" to 100L, "200" to 200L, "400" to 400L, "800" to 800L,
        "1600" to 1600L, "3200" to 3200L, "6400" to 6400L, "12800" to 12800L
    )
    val FNUMBER_PRESETS: List<Pair<String, Long>> = listOf(
        "1.8" to 180L, "2.0" to 200L, "2.8" to 280L, "4.0" to 400L,
        "5.6" to 560L, "8.0" to 800L, "11" to 1100L, "16" to 1600L, "22" to 2200L
    )
    val SHUTTER_PRESETS: List<Pair<String, Long>> = listOf(
        "1/4000" to (1L shl 16 or 4000L),
        "1/2000" to (1L shl 16 or 2000L),
        "1/1000" to (1L shl 16 or 1000L),
        "1/500" to (1L shl 16 or 500L),
        "1/250" to (1L shl 16 or 250L),
        "1/125" to (1L shl 16 or 125L),
        "1/60" to (1L shl 16 or 60L),
        "1/30" to (1L shl 16 or 30L),
        "1/15" to (1L shl 16 or 15L),
        "1/8" to (1L shl 16 or 8L),
        "1/4" to (1L shl 16 or 4L),
        "1/2" to (1L shl 16 or 2L),
        "1\"" to (10L shl 16 or 10L)
    )

    /** 白平衡（0x5005 枚举值表：官方 EnumWhiteBalanceMode） */
    val WB_OPTIONS = listOf(
        "自动" to 3L,
        "日光" to 5L,
        "阴影" to 14L,
        "阴天" to 13L,
        "白炽灯" to 7L,
        "荧光灯" to 6L,
        "闪光灯" to 8L
    )

    /** 曝光补偿（0x5010，INT16 EV×1000；±3.0EV 1/3 步） */
    val EB_OPTIONS: List<Pair<String, Long>> = listOf(
        "+3.0" to 3000L, "+2.7" to 2700L, "+2.3" to 2300L, "+2.0" to 2000L,
        "+1.7" to 1700L, "+1.3" to 1300L, "+1.0" to 1000L, "+0.7" to 700L,
        "+0.3" to 300L, "0.0" to 0L,
        "-0.3" to (-300L).and(0xFFFFL), "-0.7" to (-700L).and(0xFFFFL),
        "-1.0" to (-1000L).and(0xFFFFL), "-1.3" to (-1300L).and(0xFFFFL),
        "-1.7" to (-1700L).and(0xFFFFL), "-2.0" to (-2000L).and(0xFFFFL),
        "-2.3" to (-2300L).and(0xFFFFL), "-2.7" to (-2700L).and(0xFFFFL),
        "-3.0" to (-3000L).and(0xFFFFL)
    )
}

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
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()

    /** BLE 快门连接状态（Disconnected/Scanning/Connecting/Connected） */
    val bleState = bleShutter.state

    /** 相机实时状态（ff02 通知：对焦/快门/录像） */
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
        // 300ms 合并去抖 → 重读 0x9209 回显（手机显示始终以相机实际状态为准）
        viewModelScope.launch {
            cameraRepository.propEvents
                .debounce(300)
                .collect { readSettings() }
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
                    refreshAfterCapture()
                } catch (e: Exception) {
                    _state.update { it.copy(message = "拍摄失败：${e.message}") }
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
                // 等相机确认快门已触发（ff02 状态 feedback shutter=true），最多 3 秒
                // 参考 alpharemote CAWaitFor(SHUTTER)：过早发回位/半按抬起会打断拍摄导致写卡卡死
                val triggered = withTimeoutOrNull(3000) {
                    bleShutter.cameraStatus.first { it.shutter }
                } != null
                if (triggered) {
                    delay(500)                    // 快门触发后留出曝光时间
                    bleShutter.shutterRelease()   // [01,08]：快门回位
                    delay(500)                    // 回位稳定后再抬半按，避免干扰写卡
                    bleShutter.halfRelease()      // [01,06]：半按抬起，结束对焦
                } else {
                    AppLog.w("control", "快门未触发（3s 超时），仍执行回位")
                    bleShutter.shutterRelease()
                    bleShutter.halfRelease()
                }
                _state.update { it.copy(message = "已拍摄（BLE）") }
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
     * 进入控制面板工作态：真正建立相机连接，再建相册基线并读取参数。
     *
     * 参数走 PTP DeviceProp、快门走 BLE/PTP、LiveView 走 60152 裸流，
     * 均不依赖索尼 Web API（ZV-E10 无此服务，相关代码已于 2026-08-29 清除）。
     *
     * 修复（P1-1）：原实现**从不调用** `cameraRepository.connect()`，只是把
     * isConnected 直接置 true —— 一个纯粹的假状态。相机实际没连上时，
     * readSettings() 因 `getOrNull() ?: return` 静默返回，用户看到空白面板且
     * 没有任何错误提示，无法判断到底是没连上还是相机不支持。
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
            // 基线相册内容（供拍摄后增量拉取）+ 读取相机当前参数
            runCatching { cameraRepository.listMedia() }.getOrNull()?.let { items ->
                lastKnownThumbKeys.clear()
                lastKnownThumbKeys.addAll(items.map { it.thumbKey })
                // P1-14：基线就绪后才允许「拍摄后自动拉回」，否则首个 CaptureComplete
                // 会把整张相册当成新照片灌进下载队列
                baselineReady = true
            }
            readSettings()
        }
    }

    /** 离开控制面板工作态 */
    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(isConnected = false) }
        }
    }

    /** 设置 ISO（PTP DeviceProp 0xD21E，接收相机原始值：低 24 位 = ISO，0x00FFFFFF = Auto） */
    fun setIso(raw: Long) {
        _state.update { it.copy(isoRaw = raw) }
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setIso(raw) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) "ISO 已设为 ${CameraSettings.formatIso(raw)}"
                    else "ISO 设置失败（相机未响应或该机型不支持）")
            }
            readSettings()
        }
    }

    /** 设置光圈（PTP DeviceProp 0x5007，接收原始值 = f 值 ×100） */
    fun setFNumber(raw: Long) {
        _state.update { it.copy(fNumberRaw = raw) }
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setFNumber(raw) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) "光圈已设为 f/${CameraSettings.formatFNumber(raw)}"
                    else "光圈设置失败（相机未响应或该机型不支持）")
            }
            readSettings()
        }
    }

    /** 设置快门（PTP DeviceProp 0xD20D，接收原始值：高 16 分子 / 低 16 分母） */
    fun setShutterSpeed(raw: Long) {
        _state.update { it.copy(shutterRaw = raw) }
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setShutterSpeed(raw) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) "快门已设为 ${CameraSettings.formatShutter(raw)}"
                    else "快门设置失败（相机未响应或该机型不支持）")
            }
            readSettings()
        }
    }

    /** 设置白平衡（PTP DeviceProp 0x5005） */
    fun setWhiteBalance(code: Long) {
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setWhiteBalance(code) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) null else "白平衡设置失败（相机未响应或该机型不支持）")
            }
            readSettings()
        }
    }

    /** 设置曝光补偿（PTP DeviceProp 0x5010，INT16 EV×1000） */
    fun setExposureBias(raw: Long) {
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setExposureBias(raw) }.getOrDefault(false)
            _state.update {
                it.copy(message = if (ok) null else "曝光补偿设置失败（相机未响应或该机型不支持）")
            }
            readSettings()
        }
    }

    /** 设置照相模式（PTP DeviceProp 0x500E，官方 APP 同款 0x9205 通道） */
    fun setShootMode(raw: Long) {
        viewModelScope.launch {
            val ok = runCatching { cameraRepository.setExposureProgramMode(raw) }.getOrDefault(false)
            _state.update {
                it.copy(
                    message = if (ok) null
                    else "照相模式切换失败（相机未响应或当前状态不允许）"
                )
            }
            readSettings()
        }
    }

    /** 读取相机当前参数并回显到 UI */
    private suspend fun readSettings() {
        val settings = runCatching { cameraRepository.readCameraSettings() }.getOrNull() ?: return
        _state.update { s ->
            s.copy(
                isoRaw = settings.isoRaw,
                isoOptions = if (settings.isoSupported.isNotEmpty())
                    CameraSettings.isoOptions(settings.isoSupported)
                else CameraPresets.ISO_PRESETS,
                fNumberRaw = settings.fNumberRaw,
                fNumberOptions = if (settings.fNumberSupported.isNotEmpty())
                    CameraSettings.fNumberOptions(settings.fNumberSupported)
                else CameraPresets.FNUMBER_PRESETS,
                shutterRaw = settings.shutterRaw,
                shutterOptions = if (settings.shutterSupported.isNotEmpty())
                    CameraSettings.shutterOptions(settings.shutterSupported)
                else CameraPresets.SHUTTER_PRESETS,
                shootModeLabel = settings.exposureProgramMode
                    ?.let { CameraSettings.formatProgramMode(it) } ?: s.shootModeLabel,
                shootModeCode = settings.exposureProgramMode ?: s.shootModeCode,
                // 照相模式选项：相机上报枚举表 ∩ 官方 APP 遥控白名单（固定顺序），
                // 滤掉协议表里的场景模式/拨盘位/程序偏移态；只读属性不渲染选择器
                shootModeOptions = if (settings.exposureProgramModeSettable) {
                    CameraSettings.selectableProgramModes(settings.exposureProgramModeSupported)
                        .map { CameraSettings.formatProgramMode(it) to it }
                } else {
                    emptyList()
                },
                whiteBalance = settings.whiteBalance
                    ?.let { CameraSettings.formatWhiteBalance(it) } ?: s.whiteBalance,
                exposureBias = settings.exposureBias
                    ?.let { CameraSettings.formatExposureBias(it) } ?: s.exposureBias
            )
        }
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
}
