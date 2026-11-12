package cn.enaium.camera

/** A width/height pair of a capture mode. */
data class CameraResolution(val width: Int, val height: Int) {
    init {
        require(width > 0) { "width must be positive: $width" }
        require(height > 0) { "height must be positive: $height" }
    }

    /** Number of pixels of one frame. */
    val pixelCount: Long get() = width.toLong() * height.toLong()

    /** Width divided by height. */
    val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    /** The same resolution with width and height swapped, as a 90 degree rotation produces. */
    fun rotated(): CameraResolution = CameraResolution(height, width)

    override fun toString(): String = "${width}x$height"
}

/**
 * Layout of one YUV/RGB frame.
 *
 * The layout is tightly packed; plane strides are derived from the size. See
 * [frameSizeBytes] for the total byte count.
 */
enum class PixelFormat {
    /** Planar 4:2:0: Y plane followed by U and V planes. */
    I420,

    /** Semi-planar 4:2:0: Y plane followed by interleaved U/V samples. */
    NV12,

    /** Semi-planar 4:2:0: Y plane followed by interleaved V/U samples. */
    NV21,

    /** Packed 4:2:2, byte order Y0 U0 Y1 V0. */
    YUY2,

    /** Packed 4:2:2, byte order U0 Y0 V0 Y1. */
    UYVY,

    /** Packed 8-bit RGBA (byte order R, G, B, A on the little-endian targets of this library). */
    RGBA,

    /** Packed 8-bit BGRA (byte order B, G, R, A on the little-endian targets of this library). */
    BGRA,

    /** Packed 8-bit RGB, no alpha. */
    RGB24,
    ;

    /** `true` for the YUV formats. */
    val isYuv: Boolean
        get() = this == I420 || this == NV12 || this == NV21 || this == YUY2 || this == UYVY

    /** `true` when the format has separate planes. */
    val isPlanar: Boolean
        get() = this == I420 || this == NV12 || this == NV21

    /** Bytes of one frame of [width] x [height] pixels. */
    fun frameSizeBytes(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "frame size must be positive, was ${width}x$height" }
        val luma = width.toLong() * height
        val chroma = (((width + 1) / 2).toLong()) * ((height + 1) / 2)
        // Interleaved chroma rows hold two samples per pixel and are padded to
        // an even byte count, so an odd width costs one byte per chroma row.
        val interleavedChroma = ((width + 1) / 2).toLong() * 2 * ((height + 1) / 2)
        return when (this) {
            I420 -> luma + chroma * 2
            NV12, NV21 -> luma + interleavedChroma
            YUY2, UYVY -> luma * 2
            RGBA, BGRA -> luma * 4
            RGB24 -> luma * 3
        }.toInt()
    }

    /** Byte offset of the U plane for planar formats, or -1 when there is none. */
    fun uOffset(width: Int, height: Int): Int = when (this) {
        I420, NV12, NV21 -> width * height
        else -> -1
    }

    override fun toString(): String = name
}

/**
 * One capture mode: a resolution, a pixel layout and a frame rate.
 *
 * For [CameraInfo.supportedFormats] the pixel format is the layout the platform
 * can deliver natively; a session may still be asked for any [PixelFormat]
 * because the library converts before handing frames to the caller.
 */
data class CameraFormat(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat = PixelFormat.I420,
    val frameRate: Int = DEFAULT_FRAME_RATE,
) {
    init {
        require(width > 0) { "width must be positive: $width" }
        require(height > 0) { "height must be positive: $height" }
        require(frameRate > 0) { "frameRate must be positive: $frameRate" }
    }

    /** The resolution of this mode. */
    val resolution: CameraResolution get() = CameraResolution(width, height)

    /** Bytes one full frame occupies in [pixelFormat]. */
    val frameSizeBytes: Int get() = pixelFormat.frameSizeBytes(width, height)

    /** A copy with another pixel format. */
    fun withPixelFormat(pixelFormat: PixelFormat): CameraFormat = copy(pixelFormat = pixelFormat)

    /** A copy with another frame rate. */
    fun withFrameRate(frameRate: Int): CameraFormat = copy(frameRate = frameRate)

    override fun toString(): String = "${width}x$height $pixelFormat @${frameRate}fps"

    companion object {
        /** Frame rate used when a caller does not request one. */
        const val DEFAULT_FRAME_RATE: Int = 30

        /** 640x480 I420 at 30 fps. */
        val VGA: CameraFormat = CameraFormat(640, 480)

        /** 1280x720 I420 at 30 fps. */
        val HD: CameraFormat = CameraFormat(1280, 720)

        /** 1920x1080 I420 at 30 fps. */
        val FULL_HD: CameraFormat = CameraFormat(1920, 1080)
    }
}
