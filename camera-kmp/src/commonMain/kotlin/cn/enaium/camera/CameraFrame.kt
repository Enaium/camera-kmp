package cn.enaium.camera

/**
 * One captured picture: tightly packed [data] in [format].
 *
 * Frames are immutable snapshots owned by the receiver. A backend allocates a
 * new buffer per frame, so retaining a frame never stalls the capture thread
 * and no `close()` is required.
 *
 * [rotationDegrees] and [mirrored] describe the transforms that were already
 * applied, not transforms the receiver still has to apply.
 */
class CameraFrame(
    /** Effective format, after rotation and conversion. */
    val format: CameraFormat,
    /** Tightly packed planes, see [PixelFormat.frameSizeBytes]. */
    val data: ByteArray,
    /** Capture timestamp on the platform's monotonic clock, in nanoseconds. */
    val timestampNanos: Long,
    /** Clockwise rotation that was applied, 0/90/180/270. */
    val rotationDegrees: Int = 0,
    /** Whether a horizontal mirror was applied. */
    val mirrored: Boolean = false,
) {
    init {
        require(rotationDegrees % 90 == 0) { "rotationDegrees must be a multiple of 90: $rotationDegrees" }
        require(data.size >= format.frameSizeBytes) {
            "frame buffer is too small: ${data.size} bytes for $format (${format.frameSizeBytes} needed)"
        }
    }

    val width: Int get() = format.width

    val height: Int get() = format.height

    val pixelFormat: PixelFormat get() = format.pixelFormat

    /** Byte offset of the U plane in [data], or -1 for packed formats. */
    val uOffset: Int get() = pixelFormat.uOffset(width, height)

    /** Byte offset of the V plane in [data], or -1 for NV12/NV21 (interleaved chroma). */
    val vOffset: Int
        get() = when (pixelFormat) {
            PixelFormat.I420 -> width * height + ((width + 1) / 2) * ((height + 1) / 2)
            else -> -1
        }

    /**
     * Converts this frame to planar I420, independently of its current layout.
     *
     * The conversion is a no-op copy when the frame is already I420.
     */
    fun toI420(): I420Frame = FrameProcessor.toI420(this)

    /** Bytes of one row of [data] for the first plane. */
    val rowStrideBytes: Int
        get() = when (pixelFormat) {
            PixelFormat.I420, PixelFormat.NV12, PixelFormat.NV21 -> width
            PixelFormat.YUY2, PixelFormat.UYVY -> width * 2
            PixelFormat.RGBA, PixelFormat.BGRA -> width * 4
            PixelFormat.RGB24 -> width * 3
        }

    override fun toString(): String =
        "CameraFrame($format, rotation=$rotationDegrees, mirrored=$mirrored, " +
            "bytes=${data.size}, ts=$timestampNanos)"
}

/**
 * A planar 8-bit I420 picture with tightly packed planes.
 *
 * This is the interchange format of the library and the layout the H.264/H.265
 * encoders in the examples consume.
 */
class I420Frame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val timestampNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive, was ${width}x$height" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        require(y.size >= width * height) { "Y plane is too small: ${y.size} < ${width * height}" }
        require(u.size >= chromaWidth * chromaHeight) {
            "U plane is too small: ${u.size} < ${chromaWidth * chromaHeight}"
        }
        require(v.size >= chromaWidth * chromaHeight) {
            "V plane is too small: ${v.size} < ${chromaWidth * chromaHeight}"
        }
    }

    val chromaWidth: Int get() = (width + 1) / 2

    val chromaHeight: Int get() = (height + 1) / 2

    /**
     * Packs this picture into a [CameraFrame] of [pixelFormat], applying
     * [rotationDegrees] and an optional horizontal mirror.
     */
    fun toCameraFrame(
        pixelFormat: PixelFormat = PixelFormat.I420,
        rotationDegrees: Int = 0,
        mirrorHorizontally: Boolean = false,
        frameRate: Int = CameraFormat.DEFAULT_FRAME_RATE,
    ): CameraFrame = FrameProcessor.fromI420(this, pixelFormat, rotationDegrees, mirrorHorizontally, frameRate)

    /** The three planes concatenated in I420 order. */
    fun toByteArray(): ByteArray {
        val out = ByteArray(width * height + chromaWidth * chromaHeight * 2)
        y.copyInto(out, 0, 0, width * height)
        val uStart = width * height
        u.copyInto(out, uStart, 0, chromaWidth * chromaHeight)
        v.copyInto(out, uStart + chromaWidth * chromaHeight, 0, chromaWidth * chromaHeight)
        return out
    }

    override fun toString(): String = "I420Frame(${width}x$height, ts=$timestampNanos)"
}
