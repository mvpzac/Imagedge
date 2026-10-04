package com.imagedge.camera.motionphoto.internal.io

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal object MotionPhotoTempFiles {

    /**
     * compose 启动时可以安全清空的工作目录：只放**compose 自己这次会重新产出**的东西。
     * 这些目录此前只建不删，每次转码/裁剪/拼接都残留数十 MB 临时文件。
     *
     * **`motion-photo-trim` / `motion-photo-stitch` / `motion-photo-cover` 不在此列，
     * 且不能加回来。** 这三个目录的产物是**调用方传给 compose 的输入**：
     * 「视频转 LIVE 图」把 `VideoTrimmer` 的产物当 `videoUri`、把
     * `MotionPhotoVideoCoverExtractor` 的产物当 `imageUri`；三拼把 `VideoStitcher`
     * 的产物当 `videoUri`。而 compose 的第一件事就是 [resetAllWorkingDirectories]，
     * 名单里若有它们，compose 就会在读取之前删掉自己的输入，
     * 每次导出都以 `open failed: ENOENT` 告终——2026-10-04 之前两条导出路径
     * 从未成功过，正是这份名单造成的。
     * 这三类的清理由各自的生产者负责：`VideoToLivePhotoViewModel.exportOne` 的
     * `finally` 删 trimmed 与 cover，三拼的 `cleanupExportFiles` 删 trimmed 与 stitched。
     *
     * `motion-photo-audio-norm` 可以留在这里：它的产物在 `VideoTrimmer` 内部
     * 即时被消费完，到 compose 时已是上一轮的残留。
     */
    internal val workingDirectories = listOf(
        "motion-photo-work",
        "motion-photo-audio-norm",
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
