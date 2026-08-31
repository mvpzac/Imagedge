package com.imagedge.camera.motionphoto.internal.format

internal fun indexOfSubarray(
    array: ByteArray,
    subarray: ByteArray,
    startFrom: Int = 0,
): Int {
    if (subarray.isEmpty() || array.size < subarray.size) {
        return -1
    }

    val firstByte = subarray[0]
    var startIndex = startFrom
    while (startIndex <= array.size - subarray.size) {
        var found = false
        for (index in startIndex..array.size - subarray.size) {
            if (array[index] == firstByte) {
                startIndex = index
                found = true
                break
            }
        }
        if (!found) {
            return -1
        }

        var matches = true
        for (index in subarray.indices) {
            if (array[startIndex + index] != subarray[index]) {
                matches = false
                break
            }
        }
        if (matches) {
            return startIndex
        }
        startIndex++
    }

    return -1
}

internal fun looksLikeJpeg(sourceBytes: ByteArray, offset: Int): Boolean {
    return offset + 1 < sourceBytes.size &&
        sourceBytes[offset] == 0xFF.toByte() &&
        sourceBytes[offset + 1] == 0xD8.toByte()
}

internal fun looksLikeIsoBaseMedia(sourceBytes: ByteArray, offset: Int): Boolean {
    if (offset + 8 > sourceBytes.size) {
        return false
    }
    return String(sourceBytes, offset + 4, 4, Charsets.US_ASCII) == "ftyp"
}

internal fun looksLikeFileType(
    sourceBytes: ByteArray,
    offset: Int = 0,
    signature: String,
): Boolean {
    return offset + 12 <= sourceBytes.size &&
        String(sourceBytes, offset + 4, 8, Charsets.US_ASCII) == signature
}

internal fun inferIsoBaseMediaMime(
    sourceBytes: ByteArray,
    offset: Int = 0,
): String {
    if (offset + 12 > sourceBytes.size) {
        return "video/mp4"
    }
    return when (String(sourceBytes, offset + 8, 4, Charsets.US_ASCII)) {
        "qt  " -> "video/quicktime"
        else -> "video/mp4"
    }
}

internal fun extensionForMime(mimeType: String): String {
    return when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        "video/quicktime" -> "mov"
        "video/mp4" -> "mp4"
        else -> "bin"
    }
}
