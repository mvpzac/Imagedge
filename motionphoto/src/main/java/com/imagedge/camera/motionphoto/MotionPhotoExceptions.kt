package com.imagedge.camera.motionphoto

/** Base exception type for all Motion Photo library failures. */
open class MotionPhotoException internal constructor(message: String) :
    IllegalStateException(message)

/** Parsing or extraction failed because the source file is not a supported Motion Photo. */
class MotionPhotoParseException internal constructor(message: String) :
    MotionPhotoException(message)

/** Composition failed while preparing media, packaging, or writing the Motion Photo. */
class MotionPhotoComposeException internal constructor(message: String) :
    MotionPhotoException(message)
