package com.imagedge.camera.motionphoto.internal.compose

import java.io.File

internal data class PreparedImage(
    val preparedFile: File,
    val sourceMimeType: String,
    val sourceHasGainMap: Boolean,
    val outputHasGainMap: Boolean,
    val ultraHdrInfo: UltraHdrInfo?,
    val processingDescription: String,
)

internal data class PreparedVideo(
    val sourceMimeType: String,
    val outputMimeType: String,
    val processingDescription: String,
    val preparedFile: File,
    val presentationTimestampUs: Long,
    val oplusPresentationTimestampUs: Long,
)

internal data class UltraHdrInfo(
    val gainMapLengthBytes: Int,
    val hdrgmVersion: String?,
)
