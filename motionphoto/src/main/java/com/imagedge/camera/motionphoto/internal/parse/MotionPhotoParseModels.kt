package com.imagedge.camera.motionphoto.internal.parse

import com.imagedge.camera.motionphoto.ContainerItem
import com.imagedge.camera.motionphoto.MetadataSource

internal data class Extraction(
    val image: BinarySegment,
    val video: BinarySegment,
    val gainMap: BinarySegment?,
    val metadataSource: MetadataSource,
)

internal data class ExtractedItem(
    val item: ContainerItem,
    val segment: BinarySegment,
)

internal data class BinarySegment(
    val bytes: ByteArray,
    val mimeType: String,
    val startOffset: Int,
    val endOffset: Int,
)
