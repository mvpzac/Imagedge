package com.imagedge.camera.feature.control.monitoring

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.imagedge.camera.core.common.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 取景截图落盘（T1）
 *     version: 1.0
 * </pre>
 */

/**
 * 把当前取景帧存成「取景截图」。
 *
 * **存的是相机回传的预览帧本身，不含镜像/旋转/缩放，也不画网格与比例标记。**
 * 这不是遗漏，而是刻意的：镜像与标记是**监看辅助**，把它们烘焙进文件就等于让一个
 * 「截图」悄悄改写了相机给出的画面内容，与 T1「所有工具都不改变画面内容」的口径冲突。
 * 需要带辅助线的成片，应由用户自行决定（那属 T2 之后的导出议题，不在本包内）。
 *
 * 也因此它的尺寸天然就是预览信号的尺寸（当前约 960×640），**不是**相机原片尺寸。
 * 这一点同时写进文件名前缀、MediaStore 的 DESCRIPTION 与 WIDTH/HEIGHT，
 * 避免事后被当成一张小尺寸的照片。
 *
 * 与下载管线的边界：截图不进下载队列、不写下载目录设置、不参与断点续传，
 * 也完全不触达相机——它只是本机的一张图片。
 */
@Singleton
class ViewfinderSnapshotWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * @param bitmap 取景帧。由 [com.imagedge.camera.feature.control.CameraControlViewModel]
     *               的下采样解码产出，尺寸受控（≤ 目标宽高），故此处不再另设大小上限
     * @param capturedAt 画面被采集的时刻（毫秒），写入 DATE_TAKEN 以便相册按拍摄时间排序
     * @return 落盘后的 MediaStore Uri
     * @throws IOException 压缩或写流失败（失败时不会留下半成品条目）
     */
    suspend fun write(bitmap: Bitmap, capturedAt: Long = System.currentTimeMillis()): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val displayName = "${FILE_PREFIX}${FILE_STAMP.format(Date(capturedAt))}.jpg"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$DIRECTORY"
                )
                put(MediaStore.MediaColumns.WIDTH, bitmap.width)
                put(MediaStore.MediaColumns.HEIGHT, bitmap.height)
                put(MediaStore.MediaColumns.DATE_TAKEN, capturedAt)
                // 说明写进元数据：图库里能直接看出这不是相机原片
                put(MediaStore.Images.ImageColumns.DESCRIPTION, CAPTURE_NOTE)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在系统相册创建截图条目")

            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        throw IOException("取景帧 JPEG 编码失败")
                    }
                } ?: throw IOException("无法打开截图输出流")

                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                AppLog.i(TAG, "取景截图已保存：$displayName（${bitmap.width}×${bitmap.height}）")
                uri
            } catch (e: Exception) {
                // 与下载同一条规矩：失败必须删掉残条，否则相册里攒出一堆打不开的 0 字节文件
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { AppLog.w(TAG, "删除未完成的截图条目失败：$uri：${it.message}") }
                if (e is IOException) throw e else throw IOException("取景截图保存失败：${e.message}", e)
            }
        }

    private companion object {
        const val TAG = "monitoring"

        /** 独立目录，与下载回来的相机文件分开存放，避免在相册里互相冒充 */
        const val DIRECTORY = "ImagedgeViewfinder"

        /** 文件名前缀即为身份标识，导出后被单独挑出来也不会误认成相机原片 */
        const val FILE_PREFIX = "VIEWFINDER_"

        const val CAPTURE_NOTE = "取景截图：来自相机实时取景预览流，非相机原片，不含监看辅助标记"
        const val JPEG_QUALITY = 92

        val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
