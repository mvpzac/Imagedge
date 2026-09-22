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
    val sourceFile: java.io.File,
    val mimeType: String,
    val startOffset: Long,
    val endOffset: Long,
) {
    val length: Long get() = endOffset - startOffset
}
