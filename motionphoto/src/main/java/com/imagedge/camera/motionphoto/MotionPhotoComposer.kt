package com.imagedge.camera.motionphoto

import android.content.Context
import android.net.Uri
import com.imagedge.camera.motionphoto.internal.compose.MotionPhotoComposeEngine
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoGalleryWriter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public facade for Motion Photo creation.
 *
 * The public API stays intentionally small while the implementation is split into
 * internal preparation, packaging, metadata, and persistence components.
 */
object MotionPhotoComposer {
    /**
     * 合成 Motion Photo（LIVE 图）。
     *
     * @param coverTimestampUs 封面帧在嵌入视频中的时间戳（us）；<0 表示使用视频中间帧
     * @param exifSourceUri 源素材 URI（原图片/视频）；非空时把其 EXIF 注入成品封面，
     *   使相册中能显示与源一致的机型/参数/拍摄时间（视频源仅取拍摄时间）。
     */
    suspend fun compose(
        context: Context,
        imageUri: Uri,
        videoUri: Uri,
        coverTimestampUs: Long = -1L,
        exifSourceUri: Uri? = null,
    ): MotionPhotoComposeResult {
        return MotionPhotoComposeEngine.compose(context, imageUri, videoUri, coverTimestampUs, exifSourceUri)
    }

    /**
     * 裁剪视频片段并转码归一（H.264 / 短边 ≤1080p / 保留音轨），供「视频转 LIVE 图」
     * 的选段流程使用。封面帧必须从**裁剪后的文件**抽取（时间戳才与嵌入视频对齐），
     * 因此裁剪在合成之前单独暴露。
     *
     * @param audioOn 是否保留音轨
     * @param cropLTRB 可选裁剪 [left,right,bottom,top]（负值=该侧裁掉占比，media3 Crop 语义）
     * @param targetW/targetH 可选精确目标尺寸（>0 时按其输出，三拼统一规格用）
     * @return 裁剪后的临时 MP4 文件（cacheDir，由后续流程使用与清理）
     */
    suspend fun trimVideo(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        audioOn: Boolean = true,
        cropLTRB: FloatArray? = null,
        targetW: Int = 0,
        targetH: Int = 0,
    ): File = withContext(Dispatchers.IO) {
        MotionPhotoComposeEngine.trim(context, videoUri, startMs, endMs, audioOn, cropLTRB, targetW, targetH)
    }

    /**
     * 多段视频顺序拼接（LIVE 三拼）。
     * 输入约定：各段已是统一规格（H.264/1920x1080，经 16:9 横屏校验），
     * 否则拼接点可能跳变——约束校验在调用方（LiveTriptychViewModel）。
     *
     * @param segments 顺序段列表；第二项 = 是否保留该段声音
     * @return 拼接后的临时 MP4 文件
     */
    suspend fun stitchVideos(
        context: Context,
        segments: List<Pair<Uri, Boolean>>,
    ): File = withContext(Dispatchers.IO) {
        com.imagedge.camera.motionphoto.internal.compose.VideoStitcher.stitch(
            context = context,
            segments = segments.map {
                com.imagedge.camera.motionphoto.internal.compose.VideoStitcher.Segment(it.first, it.second)
            },
        )
    }

    fun saveToGallery(
        context: Context,
        composeResult: MotionPhotoComposeResult,
    ): Uri {
        return MotionPhotoGalleryWriter.saveToGallery(context, composeResult)
    }
}
