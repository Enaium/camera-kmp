package cn.enaium.camera

/**
 * One camera endpoint and the capture modes it advertised.
 *
 * The list is a snapshot taken when [CameraSystem.cameras] was called; devices
 * can be plugged in or removed afterwards, so enumerate again before opening a
 * device that may have changed.
 */
data class CameraInfo(
    /** Backend identifier. Stable while the device exists. */
    val id: String,
    /** Human readable name, for example `"FaceTime HD Camera"`. */
    val name: String,
    /** Direction of the camera. */
    val lensFacing: CameraLensFacing,
    /** Clockwise degrees between the sensor's native orientation and the device's natural orientation. */
    val sensorOrientation: Int = 0,
    /** How the camera is attached. */
    val transport: CameraTransport = CameraTransport.INTERNAL,
    /** `true` when the backend would pick this camera for its [lensFacing]. */
    val isDefault: Boolean = false,
    /** Linux/Android device node, for example `/dev/video0`, when the backend has one. */
    val devicePath: String? = null,
    /** Every capture mode the backend advertised, in backend order. */
    val supportedFormats: List<CameraFormat> = emptyList(),
) {
    /** Distinct resolutions of [supportedFormats], largest first. */
    val resolutions: List<CameraResolution>
        get() = supportedFormats.asSequence()
            .map { it.resolution }
            .distinct()
            .sortedWith(compareByDescending<CameraResolution> { it.pixelCount }.thenBy { it.width })
            .toList()

    /** Native pixel layouts the backend can produce without a conversion. */
    val nativePixelFormats: List<PixelFormat>
        get() = supportedFormats.asSequence().map { it.pixelFormat }.distinct().toList()

    /** Frame rates advertised for [resolution], ascending. */
    fun frameRates(resolution: CameraResolution): List<Int> = supportedFormats.asSequence()
        .filter { it.resolution == resolution }
        .map { it.frameRate }
        .distinct()
        .sorted()
        .toList()

    /** `true` when the backend advertised exactly [format] as a native mode. */
    fun supports(format: CameraFormat): Boolean = supportedFormats.any {
        it.width == format.width &&
            it.height == format.height &&
            it.frameRate == format.frameRate &&
            it.pixelFormat == format.pixelFormat
    }

    /**
     * Picks the capture mode closest to [width] x [height].
     *
     * The selection prefers a mode at or above the requested pixel count with
     * the closest aspect ratio, then the closest frame rate. When every mode is
     * smaller, the largest one is returned. Returns `null` only when
     * [supportedFormats] is empty.
     */
    fun bestFormat(
        width: Int,
        height: Int,
        pixelFormat: PixelFormat? = null,
        frameRate: Int = CameraFormat.DEFAULT_FRAME_RATE,
    ): CameraFormat? {
        val candidates = supportedFormats.filter { pixelFormat == null || it.pixelFormat == pixelFormat }
            .ifEmpty { supportedFormats }
        if (candidates.isEmpty()) return null

        val targetPixels = width.toLong() * height
        val targetAspect = width.toDouble() / height
        val above = candidates.filter { it.resolution.pixelCount >= targetPixels }
        val pool = above.ifEmpty { candidates }

        return pool.minWithOrNull(
            compareBy<CameraFormat> { candidate ->
                // Aspect ratio first: a wrong shape looks worse than a wrong size.
                val aspectPenalty = (candidate.resolution.aspectRatio - targetAspect).let { if (it < 0) -it else it }
                (aspectPenalty * 1000).toLong()
            }.thenBy { candidate ->
                val sizePenalty = if (candidate.resolution.pixelCount >= targetPixels) {
                    candidate.resolution.pixelCount - targetPixels
                } else {
                    (targetPixels - candidate.resolution.pixelCount) * 4
                }
                sizePenalty
            }.thenBy { candidate -> if (candidate.frameRate >= frameRate) candidate.frameRate - frameRate else (frameRate - candidate.frameRate) * 4 },
        )
    }

    /** `"FaceTime HD Camera (0): FRONT, 1280x720 ..."`. */
    override fun toString(): String {
        val modes = supportedFormats.take(4).joinToString(", ")
        val more = if (supportedFormats.size > 4) ", ..." else ""
        return "$name ($id): $lensFacing, ${supportedFormats.size} modes [$modes$more]"
    }
}
