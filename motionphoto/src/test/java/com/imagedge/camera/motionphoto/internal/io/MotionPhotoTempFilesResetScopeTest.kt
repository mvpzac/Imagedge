package com.imagedge.camera.motionphoto.internal.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : compose 启动时会清空的工作目录名单——凡是「调用方输入」所在的目录都必须在名单外
 * </pre>
 */
class MotionPhotoTempFilesResetScopeTest {

    /**
     * 这三个目录的产物是**调用方传给 compose 的输入**：
     * 「视频转 LIVE 图」传 trim 产物（videoUri）与 cover 产物（imageUri），
     * 三拼传 stitch 产物（videoUri）。而 compose 的第一件事是清空这份名单，
     * 名单里若有它们，compose 就会在读取之前删掉自己的输入，
     * 每次导出以 `open failed: ENOENT` 告终。2026-10-04 之前两条导出路径
     * 从未成功过，正是这份名单造成的。
     */
    @Test
    fun `持有调用方输入的目录不在清理名单里`() {
        for (dir in listOf("motion-photo-trim", "motion-photo-stitch", "motion-photo-cover")) {
            assertFalse(
                "$dir 进名单会让 compose 删掉调用方刚写好的输入",
                dir in MotionPhotoTempFiles.workingDirectories
            )
        }
    }

    /** 反向钉住：清理本身不能被悄悄丢掉，否则每次导出残留数十 MB */
    @Test
    fun `compose 自产的工作目录仍在清理名单里`() {
        for (dir in listOf("motion-photo-work", "motion-photo-audio-norm")) {
            assertTrue("$dir 必须仍被清理", dir in MotionPhotoTempFiles.workingDirectories)
        }
    }
}