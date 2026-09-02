package com.imagedge.camera.share

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 导出器：把已传输到本机的照片按 [ExportConfig] 生成**可分享的缓存副本**。
 *
 * 关键设计：
 * - **绝不改写原图**：产物一律写在 cacheDir，原照片（相册 / SAF 目录）不受影响
 * - **方向归一化**：按源图 EXIF 的 Orientation 把像素转正，
 *   并把新文件的 Orientation 写成正常（0）。不处理的话，相机竖拍的照片
 *   分享出去会是躺着的——这是最容易被忽略、又最影响观感的问题
 * - **元数据可控**：按 [ExifPolicy] 决定保留/清除 GPS 或全部 EXIF
 *
 * @param context 应用上下文（不持有 Activity，避免泄漏）
 */
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "share"
        private const val EXPORT_DIR = "share_export"

        /**
         * 解码长边上限（保护性阈值）。
         *
         * 相机 RAW 内嵌的 JPEG 可达 6000×4000 以上，整图解码约需 96MB；
         * 低端机上连续导出多张容易 OOM。超过此阈值时按 2 的幂采样，
         * 「原图」档位在超大图上会略降采样——换取稳定性。
         */
        private const val MAX_DECODE_EDGE = 8000

        /**
         * 需要复制的 EXIF 字段（摄影常用子集）。
         *
         * 不做全量遍历：androidx.exifinterface 没有暴露「全部 tag」枚举，
         * 这里覆盖相机出片实际会写入的字段即可。
         */
        private val COPY_TAGS = arrayOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_SOFTWARE
        )

        /** GPS 相关字段——[ExifPolicy.STRIP_LOCATION] 时跳过这些 */
        private val GPS_TAGS = arrayOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD
        )
    }

    /**
     * 导出单张，返回可通过 FileProvider 分享的 content:// Uri。
     * @return Result：成功为可分享 Uri，失败为异常（调用方应提示用户）
     */
    suspend fun export(source: Uri, config: ExportConfig): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decodeOriented(source, config.size)
                    ?: throw IllegalArgumentException("无法解码该图片")
                try {
                    val target = scaleTo(bitmap, config.size)
                    try {
                        val file = newOutputFile(source, config)
                        writeBitmap(target, file, config)
                        applyMetadata(source, file, config)
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.sharefileprovider",
                            file
                        )
                    } finally {
                        if (target !== bitmap) target.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }.onFailure {
                AppLog.e(TAG, "导出失败（${source.lastPathSegment}）：${it.message}")
            }
        }

    /**
     * 批量导出。单张失败不影响其余（跳过并记日志），返回成功的 Uri 列表。
     */
    suspend fun exportAll(
        sources: List<Uri>,
        config: ExportConfig,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<Uri> = withContext(Dispatchers.IO) {
        val out = ArrayList<Uri>(sources.size)
        sources.forEachIndexed { index, uri ->
            export(uri, config).onSuccess { out.add(it) }
            onProgress(index + 1, sources.size)
        }
        out
    }

    /** 清理所有导出副本（缓存目录，可安全删除） */
    fun clearExports() {
        runCatching {
            File(context.cacheDir, EXPORT_DIR).deleteRecursively()
        }.onFailure { AppLog.w(TAG, "清理导出缓存失败：${it.message}") }
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────

    /** 打开源图流（content:// 与 file:// 都支持） */
    private fun openStream(uri: Uri) =
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.openInputStream(uri)
        } else {
            uri.path?.let { File(it).takeIf { f -> f.exists() }?.inputStream() }
        }

    /** 读取源图方向（仅 JPEG/WebP 有 EXIF） */
    private fun readOrientation(uri: Uri): Int = runCatching {
        openStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 解码并按 EXIF 方向旋转为「所见即所得」的位图。
     *
     * BitmapFactory 返回的是未经 EXIF 旋转的原始像素，因此竖拍照片在这里
     * 必须转正，否则后续缩放/编码都会基于错误方向。
     */
    private fun decodeOriented(uri: Uri, size: ExportSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val sample = inSampleSize(w, h, size)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotated = applyOrientation(raw, readOrientation(uri))
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** 按 2 的幂计算采样率（同时兼顾超大图的内存保护） */
    private fun inSampleSize(w: Int, h: Int, size: ExportSize): Int {
        var sample = 1
        // 超大图保护
        while ((maxOf(w, h) / sample) > MAX_DECODE_EDGE) sample *= 2
        // 目标档位：先粗采样到目标附近，后续再做精确缩放
        val target = size.maxLongEdge
            ?: if (size == ExportSize.M2) null else null
        if (target != null) {
            while ((maxOf(w, h) / sample) > target * 2) sample *= 2
        } else if (size == ExportSize.M2) {
            while ((w / sample) * (h / sample) > ExportSize.M2_MAX_PIXELS * 4) sample *= 2
        }
        return sample
    }

    /** 精确缩放到目标档位 */
    private fun scaleTo(bitmap: Bitmap, size: ExportSize): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = when (size) {
            ExportSize.ORIGINAL -> return bitmap
            ExportSize.M2 -> {
                val pixels = w.toLong() * h.toLong()
                if (pixels <= ExportSize.M2_MAX_PIXELS) return bitmap
                kotlin.math.sqrt(ExportSize.M2_MAX_PIXELS.toDouble() / pixels).toFloat()
            }
            else -> {
                val target = size.maxLongEdge ?: return bitmap
                val longEdge = maxOf(w, h)
                if (longEdge <= target) return bitmap
                target.toFloat() / longEdge
            }
        }
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    /** 按 EXIF Orientation 旋转（含镜像与转置） */
    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrDefault(source)
    }

    /** 生成缓存副本文件（文件名带时间戳，避免同名覆盖） */
    private fun newOutputFile(source: Uri, config: ExportConfig): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val raw = source.lastPathSegment?.substringAfterLast('/') ?: "image"
        val base = raw.substringBeforeLast('.').ifBlank { "image" }
        val safe = base.replace(Regex("[^\\w.\\-]"), "_").take(40)
        return File(dir, "${safe}_${System.currentTimeMillis()}.${config.format.extension}")
    }

    private fun writeBitmap(bitmap: Bitmap, file: File, config: ExportConfig) {
        FileOutputStream(file).use { out ->
            when (config.format) {
                ExportFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, config.quality, out)
                // PNG 为无损，quality 参数无效
                ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                ExportFormat.WEBP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, config.quality, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, config.quality, out)
                    }
                }
            }
        }
    }

    /**
     * 按策略写入元数据。
     *
     * 因为像素已经旋转归一，新文件的 Orientation 一律写「正常」，
     * 避免接收方再旋转一次导致方向错乱。
     */
    private fun applyMetadata(source: Uri, file: File, config: ExportConfig) {
        if (!config.format.supportsExif || config.exif == ExifPolicy.STRIP_ALL) return
        runCatching {
            val src = openStream(source)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(file)
            val keepLocation = config.exif != ExifPolicy.STRIP_LOCATION
            for (tag in COPY_TAGS) {
                val value = src.getAttribute(tag) ?: continue
                dst.setAttribute(tag, value)
            }
            if (keepLocation) {
                for (tag in GPS_TAGS) {
                    val value = src.getAttribute(tag) ?: continue
                    dst.setAttribute(tag, value)
                }
            }
            dst.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            dst.saveAttributes()
        }.onFailure {
            // 元数据写入失败不影响分享本身（像素已落盘），仅记录
            AppLog.w(TAG, "写入 EXIF 失败（不影响分享）：${it.message}")
        }
    }
}
