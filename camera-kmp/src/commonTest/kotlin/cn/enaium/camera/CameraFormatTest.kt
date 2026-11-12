package cn.enaium.camera

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraFormatTest {

    @Test
    fun frameSizesMatchPlaneLayouts() {
        assertEquals(4 * 4 + 2 * 2 * 2, PixelFormat.I420.frameSizeBytes(4, 4))
        assertEquals(4 * 4 + 4 * 2, PixelFormat.NV12.frameSizeBytes(4, 4))
        assertEquals(4 * 4 + 4 * 2, PixelFormat.NV21.frameSizeBytes(4, 4))
        assertEquals(4 * 4 * 2, PixelFormat.YUY2.frameSizeBytes(4, 4))
        assertEquals(4 * 4 * 2, PixelFormat.UYVY.frameSizeBytes(4, 4))
        assertEquals(4 * 4 * 4, PixelFormat.RGBA.frameSizeBytes(4, 4))
        assertEquals(4 * 4 * 4, PixelFormat.BGRA.frameSizeBytes(4, 4))
        assertEquals(4 * 4 * 3, PixelFormat.RGB24.frameSizeBytes(4, 4))
        // Odd widths pad the interleaved chroma row to an even byte count.
        assertEquals(3 * 3 + 4 * 2, PixelFormat.NV12.frameSizeBytes(3, 3))
    }

    @Test
    fun bestFormatPrefersTheRequestedShape() {
        val camera = CameraInfo(
            id = "test",
            name = "Test camera",
            lensFacing = CameraLensFacing.FRONT,
            supportedFormats = listOf(
                CameraFormat(320, 240, PixelFormat.NV12, 30),
                CameraFormat(640, 480, PixelFormat.NV12, 30),
                CameraFormat(1280, 720, PixelFormat.NV12, 30),
                CameraFormat(1920, 1080, PixelFormat.NV12, 60),
            ),
        )
        assertEquals(CameraFormat(640, 480, PixelFormat.NV12, 30), camera.bestFormat(640, 480))
        assertEquals(CameraFormat(1280, 720, PixelFormat.NV12, 30), camera.bestFormat(900, 500))
        assertEquals(CameraFormat(1920, 1080, PixelFormat.NV12, 60), camera.bestFormat(1920, 1080, frameRate = 60))
        assertEquals(listOf(1920, 1280, 640, 320), camera.resolutions.map { it.width })
    }

    @Test
    fun rotationSwapsDimensions() {
        val config = CameraConfig(format = CameraFormat(640, 480), rotationDegrees = 90)
        assertEquals(480, config.outputFormat.width)
        assertEquals(640, config.outputFormat.height)
    }
}

class FrameProcessorTest {

