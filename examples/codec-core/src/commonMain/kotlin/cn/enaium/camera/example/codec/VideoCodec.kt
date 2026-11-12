package cn.enaium.camera.example.codec

import cn.enaium.camera.CameraFormat
import cn.enaium.camera.I420Frame

/** The two HEVC/H.264 codecs the example can run. */
enum class CodecKind(val displayName: String, val shortName: String) {
    H264("H.264 / AVC", "h264"),
    H265("H.265 / HEVC", "hevc"),
    ;

    companion object {
        fun parse(value: String?): CodecKind = when (value?.trim()?.lowercase()) {
            null, "", "h264", "avc", "x264" -> H264
            "h265", "hevc", "x265" -> H265
            else -> error("unknown codec '$value': use h264 or hevc")
        }
    }
}

/** Where the example gets its frames. */
enum class SourceKind {
    CAMERA,
    SYNTHETIC,
    ;

    companion object {
        fun parse(value: String?): SourceKind = when (value?.trim()?.lowercase()) {
            null, "", "camera" -> CAMERA
            "synthetic", "test", "pattern" -> SYNTHETIC
            else -> error("unknown source '$value': use camera or synthetic")
        }
    }
}

/** One encoded access unit in Annex-B form. */
class EncodedFrame(
    val data: ByteArray,
    val keyFrame: Boolean,
    val ptsNanos: Long,
) {
    val byteSize: Int get() = data.size
}

/** Encodes I420 pictures into H.264 or HEVC Annex-B access units. */
interface VideoEncoder : AutoCloseable {
    val kind: CodecKind
    val width: Int
    val height: Int
    val frameRate: Int

    /** Encodes one picture; returns `null` while the encoder reorders frames. */
    fun encode(frame: I420Frame): EncodedFrame?

    /** Drains pictures still held for reordering. */
    fun flush(): List<EncodedFrame>
}

/** Decodes Annex-B access units into I420 pictures. */
interface VideoDecoder : AutoCloseable {
    val kind: CodecKind

    fun decode(frame: EncodedFrame): List<I420Frame>

    fun flush(): List<I420Frame>
}

/** Default ABR target used by the interactive example and the browser build. */
const val DEFAULT_BITRATE_KBPS: Int = 2_000

internal fun encoderFor(
    kind: CodecKind,
    width: Int,
    height: Int,
    frameRate: Int,
    bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
): VideoEncoder = when (kind) {
    CodecKind.H264 -> X264VideoEncoder(width, height, frameRate, bitrateKbps)
    CodecKind.H265 -> X265VideoEncoder(width, height, frameRate, bitrateKbps)
}

internal fun decoderFor(kind: CodecKind): VideoDecoder = when (kind) {
    CodecKind.H264 -> OpenH264VideoDecoder()
    CodecKind.H265 -> Libde265VideoDecoder()
}

internal fun requireEven(width: Int, height: Int) {
    require(width > 0 && height > 0) { "size must be positive, got ${width}x$height" }
    require(width % 2 == 0 && height % 2 == 0) {
        "YUV 4:2:0 encoders need even dimensions, got ${width}x$height"
    }
}

/** The frame format used for the direct view of the example. */
internal val DEMO_FORMAT: CameraFormat = CameraFormat(640, 480)
