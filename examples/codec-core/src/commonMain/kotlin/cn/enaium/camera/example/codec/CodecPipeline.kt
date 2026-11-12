package cn.enaium.camera.example.codec

import cn.enaium.camera.I420Frame

/** Counters shown by the renderer and printed by the headless run. */
data class CodecStats(
    val codec: CodecKind = CodecKind.H264,
    val inputFrames: Long = 0,
    val encodedFrames: Long = 0,
    val encodedBytes: Long = 0,
    val decodedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val measuredFps: Double = 0.0,
) {
    val averageFrameBytes: Long get() = if (encodedFrames == 0L) 0 else encodedBytes / encodedFrames

    override fun toString(): String =
        "${codec.displayName}: ${width}x$height, input=$inputFrames encoded=$encodedFrames " +
            "decoded=$decodedFrames dropped=$droppedFrames, " +
            "${averageFrameBytes} B/frame, ${(measuredFps * 10).toInt() / 10.0} fps"
}

/**
 * Wraps an encoder/decoder pair and keeps the counters.
 *
 * One instance is created for the first camera frame because the backend may
 * negotiate a different resolution than the one requested. A resolution change
 * recreates the pair, which starts with a fresh key frame.
 */
class CodecPipeline(
    private val kind: CodecKind,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
) : AutoCloseable {
    private val encoder: VideoEncoder = encoderFor(kind, width, height, frameRate, bitrateKbps)
    private val decoder: VideoDecoder = decoderFor(kind)

    var inputFrames: Long = 0
        private set
    var encodedFrames: Long = 0
        private set
    var encodedBytes: Long = 0
        private set
    var decodedFrames: Long = 0
        private set
    var droppedFrames: Long = 0
        private set

    /** Encodes one camera frame and returns the picture(s) the decoder completed for it. */
    fun process(frame: I420Frame): List<I420Frame> {
        inputFrames++
        val encoded = encoder.encode(frame) ?: return emptyList()
        encodedFrames++
        encodedBytes += encoded.byteSize
        val decoded = decoder.decode(encoded)
        if (decoded.isEmpty()) droppedFrames++
        decodedFrames += decoded.size
        return decoded
    }

    /** Flushes the reorder buffers of both codecs after the last input frame. */
    fun flush(): List<I420Frame> {
        val remaining = ArrayList<I420Frame>()
        for (encoded in encoder.flush()) {
            encodedFrames++
            encodedBytes += encoded.byteSize
            remaining += decoder.decode(encoded)
        }
        remaining += decoder.flush()
        decodedFrames += remaining.size
        return remaining
    }

    fun stats(codec: CodecKind, measuredFps: Double): CodecStats = CodecStats(
        codec = codec,
        inputFrames = inputFrames,
        encodedFrames = encodedFrames,
        encodedBytes = encodedBytes,
        decodedFrames = decodedFrames,
        droppedFrames = droppedFrames,
        width = width,
        height = height,
        measuredFps = measuredFps,
    )

    override fun close() {
        runCatching { encoder.close() }
        runCatching { decoder.close() }
    }
}

/**
 * Lazily creates the [CodecPipeline] for the resolution the camera actually
 * delivered and recreates it if a backend changes resolution mid-stream.
 */
class PipelineController(
    private val kind: CodecKind,
    private val frameRate: Int,
    private val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
) : AutoCloseable {
    private var pipeline: CodecPipeline? = null

    val current: CodecPipeline? get() = pipeline

    fun process(frame: I420Frame): List<I420Frame> {
        val active = pipeline?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: create(frame.width, frame.height)
        return active.process(frame)
    }

    fun flush(): List<I420Frame> = pipeline?.flush() ?: emptyList()

    private fun create(width: Int, height: Int): CodecPipeline {
        pipeline?.close()
        return CodecPipeline(kind, width, height, frameRate, bitrateKbps).also { pipeline = it }
    }

    override fun close() {
        pipeline?.close()
        pipeline = null
    }
}
