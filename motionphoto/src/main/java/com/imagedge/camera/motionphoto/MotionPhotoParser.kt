package com.imagedge.camera.motionphoto

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.imagedge.camera.motionphoto.internal.parse.MotionPhotoParseEngine
import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.isMotionPhotoXmp

/**
 * Public facade for Motion Photo inspection and extraction.
 */
object MotionPhotoParser {
    fun parse(
        context: Context,
        sourceUri: Uri,
    ): MotionPhotoParseResult {
        return MotionPhotoParseEngine.parse(context, sourceUri)
    }

    /**
     * Checks the current row of a MediaStore query cursor using the `xmp` column.
     *
     * Add `MediaStore.MediaColumns.XMP` to the projection on API 30+.
     */
    fun isMotionPhoto(cursor: Cursor): Boolean {
        val xmp = cursor.readMediaStoreXmp() ?: return false
        return isMotionPhotoXmp(xmp)
    }

    private fun Cursor.readMediaStoreXmp(): String? {
        val columnIndex = getColumnIndex(MEDIA_STORE_XMP_COLUMN)
        if (columnIndex == -1 || isNull(columnIndex)) {
            return null
        }

        return when (getType(columnIndex)) {
            Cursor.FIELD_TYPE_BLOB -> getBlob(columnIndex)?.let(::decodeXmp)
            Cursor.FIELD_TYPE_STRING -> getString(columnIndex)
            else -> null
        }
    }

    private const val MEDIA_STORE_XMP_COLUMN = "xmp"
}
