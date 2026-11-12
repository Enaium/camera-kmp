package cn.enaium.camera

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation

/**
 * Converts every backend frame to the caller's format while applying mirroring
 * and rotation.
 *
 * libyuv consumes tightly packed or strided planes, so backends only have to
 * describe what the OS handed them; the arithmetic of padded rows, interleaved
 * chroma and rotations stays in one place.
 */
internal object FrameProcessor {

    /** Converts a backend frame to planar I420 without applying user transforms. */
    fun toI420(frame: SourceFrame): I420Frame {
        val width = frame.width
        val height = frame.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2

        val y = ByteArray(width * height)
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)

        when (frame.pixelFormat) {
            PixelFormat.I420 -> {
                require(frame.planes.size >= 3) { "I420 needs three planes, got ${frame.planes.size}" }
                val (sy, su, sv) = frame.planes
                if (su.pixelStride != 1 || sv.pixelStride != 1) {
                    // Android YUV_420_888 exposes chroma either planar or
                    // interleaved; libyuv's Android420ToI420 understands both.
                    LibYuv.android420ToI420(
                        sy.slice(), su.slice(), sv.slice(),
                        y, u, v,
                        width, height,
                        srcStrideY = sy.rowStride,
                        srcStrideU = su.rowStride,
                        srcStrideV = sv.rowStride,
                        srcPixelStrideUv = su.pixelStride,
                    )
                } else {
                    LibYuv.i420Copy(
                        sy.slice(), su.slice(), sv.slice(),
                        y, u, v,
                        width, height,
                        srcStrideY = sy.rowStride,
                        srcStrideU = su.rowStride,
                        srcStrideV = sv.rowStride,
                    )
                }
            }

            PixelFormat.NV12 -> {
                require(frame.planes.size >= 2) { "NV12 needs two planes, got ${frame.planes.size}" }
                val (sy, suv) = frame.planes
                LibYuv.nv12ToI420(
                    sy.slice(), suv.slice(),
                    y, u, v,
                    width, height,
                    srcStrideY = sy.rowStride,
                    srcStrideUv = suv.rowStride,
                )
            }

            PixelFormat.NV21 -> {
                require(frame.planes.size >= 2) { "NV21 needs two planes, got ${frame.planes.size}" }
                val (sy, svu) = frame.planes
                LibYuv.nv21ToI420(
                    sy.slice(), svu.slice(),
                    y, u, v,
                    width, height,
                    srcStrideY = sy.rowStride,
                    srcStrideVu = svu.rowStride,
                )
            }

            PixelFormat.YUY2 -> {
                val plane = frame.planes[0]
                LibYuv.yuy2ToI420(plane.slice(), y, u, v, width, height, srcStrideYuy2 = plane.rowStride)
            }

            PixelFormat.UYVY -> {
                val plane = frame.planes[0]
                LibYuv.uyvyToI420(plane.slice(), y, u, v, width, height, srcStrideUyvy = plane.rowStride)
            }

            PixelFormat.RGBA -> {
                // libyuv names its packed formats after the 32-bit word, so
                // ABGR writes R,G,B,A bytes on the little-endian targets this
                // library supports.
                val plane = frame.planes[0]
                LibYuv.abgrToI420(plane.slice(), y, u, v, width, height, srcStrideAbgr = plane.rowStride)
            }

            PixelFormat.BGRA -> {
                val plane = frame.planes[0]
                LibYuv.argbToI420(plane.slice(), y, u, v, width, height, srcStrideArgb = plane.rowStride)
            }

            PixelFormat.RGB24 -> {
                val plane = frame.planes[0]
                LibYuv.rgb24ToI420(plane.slice(), y, u, v, width, height, srcStrideRgb24 = plane.rowStride)
            }
        }

