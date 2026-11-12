@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.camera.example.codec.CodecDemo
import cn.enaium.camera.example.codec.CodecKind
import cn.enaium.camera.example.codec.DEFAULT_BITRATE_KBPS
import cn.enaium.camera.example.codec.DemoOptions
import cn.enaium.camera.example.codec.SourceKind
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Native entry point. The options come from environment variables so the same
 * binary works in a terminal and in a headless CI job:
 *
 * `CAMERA_CODEC_FRAMES`, `CAMERA_CODEC_CODEC` (`h264`/`hevc`),
 * `CAMERA_CODEC_SOURCE` (`camera`/`synthetic`), `CAMERA_CODEC_BITRATE`,
 * `CAMERA_CODEC_HEADLESS`.
 */
private fun nativeOptions(): DemoOptions {
    fun env(name: String): String? = getenv(name)?.toKString()
    return DemoOptions(
        frames = env("CAMERA_CODEC_FRAMES")?.toIntOrNull() ?: 0,
        codec = CodecKind.parse(env("CAMERA_CODEC_CODEC")),
        source = SourceKind.parse(env("CAMERA_CODEC_SOURCE")),
        headless = env("CAMERA_CODEC_HEADLESS") == "1",
        width = env("CAMERA_CODEC_WIDTH")?.toIntOrNull() ?: 640,
        height = env("CAMERA_CODEC_HEIGHT")?.toIntOrNull() ?: 480,
        frameRate = env("CAMERA_CODEC_FPS")?.toIntOrNull() ?: 30,
        bitrateKbps = env("CAMERA_CODEC_BITRATE")?.toIntOrNull() ?: DEFAULT_BITRATE_KBPS,
        quitAfterMillis = env("CAMERA_CODEC_QUIT_AFTER_MS")?.toLongOrNull() ?: 0,
    )
}

fun main() {
    val options = nativeOptions()
    println("camera-kmp codec example: ${options.codec.displayName}, ${options.width}x${options.height}")
    CodecDemo.run(options)
}
