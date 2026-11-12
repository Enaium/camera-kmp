package cn.enaium.camera

import kotlin.time.TimeSource

/**
 * One plane of a frame as a backend produced it.
 *
 * [bytes] may be larger than the plane; [offset] is where the plane starts,
 * [rowStride] is the byte distance between rows and [pixelStride] the distance
 * between two samples of a packed/interleaved plane.
 */
internal class SourcePlane(
    val bytes: ByteArray,
    val offset: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val width: Int,
    val height: Int,
) {
    /** A tightly sized view of the plane, copied only when [bytes] carries more. */
    fun slice(): ByteArray {
        val size = rowStride * height
        if (offset == 0 && size == bytes.size) return bytes
        val end = minOf(offset + size, bytes.size)
        return bytes.copyOfRange(offset, end)
    }

    companion object {
        /** A tightly packed plane stored in its own array. */
        fun packed(bytes: ByteArray, width: Int, height: Int, pixelStride: Int = 1): SourcePlane =
            SourcePlane(bytes, 0, width * pixelStride, pixelStride, width, height)
    }
}

/** A frame as a backend produced it, before conversion/rotation/mirroring. */
internal class SourceFrame(
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val planes: List<SourcePlane>,
    val timestampNanos: Long = monotonicNanos(),
)

/** Monotonic clock shared by the frame queue and backends without a hardware timestamp. */
internal object MonotonicClock {
    private val base = TimeSource.Monotonic.markNow()

    fun now(): Long = base.elapsedNow().inWholeNanoseconds
}

internal fun monotonicNanos(): Long = MonotonicClock.now()
