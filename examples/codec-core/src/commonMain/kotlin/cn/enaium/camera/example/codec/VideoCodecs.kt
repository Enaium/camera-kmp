package cn.enaium.camera.example.codec

import cn.enaium.camera.I420Frame
import cn.enaium.openh264.OpenH264Decoder
import cn.enaium.libde265.Libde265Decoder
import cn.enaium.x264.X264Encoder
import cn.enaium.x264.X264LogLevel
import cn.enaium.x264.X264Param
import cn.enaium.x264.X264Picture
import cn.enaium.x264.X264Preset
import cn.enaium.x264.X264Tune
import cn.enaium.x265.X265Encoder
import cn.enaium.x265.X265LogLevel
import cn.enaium.x265.X265Param
import cn.enaium.x265.X265Picture
import cn.enaium.x265.X265Preset
import cn.enaium.x265.X265Tune

/**
 * x264 in zero-latency mode: every input picture produces at most one access
 * unit immediately, which is what a live camera loop needs. Repeated SPS/PPS
 * makes every key frame a join point for the decoder.
 */
internal class X264VideoEncoder(
    override val width: Int,
    override val height: Int,
    override val frameRate: Int,
    bitrateKbps: Int,
) : VideoEncoder {
    override val kind: CodecKind = CodecKind.H264

    private val encoder: X264Encoder

    init {
        requireEven(width, height)
        val parameters = X264Param.create(width, height, preset = X264Preset.ULTRAFAST)
            .tune(X264Tune.ZERO_LATENCY)
            .frameRate(frameRate)
            .bitrate(bitrateKbps)
            .set("keyint", frameRate)
            .set("repeat-headers", true)
            .set("bframes", 0)
        parameters.logLevel = X264LogLevel.ERROR
        encoder = X264Encoder.open(parameters)
        parameters.close()
    }

    override fun encode(frame: I420Frame): EncodedFrame? {
        require(frame.width == width && frame.height == height) {
            "x264 encoder is $width x $height, got ${frame.width} x ${frame.height}"
        }
        val output = encoder.encode(
            X264Picture.i420(frame.y, frame.u, frame.v, width, height, frame.timestampNanos),
        ) ?: return null
        return EncodedFrame(output.toAnnexB(), output.isKeyFrame, output.pts)
    }

    override fun flush(): List<EncodedFrame> = encoder.flush().map {
        EncodedFrame(it.toAnnexB(), it.isKeyFrame, it.pts)
    }

    override fun close() = encoder.close()
}

/** x265 in zero-latency mode, the HEVC counterpart of [X264VideoEncoder]. */
internal class X265VideoEncoder(
    override val width: Int,
    override val height: Int,
    override val frameRate: Int,
    bitrateKbps: Int,
) : VideoEncoder {
    override val kind: CodecKind = CodecKind.H265

    private val encoder: X265Encoder

    init {
        requireEven(width, height)
        val parameters = X265Param.create(width, height, preset = X265Preset.ULTRAFAST)
            .tune(X265Tune.ZERO_LATENCY)
            .frameRate(frameRate)
            .bitrate(bitrateKbps)
            .set("keyint", frameRate)
            .set("repeat-headers", true)
            .set("bframes", 0)
        parameters.logLevel = X265LogLevel.ERROR
        encoder = X265Encoder.open(parameters)
        parameters.close()
    }

    override fun encode(frame: I420Frame): EncodedFrame? {
        require(frame.width == width && frame.height == height) {
            "x265 encoder is $width x $height, got ${frame.width} x ${frame.height}"
        }
        val output = encoder.encode(
            X265Picture.i420(frame.y, frame.u, frame.v, width, height, frame.timestampNanos),
        ) ?: return null
        return EncodedFrame(output.toAnnexB(), output.isKeyFrame, output.pts)
    }

    override fun flush(): List<EncodedFrame> = encoder.flush().map {
        EncodedFrame(it.toAnnexB(), it.isKeyFrame, it.pts)
    }

    override fun close() = encoder.close()
}

internal class OpenH264VideoDecoder : VideoDecoder {
    override val kind: CodecKind = CodecKind.H264

    private val decoder = OpenH264Decoder.create()

    override fun decode(frame: EncodedFrame): List<I420Frame> {
        val decoded = decoder.decode(frame.data, frame.ptsNanos) ?: return emptyList()
        return listOf(
            I420Frame(
                decoded.width,
                decoded.height,
                decoded.y,
                decoded.u,
                decoded.v,
                decoded.pts,
            ),
        )
    }

    override fun flush(): List<I420Frame> = decoder.flush().map {
        I420Frame(it.width, it.height, it.y, it.u, it.v, it.pts)
    }

    override fun close() = decoder.close()
}

internal class Libde265VideoDecoder : VideoDecoder {
    override val kind: CodecKind = CodecKind.H265

    // Leave libde265 on the calling thread. Its web runtime traps if
    // decoderSetThreads() is called, and 640x480 decodes fast enough without
    // a worker pool.
    private val decoder = Libde265Decoder.create()

    override fun decode(frame: EncodedFrame): List<I420Frame> = decoder
        .decode(frame.data, frame.ptsNanos)
        .map { decoded ->
            I420Frame(
                decoded.width,
                decoded.height,
                decoded.y,
                decoded.u,
                decoded.v,
                decoded.pts,
            )
        }

    override fun flush(): List<I420Frame> = decoder.flush().map {
        I420Frame(it.width, it.height, it.y, it.u, it.v, it.pts)
    }

    override fun close() = decoder.close()
}
