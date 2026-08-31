package com.imagedge.camera.ptp

import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP 数据类型（对象信息 / 设备信息解析、格式分类、响应码）
 *     version: 1.0
 * </pre>
 */

/** PTP 对象格式码 */
object ObjectFormat {
    const val ASSOCIATION = 0x3001          // 文件夹
    const val JPEG = 0x3801                 // JPEG/EXIF
    const val TIFF = 0x3802                 // TIFF
    const val RAW_SONY = 0xB101             // 索尼 ARW
    const val MP4 = 0x300D                 // MP4 视频
    const val AVCHD = 0x3004               // AVCHD 视频
    const val MTP_MP4 = 0xB981             // MTP MP4
    const val MTP_3GP = 0xB988             // MTP 3GP
    const val MTP_3G2 = 0xB989             // MTP 3G2
    const val MTP_AVCHD = 0xB98A           // MTP AVCHD
}

/** 媒体类型 */
enum class PhotoType { JPEG, RAW, VIDEO, OTHER }

/** PTP 响应码 */
object PtpResponseCode {
    const val OK = 0x2001
    const val SESSION_NOT_OPEN = 0x2003
    const val INVALID_TRANSACTION_ID = 0x2004
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val PARAMETER_NOT_SUPPORTED = 0x2006
    const val INCOMPLETE_TRANSFER = 0x2007
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val STORE_NOT_AVAILABLE = 0x2013
    const val DEVICE_BUSY = 0x2019

    fun description(code: Int): String = when (code) {
        OK -> "OK"
        SESSION_NOT_OPEN -> "会话未打开"
        INVALID_TRANSACTION_ID -> "无效事务 ID"
        OPERATION_NOT_SUPPORTED -> "操作不支持"
        PARAMETER_NOT_SUPPORTED -> "参数不支持"
        INCOMPLETE_TRANSFER -> "传输不完整"
        INVALID_STORAGE_ID -> "无效存储 ID"
        INVALID_OBJECT_HANDLE -> "无效对象句柄"
        STORE_NOT_AVAILABLE -> "存储不可用（StoreNotAvailable，卡内存储未对 PTP 暴露）"
        DEVICE_BUSY -> "设备忙"
        else -> "未知错误（0x${code.toString(16)}）"
    }
}

/** 按格式码 + 文件名后缀分类媒体类型 */
fun classifyFormat(formatCode: Int, filename: String): PhotoType {
    when (formatCode) {
        ObjectFormat.JPEG, ObjectFormat.TIFF -> return PhotoType.JPEG
        ObjectFormat.RAW_SONY -> return PhotoType.RAW
        ObjectFormat.MP4, ObjectFormat.AVCHD,
        ObjectFormat.MTP_MP4, ObjectFormat.MTP_3GP,
        ObjectFormat.MTP_3G2, ObjectFormat.MTP_AVCHD -> return PhotoType.VIDEO
        ObjectFormat.ASSOCIATION -> return PhotoType.OTHER
    }
    val lower = filename.lowercase()
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".mov") ||
        lower.endsWith(".mts") || lower.endsWith(".m2ts") ||
        lower.endsWith(".avi") -> PhotoType.VIDEO
        lower.endsWith(".arw") -> PhotoType.RAW
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> PhotoType.JPEG
        (formatCode and 0x0800) != 0 -> PhotoType.JPEG
        else -> PhotoType.OTHER
    }
}

/**
 * 对象信息（GetObjectInfo 返回）
 */
data class ObjectInfo(
    val storageId: Long,
    val formatCode: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val filename: String,
    val captureDate: Date?,
    val photoType: PhotoType
) {
    companion object {
        /** 从 GetObjectInfo 的响应负载解析 */
        fun parse(buffer: PtpBuffer): ObjectInfo {
            val storageId = buffer.readUInt32()
            val formatCode = buffer.readUInt16()
            buffer.readUInt16()                       // protectionStatus
            val compressedSize = buffer.readUInt32()
            val thumbFormat = buffer.readUInt16()
            val thumbCompressedSize = buffer.readUInt32()
            val thumbPixWidth = buffer.readUInt32()
            val thumbPixHeight = buffer.readUInt32()
            val imageWidth = buffer.readUInt32().toInt()
            val imageHeight = buffer.readUInt32().toInt()
            buffer.readUInt32()                       // imageBitDepth
            buffer.readUInt32()                       // parentObject
            buffer.readUInt16()                       // associationType
            buffer.readUInt32()                       // associationDesc
            buffer.readUInt32()                       // sequenceNumber
            val filename = buffer.readPtpString()
            val captureDate = parsePtpDate(buffer.readPtpString())
            parsePtpDate(buffer.readPtpString())      // modificationDate
            buffer.readPtpString()                    // keywords

            return ObjectInfo(
                storageId = storageId,
                formatCode = formatCode,
                compressedSize = compressedSize,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                filename = filename.ifEmpty { "IMG_$storageId" },
                captureDate = captureDate,
                photoType = classifyFormat(formatCode, filename)
            )
        }

        private fun parsePtpDate(raw: String): Date? {
            if (raw.isEmpty()) return null
            // 典型格式：yyyyMMdd'T'HHmmss 或带时区/小数秒
            return try {
                val clean = raw.replace(Regex("\\.\\d+"), "")
                val formats = listOf("yyyyMMdd'T'HHmmssZ", "yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm")
                val sdf = java.text.SimpleDateFormat("")
                for (f in formats) {
                    sdf.applyPattern(f)
                    try { return sdf.parse(clean) } catch (_: Exception) {}
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 设备信息（GetDeviceInfo 返回的摘要）
 */
data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val functionalMode: Int,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operationsSupported: List<Int>
) {
    companion object {
        fun parse(buffer: PtpBuffer): DeviceInfo {
            val standardVersion = buffer.readUInt16()
            val vendorExtensionId = buffer.readUInt32()
            val vendorExtensionVersion = buffer.readUInt16()
            buffer.readPtpString()                    // vendorExtensionDesc
            val functionalMode = buffer.readUInt16()

            val opsCount = buffer.readUInt32().toInt()
            val operations = (0 until opsCount).map { buffer.readUInt16() }

            val eventsCount = buffer.readUInt32().toInt()
            repeat(eventsCount) { buffer.readUInt16() }

            val propsCount = buffer.readUInt32().toInt()
            repeat(propsCount) { buffer.readUInt16() }

            val captureCount = buffer.readUInt32().toInt()
            repeat(captureCount) { buffer.readUInt16() }

            val imageCount = buffer.readUInt32().toInt()
            repeat(imageCount) { buffer.readUInt16() }

            val manufacturer = buffer.readPtpString()
            val model = buffer.readPtpString()
            val deviceVersion = buffer.readPtpString()
            val serialNumber = buffer.readPtpString()

            return DeviceInfo(
                standardVersion = standardVersion,
                vendorExtensionId = vendorExtensionId,
                functionalMode = functionalMode,
                manufacturer = manufacturer,
                model = model,
                deviceVersion = deviceVersion,
                serialNumber = serialNumber,
                operationsSupported = operations
            )
        }
    }
}
