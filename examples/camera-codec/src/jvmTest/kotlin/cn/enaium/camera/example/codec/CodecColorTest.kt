package cn.enaium.camera.example.codec

import cn.enaium.camera.I420Frame
import cn.enaium.libyuv.LibYuv
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CodecColorTest {
    @Test
    fun yellowSurvivesBothCodecRoundTrips() {
        val width = 64
        val height = 64
        val luma = ByteArray(width * height) { 210.toByte() }
        val chroma = ByteArray((width / 2) * (height / 2))
        val u = ByteArray(chroma.size) { 16.toByte() }
        val v = ByteArray(chroma.size) { 146.toByte() }

        for (kind in CodecKind.entries) {
            val pipeline = CodecPipeline(kind, width, height, 30)
            try {
                val decoded = ArrayList<I420Frame>()
                for (index in 0 until 10) {
                    decoded += pipeline.process(I420Frame(width, height, luma, u, v, index.toLong()))
                }
                decoded += pipeline.flush()
                assertTrue(decoded.isNotEmpty(), "$kind produced no decoded picture")

                val picture = decoded.last()
                val rgba = ByteArray(width * height * 4)
                LibYuv.i420ToAbgr(picture.y, picture.u, picture.v, rgba, width, height)
                val red = rgba[0].toInt() and 0xFF
                val green = rgba[1].toInt() and 0xFF
                val blue = rgba[2].toInt() and 0xFF
                val alpha = rgba[3].toInt() and 0xFF
                assertTrue(red > 220, "$kind yellow red=$red")
                assertTrue(green > 220, "$kind yellow green=$green")
                assertTrue(blue < 40, "$kind yellow blue=$blue (yellow turned pink)")
                assertTrue(alpha == 255, "$kind alpha=$alpha")
            } finally {
                pipeline.close()
            }
        }
    }
}
