package cn.enaium.camera

/**
 * Everything needed to open a session.
 *
 * [format] describes the frames the caller wants; the backend picks the closest
 * native capture mode and converts to [CameraFormat.pixelFormat] before
 * delivering frames. [rotationDegrees] and [mirrorHorizontally] are applied to
 * every frame; they can be changed later with
 * [CameraSession.setRotation]/[CameraSession.setMirror].
 *
 * ```kotlin
 * val config = CameraConfig(
 *     format = CameraFormat(1280, 720, PixelFormat.I420, 30),
 *     lensFacing = CameraLensFacing.FRONT,
 *     mirrorHorizontally = true,
 * )
 * cameraSystem().open(config).use { session ->
 *     session.start { frame -> consume(frame) }
 * }
 * ```
 */
data class CameraConfig(
    /** Frames the caller wants. */
    val format: CameraFormat = CameraFormat.VGA,
    /** Which camera to open when [cameraId] is not set. */
    val lensFacing: CameraLensFacing = CameraLensFacing.BACK,
    /** Explicit camera id from [CameraInfo.id]; overrides [lensFacing]. */
    val cameraId: String? = null,
    /** Clockwise rotation applied to every frame; a multiple of 90. */
    val rotationDegrees: Int = 0,
    /** Mirror every frame horizontally (the usual setting for a selfie view). */
    val mirrorHorizontally: Boolean = false,
    /** Frames the backend queues before dropping the oldest one. */
    val bufferCount: Int = 4,
) {
    init {
        require(rotationDegrees % 90 == 0) { "rotationDegrees must be a multiple of 90: $rotationDegrees" }
        require(bufferCount > 0) { "bufferCount must be positive: $bufferCount" }
    }

    /** The frames this configuration asks for, with rotation applied to the size. */
    val outputFormat: CameraFormat
        get() = if (rotationDegrees % 180 == 0) {
            format
        } else {
            format.copy(width = format.height, height = format.width)
        }
}