    private fun source(width: Int = 4, height: Int = 2): I420Frame {
        val y = ByteArray(width * height) { (32 + it * 8).toByte() }
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2)) { 100.toByte() }
        val v = ByteArray(u.size) { 150.toByte() }
        return I420Frame(width, height, y, u, v)
    }

    @Test
    fun i420RoundTripIsLossless() {
        val source = source()
        val frame = source.toCameraFrame(PixelFormat.I420)
        assertEquals(PixelFormat.I420, frame.pixelFormat)
        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertTrue(source.y.contentEquals(frame.toI420().y))
        assertTrue(source.u.contentEquals(frame.toI420().u))
        assertTrue(source.v.contentEquals(frame.toI420().v))
    }

    @Test
    fun everyFormatCanBeReadBack() {
        val source = source()
        for (format in PixelFormat.entries) {
            val cameraFrame = source.toCameraFrame(format)
            assertEquals(format, cameraFrame.pixelFormat, "format $format")
            assertEquals(format.frameSizeBytes(4, 2), cameraFrame.data.size, "size of $format")
            val back = cameraFrame.toI420()
            assertEquals(4, back.width)
            assertEquals(2, back.height)
            for (index in source.y.indices) {
                assertTrue(
                    abs(source.y[index].toInt() - back.y[index].toInt()) <= 8,
                    "$format changed luma at $index: ${source.y[index]} -> ${back.y[index]}",
                )
            }
            for (index in source.u.indices) {
                assertTrue(
                    abs((source.u[index].toInt() and 0xFF) - (back.u[index].toInt() and 0xFF)) <= 16,
                    "$format changed U at $index: ${source.u[index]} -> ${back.u[index]}",
                )
                assertTrue(
                    abs((source.v[index].toInt() and 0xFF) - (back.v[index].toInt() and 0xFF)) <= 16,
                    "$format changed V at $index: ${source.v[index]} -> ${back.v[index]}",
                )
            }
        }
    }

    @Test
    fun clockwiseRotationMovesPixels() {
        val source = source()
        val frame = source.toCameraFrame(PixelFormat.I420, rotationDegrees = 90)
        assertEquals(2, frame.width)
        assertEquals(4, frame.height)
        val y = frame.toI420().y
        val expected = byteArrayOf(64, 32, 72, 40, 80, 48, 88, 56)
        assertTrue(expected.contentEquals(y), "rotated luma was ${y.toList()}")
        assertEquals(90, frame.rotationDegrees)
    }

    @Test
    fun mirrorFlipsRows() {
        val frame = source().toCameraFrame(PixelFormat.I420, mirrorHorizontally = true)
        val y = frame.toI420().y
        assertTrue(byteArrayOf(56, 48, 40, 32, 88, 80, 72, 64).contentEquals(y), "mirrored luma was ${y.toList()}")
        assertTrue(frame.mirrored)
    }

    @Test
    fun mirrorThenRotateMatchesTheDocumentedOrder() {
        // mirror: 56 48 40 32 / 88 80 72 64, then 90 clockwise: 88 56 / 80 48 / 72 40 / 64 32
        val frame = source().toCameraFrame(
            PixelFormat.I420,
            rotationDegrees = 90,
            mirrorHorizontally = true,
        )
        val y = frame.toI420().y
        assertTrue(byteArrayOf(88, 56, 80, 48, 72, 40, 64, 32).contentEquals(y), "luma was ${y.toList()}")
    }

    @Test
    fun packedRgbFormatsUseTheirDocumentedByteOrder() {
        val yellow = I420Frame(2, 2, ByteArray(4) { 210.toByte() }, ByteArray(1) { 16.toByte() }, ByteArray(1) { 146.toByte() })
        val rgba = yellow.toCameraFrame(PixelFormat.RGBA)
        assertTrue(rgba.data[0].toInt() and 0xFF > 240, "RGBA red=${rgba.data[0]}")
        assertTrue(rgba.data[1].toInt() and 0xFF > 240, "RGBA green=${rgba.data[1]}")
        assertTrue(rgba.data[2].toInt() and 0xFF < 20, "RGBA blue=${rgba.data[2]}")
        assertEquals(255.toByte(), rgba.data[3])

        val bgra = yellow.toCameraFrame(PixelFormat.BGRA)
        assertTrue(bgra.data[0].toInt() and 0xFF < 20, "BGRA blue=${bgra.data[0]}")
        assertTrue(bgra.data[1].toInt() and 0xFF > 240, "BGRA green=${bgra.data[1]}")
        assertTrue(bgra.data[2].toInt() and 0xFF > 240, "BGRA red=${bgra.data[2]}")
        assertEquals(255.toByte(), bgra.data[3])

        val back = rgba.toI420()
        assertTrue(abs((back.u[0].toInt() and 0xFF) - 16) <= 2, "RGBA round trip U=${back.u[0].toInt() and 0xFF}")
        assertTrue(abs((back.v[0].toInt() and 0xFF) - 146) <= 2, "RGBA round trip V=${back.v[0].toInt() and 0xFF}")
    }

    @Test
    fun nv12AndNv21KeepTheirLayout() {
        val source = source()
        val nv12 = source.toCameraFrame(PixelFormat.NV12)
        assertEquals(4 * 2 + 4, nv12.data.size)
        val nv21 = source.toCameraFrame(PixelFormat.NV21)
        assertEquals(4 * 2 + 4, nv21.data.size)
        // The first chroma sample of NV12 is U and of NV21 is V.
        assertEquals(100.toByte(), nv12.data[8])
        assertEquals(150.toByte(), nv12.data[9])
        assertEquals(150.toByte(), nv21.data[8])
        assertEquals(100.toByte(), nv21.data[9])
    }
}
