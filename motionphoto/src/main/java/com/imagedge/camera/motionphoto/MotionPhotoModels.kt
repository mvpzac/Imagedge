package com.imagedge.camera.motionphoto

import java.io.File

/** Result of parsing a Motion Photo and extracting its binary payloads. */
data class MotionPhotoParseResult(
    val imageFile: File,
    val videoFile: File,
    val gainMapFile: File?,
    val sourceBytes: Int,
    val imageBytes: Int,
    val videoBytes: Int,
    val gainMapBytes: Int?,
    val imageMimeType: String,
    val videoMimeType: String,
    val gainMapMimeType: String?,
    val metadataSource: MetadataSource,
    val xmpSummary: XmpSummary,
    val gainMapSummary: GainMapSummary?,
    val imageEndOffset: Int,
    val videoStartOffset: Int,
    val gainMapStartOffset: Int?,
)

/** Indicates which metadata convention was used to locate the embedded video. */
enum class MetadataSource {
    CONTAINER_DIRECTORY,
    LEGACY_MICRO_VIDEO_OFFSET,
}

/** Parsed high-level Motion Photo metadata extracted from the preferred XMP packet. */
data class XmpSummary(
    val motionPhotoFlag: Int?,
    val motionPhotoVersion: Int?,
    val presentationTimestampUs: Long?,
    val microVideoOffset: Int?,
    val items: List<ContainerItem>,
)

/** One entry from the Motion Photo container directory. */
data class ContainerItem(
    val semantic: String?,
    val mimeType: String?,
    val length: Int?,
    val padding: Int?,
)

/** Parsed Ultra HDR GainMap metadata when present in the embedded gain map JPEG. */
data class GainMapSummary(
    val version: String?,
    val baseRenditionIsHDR: String?,
    val gainMapMin: String?,
    val gainMapMax: String?,
    val gamma: String?,
    val offsetSdr: String?,
    val offsetHdr: String?,
    val hdrCapacityMin: String?,
    val hdrCapacityMax: String?,
)

/** Result of composing a Motion Photo, including verification output from a re-parse pass. */
data class MotionPhotoComposeResult(
    val motionPhotoFile: File,
    val displayName: String,
    val sourceImageMimeType: String,
    val imageMimeType: String,
    val sourceImageHasGainMap: Boolean,
    val outputImageHasGainMap: Boolean,
    val imageProcessingDescription: String,
    val sourceVideoMimeType: String,
    val videoMimeType: String,
    val videoProcessingDescription: String,
    val preparedVideoFile: File?,
    val totalBytes: Int,
    val xmpPacket: String,
    val verificationResult: MotionPhotoParseResult,
)
