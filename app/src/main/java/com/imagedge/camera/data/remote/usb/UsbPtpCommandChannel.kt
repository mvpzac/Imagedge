package com.imagedge.camera.data.remote.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.ptp.PtpCommandChannel
import com.imagedge.camera.ptp.PtpContainerCodec
import com.imagedge.camera.ptp.UsbContainerAssembler
import com.imagedge.camera.ptp.PtpIoException
import com.imagedge.camera.ptp.PtpMalformedPacketException
import com.imagedge.camera.ptp.PtpIpPacket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 索尼相机 USB 上的 PTP 命令通道（批量端点 + MTP 容器）
 *     version: 1.0
 * </pre>
 */

/**
 * USB 承载的命令通道。
 *
 * 与 TCP 的关键差别：**批量传输不是按消息分帧的**。一次 `bulkTransfer` 可能只带回
 * 半个容器，也可能一次带回一个半容器。所以读侧必须自己攒——先读够容器头里的长度，
 * 再补齐剩下的字节——把「字节流 → 容器」这一步补上，TCP 那边不需要是因为 socket 自带这个保证。
 *
 * 事件不走这里：USB 上的事件来自中断端点，需要 `UsbRequest` + `requestWait()` 的
 * 异步等待模型，与这里的同步读不同源。因此 USB 通路不提供事件，
 * 「拍完自动拉回」这类依赖事件的功能仍需走 Wi-Fi。
 */
class UsbPtpCommandChannel private constructor(
    private val connection: UsbDeviceConnection,
    private val interfaceHandle: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) : PtpCommandChannel {

    /** 字节流切分交给可单测的累积器；这里只负责搬字节 */
    private val assembler = UsbContainerAssembler(MAX_CONTAINER_BYTES)

    private val openFlag = AtomicBoolean(true)

    override val isOpen: Boolean get() = openFlag.get()

    override fun write(packet: PtpIpPacket) {
        val bytes = PtpContainerCodec.encode(packet)
        val written = connection.bulkTransfer(bulkOut, bytes, bytes.size, WRITE_TIMEOUT_MS)
        if (written < 0) {
            throw PtpIoException("USB 写出失败（${packet.javaClass.simpleName}）")
        }
        if (written != bytes.size) {
            // 少写了字节却当作成功：相机会把半条命令当成完整的，行为不可预期
            throw PtpIoException("USB 写出不完整：$written/${bytes.size}")
        }
    }

    override fun read(): PtpIpPacket {
        while (true) {
            assembler.next()?.let { return PtpContainerCodec.decode(it) }
            // 切不出容器只可能是「还没攒够」：合法且 ≤ 上限的长度一旦被满足就已经切出来了，
            // 而非法长度在 next() 里就被拒并清空了。所以这里只会往下再读，不会越攒越多
            appendIncoming("容器片段")
        }
    }

    private fun appendIncoming(what: String) {
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val n = connection.bulkTransfer(bulkIn, chunk, chunk.size, READ_TIMEOUT_MS)
        if (n <= 0) {
            throw PtpIoException("USB 读取中断（$what，累积 ${assembler.bufferedBytes} 字节）")
        }
        assembler.feed(chunk, n)
    }

    override fun close() {
        if (!openFlag.compareAndSet(true, false)) return
        assembler.reset()
        runCatching { connection.releaseInterface(interfaceHandle) }
        runCatching { connection.close() }
        AppLog.i(TAG, "USB PTP 通道已关闭")
    }

    companion object {
        private const val TAG = "usbptp"
        private const val WRITE_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val MAX_CONTAINER_BYTES = 64 * 1024 * 1024

        /** 索尼的 USB 厂商 ID */
        const val SONY_VENDOR_ID = 0x054C

        /** USB 权限广播的自定义动作 */
        const val ACTION_USB_PERMISSION = "com.imagedge.camera.USB_PERMISSION"

        /**
         * 打开一条 USB PTP 命令通道。
         *
         * @param connection 已 claim 的连接
         * @param usbDevice  已获授权的设备
         * @throws PtpIoException 设备不是 PTP 形态，或端点不全
         */
        fun open(connection: UsbDeviceConnection, usbDevice: UsbDevice): UsbPtpCommandChannel {
            val intf = usbDevice.getInterface(0)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_STILL_IMAGE) {
                throw PtpIoException(
                    "USB 接口类别为 ${intf.interfaceClass}，不是 PTP/静态影像（应为 6）"
                )
            }
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint else bulkOut = endpoint
            }
            if (bulkIn == null || bulkOut == null) {
                throw PtpIoException("USB 接口缺少批量端点（入=${bulkIn != null} 出=${bulkOut != null}）")
            }
            if (!connection.claimInterface(intf, true)) {
                throw PtpIoException("claimInterface 失败，设备可能被别的应用占用")
            }
            AppLog.i(TAG, "USB PTP 通道已建立（厂商 0x${Integer.toHexString(usbDevice.vendorId)}）")
            return UsbPtpCommandChannel(connection, intf, bulkIn, bulkOut)
        }
    }
}

/** 已授权的索尼 PTP 相机。 */
data class UsbPtpCamera(val device: UsbDevice, val deviceName: String)

/**
 * 索尼 PTP 相机的发现与授权。
 *
 * 授权必须走系统对话框（`UsbManager.requestPermission`），没有静默授权这条路——
 * 所以这里返回的是一个「可能已经授权也可能刚发起授权」的结果，
 * 真正的可用性由 [UsbPtpConnector] 在拿到结果后判断。
 */
class UsbPtpDiscovery(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** 列出已授权的索尼 PTP 相机。 */
    fun listAuthorizedCameras(): List<UsbPtpCamera> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { it.vendorId == UsbPtpCommandChannel.SONY_VENDOR_ID }
            .filter { it.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
            .map { UsbPtpCamera(it, it.deviceName ?: it.productName ?: "索尼相机") }
    }

    /** 尚未授权的索尼设备，需要用户点确认。 */
    fun listPendingPermission(): List<UsbDevice> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { it.vendorId == UsbPtpCommandChannel.SONY_VENDOR_ID }
            .filterNot { manager.hasPermission(it) }
    }

    /**
     * 发起授权请求。系统弹窗由用户确认，结果通过 [registerPermissionReceiver] 回调。
     */
    fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(UsbPtpCommandChannel.ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.requestPermission(device, intent)
    }

    /** 注册授权结果接收器。用完必须 [unregisterPermissionReceiver]，否则泄漏。 */
    fun registerPermissionReceiver(onResult: (UsbDevice, Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                onResult(device, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(UsbPtpCommandChannel.ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }

    fun unregisterPermissionReceiver(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

/**
 * 把「已授权设备」变成可用的命令通道。
 *
 * 与 [UsbPtpDiscovery] 分开是因为授权是异步且需要生命周期的，而通道的建立是同步的。
 */
class UsbPtpConnector(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** 为已授权设备建立通道；未授权或不是 PTP 形态时返回 null。 */
    fun open(camera: UsbPtpCamera): UsbPtpCommandChannel? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(camera.device)) {
            AppLog.w(TAG, "设备 ${camera.deviceName} 尚未获得 USB 授权")
            return null
        }
        val connection = manager.openDevice(camera.device) ?: run {
            AppLog.w(TAG, "openDevice 返回 null：设备已被拔出或被占用")
            return null
        }
        return try {
            UsbPtpCommandChannel.open(connection, camera.device)
        } catch (e: PtpIoException) {
            runCatching { connection.close() }
            AppLog.w(TAG, "打开 USB PTP 通道失败：${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "usbptp"
    }
}