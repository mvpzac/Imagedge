package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.MuxerUtil
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.MotionPhotoComposeResult
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import com.imagedge.camera.motionphoto.internal.parse.MotionPhotoParseEngine
import com.imagedge.camera.motionphoto.internal.xmp.extractPreferredMotionPhotoXmp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.WritableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object MotionPhotoComposeEngine {

    /** 裁剪 + 转码归一透传（供 facade 暴露；封面帧须从裁剪后文件抽取，故独立于 compose） */
    suspend fun trim(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        audioOn: Boolean = true,
        cropLTRB: FloatArray? = null,
        targetW: Int = 0,
        targetH: Int = 0,
    ): File = withContext(Dispatchers.IO) {
        VideoTrimmer.trim(context, videoUri, startMs, endMs, audioOn, cropLTRB, targetW, targetH)
    }

    suspend fun compose(
        context: Context,
        imageUri: Uri,
        videoUri: Uri,
        coverTimestampUs: Long = -1L,
        exifSourceUri: Uri? = null,
    ): MotionPhotoComposeResult {
        val outputDir = withContext(Dispatchers.IO) {
            MotionPhotoTempFiles.resetAllWorkingDirectories(context.cacheDir)
            MotionPhotoTempFiles.resetCacheDirectory(context.cacheDir, "motion-photo-compose")
        }
        val preparedImage = withContext(Dispatchers.IO) {
            MotionPhotoStillImagePreparer.prepare(context, imageUri, outputDir, exifSourceUri)
        }
        val preparedVideo = withContext(Dispatchers.IO) {
            // coverTimestampUs ≥ 0 表示用户自选封面帧：跳过「视频中间帧」自动探测。
            // 官方规范：XMP 缺省时间戳时 reader 播放视频中间帧——封面图与时间戳必须双写，
            // 否则会出现「静止画面是一帧、按住播放跳到另一帧」的割裂感。
            MotionPhotoVideoPreparer.prepare(context, videoUri, coverTimestampUs)
        }

        val displayName = MotionPhotoTempFiles.newMotionPhotoDisplayName()
        val composedFile = File(outputDir, displayName)

        withContext(Dispatchers.IO) {
            createMotionPhoto(
                preparedImage = preparedImage,
                preparedVideo = preparedVideo,
                composedFile = composedFile,
            )
        }

        val xmpPacket = withContext(Dispatchers.IO) {
            extractPreferredMotionPhotoXmp(composedFile.readBytes()).orEmpty()
        }
        val verificationResult = withContext(Dispatchers.IO) {
            MotionPhotoParseEngine.parse(context, Uri.fromFile(composedFile))
        }

        return MotionPhotoComposeResult(
            motionPhotoFile = composedFile,
            displayName = displayName,
            sourceImageMimeType = preparedImage.sourceMimeType,
            imageMimeType = "image/jpeg",
            sourceImageHasGainMap = preparedImage.sourceHasGainMap,
            outputImageHasGainMap = preparedImage.outputHasGainMap,
            imageProcessingDescription = preparedImage.processingDescription,
            sourceVideoMimeType = preparedVideo.sourceMimeType,
            videoMimeType = preparedVideo.outputMimeType,
            videoProcessingDescription = preparedVideo.processingDescription,
            preparedVideoFile = preparedVideo.preparedFile,
            totalBytes = composedFile.length().toInt(),
            xmpPacket = xmpPacket,
            verificationResult = verificationResult,
        )
    }

    @OptIn(UnstableApi::class)
    private fun createMotionPhoto(
        preparedImage: PreparedImage,
        preparedVideo: PreparedVideo,
        composedFile: File,
    ) {
        FileInputStream(preparedImage.preparedFile).use { imageInputStream ->
            FileInputStream(preparedVideo.preparedFile).use { videoInputStream ->
                FileOutputStream(composedFile).channel.use { outputChannel ->
                    createMotionPhotoWithMedia3(
                        imageInputStream = imageInputStream,
                        imagePresentationTimestampUs = preparedVideo.presentationTimestampUs,
                        videoInputStream = videoInputStream,
                        videoContainerMimeType = preparedVideo.outputMimeType,
                        outputChannel = outputChannel,
                    )
                }
            }
        }

        MotionPhotoJpegEditor.alignVendorCompatibleMotionPhotoXmp(
            motionPhotoFile = composedFile,
            videoLengthBytes = preparedVideo.preparedFile.length(),
            videoMimeType = preparedVideo.outputMimeType,
            presentationTimestampUs = preparedVideo.oplusPresentationTimestampUs,
            ultraHdrInfo = preparedImage.ultraHdrInfo,
        )
        MotionPhotoJpegEditor.alignWechatCompatibleJpegHeaders(
            motionPhotoFile = composedFile,
            videoLengthBytes = preparedVideo.preparedFile.length(),
        )
    }

    @OptIn(UnstableApi::class)
    private fun createMotionPhotoWithMedia3(
        imageInputStream: FileInputStream,
        imagePresentationTimestampUs: Long,
        videoInputStream: FileInputStream,
        videoContainerMimeType: String,
        outputChannel: WritableByteChannel,
    ) {
        try {
            MuxerUtil.createMotionPhotoFromJpegImageAndBmffVideo(
                imageInputStream,
                imagePresentationTimestampUs,
                videoInputStream,
                videoContainerMimeType,
                outputChannel,
            )
        } catch (error: Exception) {
            throw MotionPhotoComposeException(
                "Media3 Motion Photo packaging failed: ${error.message ?: "Unknown error"}",
            )
        }
    }
}
