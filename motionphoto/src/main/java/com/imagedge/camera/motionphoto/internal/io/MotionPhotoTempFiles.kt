package com.imagedge.camera.motionphoto.internal.io

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal object MotionPhotoTempFiles {

    /**
     * 导出过程中产生的工作目录。这些目录此前只建不删，每次转码/裁剪/拼接/
     * 归一化都残留数十 MB 临时文件；现在每次 compose 启动时统一清空。
     */
    private val workingDirectories = listOf(
        "motion-photo-work",
        "motion-photo-audio-norm",
        "motion-photo-trim",
        "motion-photo-stitch",
        "motion-photo-cover",
    )

    fun resetAllWorkingDirectories(cacheDir: File) {
        workingDirectories.forEach { resetCacheDirectory(cacheDir, it) }
    }

    fun resetCacheDirectory(
        cacheDir: File,
        name: String,
    ): File {
        return File(cacheDir, name).apply {
            mkdirs()
            listFiles()?.forEach(File::delete)
        }
    }

    fun createWorkingFile(
        cacheDir: File,
        directoryName: String,
        prefix: String,
        extension: String,
    ): File {
        val outputDir = File(cacheDir, directoryName).apply {
            mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(
            outputDir,
            "${prefix}_${timestamp}_${UUID.randomUUID().toString().take(8)}.$extension",
        )
    }

    fun newMotionPhotoDisplayName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "MVIMG_${timestamp}_${UUID.randomUUID().toString().take(8)}_MP.jpg"
    }

    fun newExtractionFileId(): String = UUID.randomUUID().toString()
}
