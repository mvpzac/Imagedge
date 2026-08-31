package com.imagedge.camera.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.imagedge.camera.core.common.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 索尼相机 BLE 遥控快门（「蓝牙遥控」功能，双源协议印证：alpharemote + furble）
 *     version: 1.0
 * </pre>
 */

/** BLE 快门连接状态 */
sealed class BleShutterState {
    data object Disconnected : BleShutterState()
    data class Scanning(val found: List<BluetoothDevice>) : BleShutterState()
    data class Connecting(val name: String) : BleShutterState()
    data class Connected(val name: String) : BleShutterState()
}

/** 相机实时状态（经 BLE ff02 状态特征通知推送，参考 alpharemote） */
data class BleCameraStatus(
    val focus: Boolean = false,       // 半按对焦中
    val shutter: Boolean = false,     // 快门按下
    val recording: Boolean = false    // 录像中
)

/**
 * 索尼 BLE 遥控快门客户端
 *
 * 协议（furble 与 alpharemote 两个独立开源实现互相印证）：
 * - 扫描过滤：厂商广播 manufacturer data，company_id = 0x012D（Sony）
 * - 服务：8000ff00-ff00-ffff-ffff-ffffffffffff
 * - 命令特征值：0000ff01-0000-1000-8000-00805f9b34fb
 * - 命令格式：2 字节 [0x01, code]，code（低位 0x01=按下、未置位=松开）：
 *   0x09 快门全按下 / 0x08 快门回位 / 0x07 半按（对焦）/ 0x06 半按抬起 / 0x0F 录像切换 / 0x15 AF-ON
 * - 前置条件：相机端开启「蓝牙遥控」功能（首次连接可能需在相机端确认配对）
 */