        return I420Frame(width, height, y, u, v, frame.timestampNanos)
    }

    /**
     * Converts [frame] to [pixelFormat], applying a horizontal mirror first and
     * the clockwise [rotationDegrees] second.
     */
    fun process(
        frame: SourceFrame,
        pixelFormat: PixelFormat,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        frameRate: Int = CameraFormat.DEFAULT_FRAME_RATE,
    ): CameraFrame = fromI420(toI420(frame), pixelFormat, rotationDegrees, mirrorHorizontally, frameRate)

    /** Applies mirroring/rotation to I420 and converts it to [pixelFormat]. */
    fun fromI420(
        source: I420Frame,
        pixelFormat: PixelFormat,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean,
        frameRate: Int = CameraFormat.DEFAULT_FRAME_RATE,
    ): CameraFrame {
        require(rotationDegrees % 90 == 0) { "rotation must be a multiple of 90: $rotationDegrees" }
        var width = source.width
        var height = source.height
        var y = source.y
        var u = source.u
        var v = source.v

        if (mirrorHorizontally) {
            val my = ByteArray(width * height)
            val mu = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
            val mv = ByteArray(mu.size)
            LibYuv.i420Mirror(y, u, v, my, mu, mv, width, height)
            y = my
            u = mu
            v = mv
        }

        val rotation = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Rotation.CLOCKWISE_90
            180 -> Rotation.ROTATE_180
            270 -> Rotation.CLOCKWISE_270
            else -> Rotation.NONE
        }
        if (rotation != Rotation.NONE) {
            val outWidth = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) height else width
            val outHeight = if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.CLOCKWISE_270) width else height
            val ry = ByteArray(outWidth * outHeight)
            val ru = ByteArray(((outWidth + 1) / 2) * ((outHeight + 1) / 2))
            val rv = ByteArray(ru.size)
            LibYuv.i420Rotate(y, u, v, ry, ru, rv, width, height, rotation)
            width = outWidth
            height = outHeight
            y = ry
            u = ru
            v = rv
        }

        val data = fromI420Data(y, u, v, width, height, pixelFormat)
        return CameraFrame(
            format = CameraFormat(width, height, pixelFormat, frameRate),
            data = data,
            timestampNanos = source.timestampNanos,
            rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
            mirrored = mirrorHorizontally,
        )
    }

    /** Converts a [CameraFrame] to I420, reading its packed layout. */
    fun toI420(frame: CameraFrame): I420Frame {
        val width = frame.width
        val height = frame.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaSize = width * height
        val data = frame.data
        val source = when (frame.pixelFormat) {
            PixelFormat.I420 -> SourceFrame(
                width, height, PixelFormat.I420,
                listOf(
                    SourcePlane(data, 0, width, 1, width, height),
                    SourcePlane(data, lumaSize, chromaWidth, 1, chromaWidth, chromaHeight),
                    SourcePlane(data, lumaSize + chromaWidth * chromaHeight, chromaWidth, 1, chromaWidth, chromaHeight),
                ),
                frame.timestampNanos,
            )

            PixelFormat.NV12, PixelFormat.NV21 -> {
                val uvStride = ((width + 1) / 2) * 2
                SourceFrame(
                    width, height, frame.pixelFormat,
                    listOf(
                        SourcePlane(data, 0, width, 1, width, height),
                        SourcePlane(data, lumaSize, uvStride, 2, chromaWidth, chromaHeight),
                    ),
                    frame.timestampNanos,
                )
            }

            PixelFormat.YUY2, PixelFormat.UYVY -> SourceFrame(
                width, height, frame.pixelFormat,
                listOf(SourcePlane(data, 0, width * 2, 2, width, height)),
                frame.timestampNanos,
            )

            PixelFormat.RGBA, PixelFormat.BGRA -> SourceFrame(
                width, height, frame.pixelFormat,
                listOf(SourcePlane(data, 0, width * 4, 4, width, height)),
                frame.timestampNanos,
            )

            PixelFormat.RGB24 -> SourceFrame(
                width, height, frame.pixelFormat,
                listOf(SourcePlane(data, 0, width * 3, 3, width, height)),
                frame.timestampNanos,
            )
        }
        return toI420(source)
    }

    private fun fromI420Data(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        width: Int,
        height: Int,
        pixelFormat: PixelFormat,
    ): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaSize = width * height
        return when (pixelFormat) {
            PixelFormat.I420 -> {
                val out = ByteArray(lumaSize + chromaWidth * chromaHeight * 2)
                y.copyInto(out, 0, 0, lumaSize)
                u.copyInto(out, lumaSize, 0, chromaWidth * chromaHeight)
                v.copyInto(out, lumaSize + chromaWidth * chromaHeight, 0, chromaWidth * chromaHeight)
                out
            }

            PixelFormat.NV12, PixelFormat.NV21 -> {
                val uvStride = chromaWidth * 2
                val out = ByteArray(lumaSize + uvStride * chromaHeight)
                val dstY = ByteArray(lumaSize)
                val dstUv = ByteArray(uvStride * chromaHeight)
                if (pixelFormat == PixelFormat.NV12) {
                    LibYuv.i420ToNv12(y, u, v, dstY, dstUv, width, height, dstStrideUv = uvStride)
                } else {
                    LibYuv.i420ToNv21(y, u, v, dstY, dstUv, width, height, dstStrideVu = uvStride)
                }
                dstY.copyInto(out, 0)
                dstUv.copyInto(out, lumaSize)
                out
            }

            PixelFormat.YUY2, PixelFormat.UYVY -> {
                val out = ByteArray(width * height * 2)
                if (pixelFormat == PixelFormat.YUY2) {
                    LibYuv.i420ToYuy2(y, u, v, out, width, height)
                } else {
                    LibYuv.i420ToUyvy(y, u, v, out, width, height)
                }
                out
            }

            PixelFormat.RGBA -> {
                val out = ByteArray(width * height * 4)
                LibYuv.i420ToAbgr(y, u, v, out, width, height)
                out
            }

            PixelFormat.BGRA -> {
                val out = ByteArray(width * height * 4)
                LibYuv.i420ToArgb(y, u, v, out, width, height)
                out
            }

            PixelFormat.RGB24 -> {
                val out = ByteArray(width * height * 3)
                LibYuv.i420ToRgb24(y, u, v, out, width, height)
                out
            }
        }
    }
}
