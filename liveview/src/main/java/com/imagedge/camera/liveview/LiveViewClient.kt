package com.imagedge.camera.liveview

import com.imagedge.camera.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : Sony 裸 LiveView 流客户端（JPEG 扫描提取，协议变体无关）
 *     version: 2.1 —— 批量边界扫描（CPU 降一个量级）+ 可中断 IO（退页面立即断流）
 * </pre>
 */

/** 日志 tag */
private const val TAG = "liveview"

/** 单次 read 的块大小 */
private const val READ_CHUNK = 64 * 1024

/** 帧缓冲初始容量（不足时自动扩容；1080p 级 JPEG 约 100~300KB） */
private const val FRAME_INITIAL_CAPACITY = 128 * 1024

/**
 * 空转保护：连续扫描这么多字节仍未产出完整帧即判定流异常并退出。
 * 相机未开启取景 / 返回错误页时，流会持续推送不含 FFD8 的数据，
 * 原实现会一直空转到 readTimeout（15s）才退出，白烧 CPU。
 */
private const val NO_FRAME_GUARD_BYTES = 8 * 1024 * 1024

/** JPEG 标记：SOI(FFD8) / EOI(FFD9) 的第二字节 */
private const val MARK_SOI = 0xD8
private const val MARK_EOI = 0xD9
private const val MARK_FF = 0xFF

/**
 * Sony 裸 LiveView 流客户端
 *
 * 背景：索尼各机型/固件的帧封装存在变体（带/去 0x24 通用包头、magic "$5hy"、
 * 128/136 字节帧头、大小端差异——ZV-E10 实测为小端变体，见流前 64 字节诊断）。
 * 因此本实现不依赖帧结构，直接扫描 JPEG 边界：
 * - JPEG 以 FFD8（SOI）开头、FFD9（EOI）结束；压缩数据内部 0xFF 后不会出现 D8/D9
 *   （FF00 填充 / FFD0-D7 重启标记），故 SOI/EOI 配对可唯一确定帧边界
 * - 帧间封装字节自然被忽略，任何变体/失步均可自愈
 */
class LiveViewClient {

    /**
     * 连接 LiveView 流，持续输出完整 JPEG 帧字节。
     * Cold flow：collect 时建连，取消时断开。
     */
    fun stream(liveViewUrl: String): Flow<ByteArray> = flow {
        val connection = URL(liveViewUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setUseCaches(false)
        AppLog.i(TAG, "LiveView 连接：$liveViewUrl")

        // 建连也可能阻塞，同样用 runInterruptible 包装以便取消
        val raw: InputStream = runInterruptible { connection.getInputStream() }
        val input = BufferedInputStream(raw, READ_CHUNK)
        try {
            val chunk = ByteArray(READ_CHUNK)
            val frame = java.io.ByteArrayOutputStream(FRAME_INITIAL_CAPACITY)
            var collecting = false       // 是否已看到 SOI，正在收集帧体
            var pendingFF = false        // 上一块的最后一个字节是 0xFF（跨块边界）
            var sinceLastFrame = 0       // 距上次产出帧已扫描的字节数

            while (currentCoroutineContext().isActive) {
                // runInterruptible：协程取消（离开取景页）时关闭流，让阻塞的 read 立即抛异常。
                // 原实现只在 read 返回后才检查 isActive，取消后线程仍会阻塞最长 15s
                // ——退后台后还占着相机的 LiveView 通道，属于非必要的持续网络活动。
                val n = runInterruptible { input.read(chunk) }
                if (n == -1) throw IOException("LiveView 流结束")
                if (n <= 0) continue

                sinceLastFrame += n
                var i = 0

                // 跨块边界：上一块以 0xFF 结尾，本块首字节可能是 D8/D9。
                // 语义与原逐字节实现保持一致：collecting 时那个 0xFF 已写入帧体，
                // 非 collecting 时它是帧间填充、直接丢弃。
                if (pendingFF) {
                    val b0 = chunk[0].toInt() and 0xFF
                    if (collecting) {
                        if (b0 == MARK_EOI) {
                            frame.write(MARK_EOI)
                            emit(frame.toByteArray())
                            frame.reset()
                            collecting = false
                            sinceLastFrame = 0
                            i = 1
                        }
                        // b0 非 EOI：上一块结尾的 0xFF 已随批量写入帧体，
                        // 不能再写一次——否则会向扫描数据注入多余 FF（破坏 FF00 填充）
                    } else if (b0 == MARK_SOI) {
                        frame.reset()
                        frame.write(MARK_FF)
                        frame.write(MARK_SOI)
                        collecting = true
                        sinceLastFrame = 0
                        i = 1
                    }
                    pendingFF = false
                }

                // 批量扫描：定位标记后整段 write，避免逐字节虚调用。
                // 原实现每字节都要走一次 ByteArrayOutputStream.write(int)，
                // 18fps × ~60KB ≈ 每秒 108 万次调用，CPU 持续高占用、手机发烫。
                while (i < n) {
                    if (collecting) {
                        val eoi = indexOfMarker(chunk, i, n, MARK_EOI)
                        if (eoi >= 0) {
                            frame.write(chunk, i, eoi - i + 1)   // 含 EOI 两字节
                            emit(frame.toByteArray())
                            frame.reset()
                            collecting = false
                            sinceLastFrame = 0
                            i = eoi + 1
                        } else {
                            frame.write(chunk, i, n - i)
                            i = n
                        }
                    } else {
                        val soi = indexOfMarker(chunk, i, n, MARK_SOI)
                        if (soi >= 0) {
                            frame.reset()
                            frame.write(MARK_FF)
                            frame.write(MARK_SOI)
                            collecting = true
                            sinceLastFrame = 0
                            i = soi + 1
                        } else {
                            i = n
                        }
                    }
                }

                pendingFF = (chunk[n - 1].toInt() and 0xFF) == MARK_FF

                if (sinceLastFrame > NO_FRAME_GUARD_BYTES) {
                    throw IOException(
                        "LiveView 流连续 ${NO_FRAME_GUARD_BYTES} 字节未产出完整帧——" +
                            "相机可能未开启取景或返回了非取景内容"
                    )
                }
            }
        } finally {
            // 先关流再断连接：确保阻塞中的 read 立即返回，连接真正释放
            runCatching { input.close() }
            runCatching { connection.disconnect() }
            AppLog.i(TAG, "LiveView 连接已断开")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 在 chunk[from, until) 中查找 `0xFF marker` 序列。
     * @return marker 字节的索引；未找到返回 -1
     */
    private fun indexOfMarker(chunk: ByteArray, from: Int, until: Int, marker: Int): Int {
        var i = from
        while (i + 1 < until) {
            if ((chunk[i].toInt() and 0xFF) == MARK_FF && (chunk[i + 1].toInt() and 0xFF) == marker) {
                return i + 1
            }
            i++
        }
        return -1
    }
}