@Singleton
class SonyBleShutter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ble"

        /** Sony Corporation 的 Bluetooth SIG 公司 ID */
        private const val SONY_MANUFACTURER_ID = 0x012D

        private val REMOTE_SERVICE_UUID: UUID =
            UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")
        private val COMMAND_CHAR_UUID: UUID =
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val STATUS_CHAR_UUID: UUID =
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * 命令码（写入 [0x01, code]）。码表语义（alpharemote CameraActionStep）：
         * code 低位 0x01 = 按下、不加 = 松开：
         *   快门全按：0x09（按）/0x08（松）；半按：0x07（按）/0x06（松）；
         *   录像：0x0F（按下切换）；AF-ON：0x15；C1：0x21
         */
        const val CMD_SHUTTER_FULL_PRESS = 0x09
        const val CMD_SHUTTER_RELEASE = 0x08
        const val CMD_SHUTTER_HALF_PRESS = 0x07
        const val CMD_SHUTTER_HALF_RELEASE = 0x06
        const val CMD_RECORD = 0x0F             // 录像按下
        const val CMD_RECORD_RELEASE = 0x0E     // 录像松开（按下+松开才是完整切换）
        const val CMD_AF_ON = 0x15
    }

    private val _state = MutableStateFlow<BleShutterState>(BleShutterState.Disconnected)
    val state: StateFlow<BleShutterState> = _state.asStateFlow()

    /** 相机实时状态（ff02 通知推送：对焦/快门/录像） */
    private val _cameraStatus = MutableStateFlow(BleCameraStatus())
    val cameraStatus: StateFlow<BleCameraStatus> = _cameraStatus.asStateFlow()

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    /**
     * BLE 写命令队列（GATT 写需逐个等待回调）。
     *
     * 该对象同时作为**写状态锁**：[writeQueue] 与 [writing] 必须在同一把锁下访问。
     * 原先只在操作队列时加锁，而 `if (writing) return` 与 `writing = true` 之间无任何同步，
     * 多线程 enqueue 可能同时通过检查 → 并发 GATT 写 → 相机直接断开。
     */
    private val writeQueue = ArrayDeque<Int>()

    /**
     * 是否有 GATT 写正在进行。@Volatile 保证 Binder 回调线程与业务线程的可见性。
     * 所有读写都在 `synchronized(writeQueue)` 内进行。
     */
    @Volatile
    private var writing = false

    /** 单次 GATT 写超时（超时未收到 onCharacteristicWrite 即复位写状态） */
    private val WRITE_TIMEOUT_MS = 2_000L

    /** 扫描超时用（stopScan 会 removeCallbacksAndMessages(null)，故写超时另用独立 handler） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 写超时专用 handler：避免被 stopScan 的 removeCallbacksAndMessages(null) 误清 */
    private val writeHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 写超时兜底（P1-3）。
     *
     * Android BLE 栈在高负载或链路抖动时**确实会丢** onCharacteristicWrite 回调。
     * 没有兜底时 [writing] 会永久为 true，之后所有快门命令被静默丢弃
     * ——用户表现为「按快门没反应」，只能杀进程重开，且日志里无任何线索。
     */
    private val writeTimeoutRunnable = Runnable {
        val stale: Boolean
        synchronized(writeQueue) {
            stale = writing
            if (stale) {
                AppLog.w(TAG, "BLE 命令写超时（${WRITE_TIMEOUT_MS}ms）未收到回调，强制复位写状态")
                writing = false
            }
        }
        // 继续消费队列；若连接已断，writeNext 会因 commandChar/gatt 为空自行退出
        if (stale) writeNext()
    }

    private var pendingBondDevice: BluetoothDevice? = null

    /** 监听系统配对状态：bond 完成后自动继续 GATT 连接 */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    AppLog.i(TAG, "配对完成：${device.address}")
                    unregisterBondReceiver()
                    pendingBondDevice?.let { if (it.address == device.address) connectGatt(device) }
                    pendingBondDevice = null
                }
                BluetoothDevice.BOND_NONE -> {
                    AppLog.w(TAG, "配对失败或被取消：${device.address}")
                    unregisterBondReceiver()
                    pendingBondDevice = null
                    _state.value = BleShutterState.Disconnected
                }
            }
        }
    }

    private val bleManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    // ── 扫描 ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 应用层过滤：解析厂商数据前 2 字节 == 0x012D（Sony）。
            // 不用系统 ScanFilter——部分机型广播格式差异会导致系统层匹配不到，
            // 全量扫描 + 自解析便于诊断（日志打印全部广播名）。
            val record = result.scanRecord ?: return
            val mfg = record.manufacturerSpecificData
            var sonyHit = false
            for (i in 0 until mfg.size()) {
                if (mfg.keyAt(i) == SONY_MANUFACTURER_ID) {
                    sonyHit = true
                    break
                }
            }
            // device.name 需 BLUETOOTH_CONNECT：权限中途被撤销时访问会抛
            // SecurityException（回调在主线程，未捕获即崩溃），降级用广播名
            val name = runCatching { result.device?.name }.getOrNull()
                ?: record.deviceName ?: ""
            if (!sonyHit) return  // 非 Sony 广播静默跳过（降噪）
            val device = result.device ?: return
            val address = runCatching { device.address }.getOrNull() ?: "?"
            AppLog.i(TAG, "发现索尼相机蓝牙遥控：$name（$address）")
            // 单相机场景：发现即自动连接（多相机时的手动选择后续再扩展）
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.e(TAG, "BLE 扫描失败：$errorCode")
            _state.value = BleShutterState.Disconnected
        }
    }

    /** 开始扫描 Sony 相机广播（需 BLUETOOTH_CONNECT / 定位权限已授予） */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_state.value !is BleShutterState.Disconnected) return
        val bluetoothLeScanner = bleManager?.adapter?.bluetoothLeScanner ?: run {
            AppLog.w(TAG, "蓝牙不可用（未开启或设备不支持）")
            return
        }
        scanner = bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _state.value = BleShutterState.Scanning(emptyList())
        bluetoothLeScanner.startScan(null, settings, scanCallback)
        AppLog.i(TAG, "BLE 全量扫描开始（应用层过滤 Sony 0x012D）")
        // 10 秒无结果：提示用户检查相机端「蓝牙遥控」是否开启
        handler.postDelayed({
            if (_state.value is BleShutterState.Scanning) {
                stopScan()
                _state.value = BleShutterState.Disconnected
                AppLog.w(TAG, "扫描超时：未发现相机广播——请确认相机已开启「蓝牙遥控」且蓝牙已打开")
            }
        }, 10_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
    }

    // ── 连接 ─────────────────────────────────────────────────────────

    /**
     * 连接（自动处理配对）：未 bonded 的设备先走系统配对（相机端/系统弹确认框），
     * 配对完成后自动继续 GATT 连接。蓝牙遥控命令特征值需要加密链路，
     * 未配对直接写命令会被相机断开（status=19，实测）。
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        val name = device.name ?: device.address
        // 幂等：已在连接/已连接时不重复发起。
        // onScanResult 可能在 stopScan() 生效前批量回调多次，不设这道闸会重复
        // registerReceiver + createBond，把系统配对流程搞乱。
        if (_state.value is BleShutterState.Connecting || _state.value is BleShutterState.Connected) {
            AppLog.d(TAG, "已在连接中/已连接，忽略重复连接请求：$name")
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            AppLog.i(TAG, "设备未配对，开始系统配对：$name")
            _state.value = BleShutterState.Connecting(name + "（配对中…）")
            pendingBondDevice = device
            // 先反注册再注册：bondReceiver 是同一实例，重复 register 会让第一次
            // 事件到达时的 unregister 失效，留下常驻 receiver（P1-4）。
            unregisterBondReceiver()
            context.registerReceiver(
                bondReceiver,
                android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            )
            device.createBond()
            return
        }
        connectGatt(device)
    }

    /** 注销配对广播（未注册时 IllegalArgumentException 已被吞掉） */
    private fun unregisterBondReceiver() {
        runCatching { context.unregisterReceiver(bondReceiver) }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        val name = device.name ?: device.address
        // 关键（P1-2）：Android 对单进程 BLE 客户端连接数有硬上限（常见 7~15）。
        // 覆盖 gatt 字段前若旧对象没 close，那个连接槽会**永久泄漏**——
        // ZV-E10 的 BLE 连接本身不稳定，用户反复重连十几次后蓝牙对该 App 全面失效，
        // 只能重启手机恢复。
        gatt?.let { old ->
            AppLog.w(TAG, "发现未关闭的旧 GATT 连接，先回收：${old.device?.address}")
            runCatching { old.disconnect() }
            runCatching { old.close() }
            gatt = null
        }
        synchronized(writeQueue) { writeQueue.clear() }
        writing = false
        _state.value = BleShutterState.Connecting(name)
        AppLog.i(TAG, "BLE 连接：$name（${device.address}）")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        // 先停扫描：否则断开后 10s 内发现相机广播仍会自动连接，与用户意图相反
        stopScan()
        // 注销配对广播：否则用户主动断开后，系统配对完成仍会触发自动重连（P1-4）
        unregisterBondReceiver()
        pendingBondDevice = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandChar = null
        statusChar = null
        writeHandler.removeCallbacks(writeTimeoutRunnable)
        synchronized(writeQueue) { writeQueue.clear() }
        writing = false
        _cameraStatus.value = BleCameraStatus()
        _state.value = BleShutterState.Disconnected
        AppLog.i(TAG, "BLE 已断开")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AppLog.i(TAG, "GATT 已连接，开始服务发现")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AppLog.w(TAG, "GATT 断开（status=$status）")
                    g.close()
                    // 仅当断开的就是当前持有的实例才清空引用：
                    // 否则会把一次「旧连接的迟到回调」误当成当前连接断开，
                    // 让刚建好的连接被判定为已断开（P1-2 配套修复）
                    if (g === gatt) gatt = null
                    commandChar = null
                    statusChar = null
                    synchronized(writeQueue) { writeQueue.clear() }
                    writing = false
                    _cameraStatus.value = BleCameraStatus()
                    _state.value = BleShutterState.Disconnected
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.e(TAG, "服务发现失败：$status")
                g.disconnect()
                return
            }
            val service = g.getService(REMOTE_SERVICE_UUID)
            val char = service?.getCharacteristic(COMMAND_CHAR_UUID)
            if (char == null) {
                AppLog.e(TAG, "未找到遥控命令特征值（相机可能未开启「蓝牙遥控」）")
                g.disconnect()
                return
            }
            commandChar = char
            // 订阅 ff02 状态特征通知（对焦/快门/录像状态反馈，参考 alpharemote）
            val status = service.getCharacteristic(STATUS_CHAR_UUID)
            statusChar = status
            if (status != null) {
                g.setCharacteristicNotification(status, true)
                val descriptor = status.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                }
            }
            val name = g.device?.name ?: "Sony"
            AppLog.i(TAG, "遥控命令通道就绪（$name），状态通知已订阅=${status != null}")
            _state.value = BleShutterState.Connected(name)
        }

        /**
         * Android 12 及以下（API ≤ 32）的状态通知入口。
         *
         * 三参版 `onCharacteristicChanged(gatt, characteristic, value)` 是 **Android 13
         * (API 33)** 才加入的重载。项目 minSdk = 29，若只覆写三参版，Android 10/11/12
         * 上状态通知**永远不会回调**——表现为相机状态胶囊不亮，且 CameraControlViewModel
         * 里 `withTimeoutOrNull(3000) { cameraStatus.first { it.shutter } }` 每次都跑满
         * 3 秒超时（用户感知：「按快门要等 3 秒才拍」）。
         *
         * 统一转发到三参版，保证全版本行为一致。
         */
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            onCharacteristicChanged(g, characteristic, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != STATUS_CHAR_UUID || value.size < 3) return
            // 状态字节：value[1]=类型（0x3f 对焦 / 0xa0 快门 / 0xd5 录像），value[2] 的 0x20 位=值
            when (value[1]) {
                0x3f.toByte() -> _cameraStatus.update { it.copy(focus = (value[2].toInt() and 0x20) != 0) }
                0xa0.toByte() -> _cameraStatus.update { it.copy(shutter = (value[2].toInt() and 0x20) != 0) }
                0xd5.toByte() -> _cameraStatus.update { it.copy(recording = (value[2].toInt() and 0x20) != 0) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.w(TAG, "命令写入失败：$status")
            }
            synchronized(writeQueue) {
                writing = false
                writeHandler.removeCallbacks(writeTimeoutRunnable)
            }
            // 在锁外消费下一条：长队列时避免递归嵌套加锁
            writeNext()
        }
    }

    // ── 命令 ─────────────────────────────────────────────────────────

    /** 快门全按（拍照；需随后调用 [shutterRelease] 回位） */
    fun shutterPress() = enqueue(CMD_SHUTTER_FULL_PRESS)

    /** 快门回位 */
    fun shutterRelease() = enqueue(CMD_SHUTTER_RELEASE)

    /** 半按（对焦，按下） */
    fun halfPress() = enqueue(CMD_SHUTTER_HALF_PRESS)

    /** 半按抬起 */
    fun halfRelease() = enqueue(CMD_SHUTTER_HALF_RELEASE)

    /** 录像开始/停止切换（按下 0x0F + 松开 0x0E 才是完整切换，参考 alpharemote RECORD preset） */
    fun record() {
        enqueue(CMD_RECORD)          // 0x0F 录像按下
        enqueue(CMD_RECORD_RELEASE)  // 0x0E 录像松开
    }

    /** AF-ON */
    fun afOn() = enqueue(CMD_AF_ON)

    private fun enqueue(code: Int) {
        if (_state.value !is BleShutterState.Connected) {
            AppLog.w(TAG, "BLE 未连接，忽略命令 0x" + code.toString(16))
            return
        }
        synchronized(writeQueue) { writeQueue.addLast(code) }
        writeNext()
    }

    /**
     * 取队列中的下一条命令并写入。
     *
     * 整个方法在 `synchronized(writeQueue)` 内执行：writeCharacteristic 是异步调用
     * （立即返回，结果走 onCharacteristicWrite），不会因为持锁而阻塞 Binder 回调。
     * 这样彻底消除「检查 writing」与「置 writing = true」之间的竞态。
     */
    @SuppressLint("MissingPermission")
    private fun writeNext() {
        synchronized(writeQueue) {
            if (writing) return
            val code = writeQueue.pollFirst() ?: return
            val char = commandChar ?: return
            val g = gatt ?: return
            writing = true
            writeHandler.removeCallbacks(writeTimeoutRunnable)
            AppLog.i(TAG, "写命令：[0x01, 0x" + code.toString(16) + "]")
            val payload = byteArrayOf(0x01, code.toByte())
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
            if (!ok) {
                AppLog.w(TAG, "命令入队失败：0x" + code.toString(16) + "——连接可能已断开，清空队列")
                writing = false
                // 写失败通常意味着 GATT 连接异常：清空队列避免剩余命令永久卡住，并复位为断开态供用户重连
                writeQueue.clear()
                runCatching { g.disconnect() }
                runCatching { g.close() }
                gatt = null
                commandChar = null
                _state.value = BleShutterState.Disconnected
            } else {
                // 正常入队：安排超时兜底，防止 onCharacteristicWrite 回调丢失导致写状态卡死
                writeHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
            }
        }
    }
}
