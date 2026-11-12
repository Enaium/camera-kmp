package cn.enaium.camera.example.codec

import cn.enaium.camera.I420Frame

/**
 * A deterministic colour-bar pattern with a moving square.
 *
 * It exercises the whole encode/decode path on machines without a camera (CI
 * runners, a terminal that has no camera permission) and gives the decoder a
 * picture whose colour changes are easy to eyeball.
 */
class TestPattern(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int = 30,
) {
    private var index = 0L

    init {
        require(width > 0 && height > 0)
        require(width % 2 == 0 && height % 2 == 0) { "test pattern needs even dimensions" }
    }

    fun next(timestampNanos: Long = index * (1_000_000_000L / frameRate)): I420Frame {
        val y = ByteArray(width * height)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)

        val bars = BARS.size
        val barWidth = width / bars
        val squareX = ((index * 6) % (width - SQUARE_SIZE)).toInt()
        val squareY = height / 2 - SQUARE_SIZE / 2

        for (row in 0 until height) {
            val rowOffset = row * width
            for (column in 0 until width) {
                val bar = (column / barWidth).coerceAtMost(bars - 1)
                val color = BARS[bar]
                val inSquare = column in squareX until (squareX + SQUARE_SIZE) &&
                    row in squareY until (squareY + SQUARE_SIZE)
                y[rowOffset + column] = (if (inSquare) 235 else color.y).toByte()
            }
        }

        for (row in 0 until chromaHeight) {
            val chromaOffset = row * chromaWidth
            for (column in 0 until chromaWidth) {
                val bar = ((column * 2) / barWidth).coerceAtMost(bars - 1)
                val color = BARS[bar]
                u[chromaOffset + column] = color.u.toByte()
                v[chromaOffset + column] = color.v.toByte()
            }
        }

        index++
        return I420Frame(width, height, y, u, v, timestampNanos)
    }

    private class Bar(val y: Int, val u: Int, val v: Int)

    private companion object {
        const val SQUARE_SIZE = 48
        val BARS = listOf(
            Bar(235, 128, 128), // white
            Bar(210, 16, 146), // yellow
            Bar(170, 166, 16), // cyan
            Bar(145, 54, 34), // green
            Bar(106, 202, 222), // magenta
            Bar(81, 90, 240), // red
            Bar(41, 240, 110), // blue
            Bar(16, 128, 128), // black
        )
    }
}
