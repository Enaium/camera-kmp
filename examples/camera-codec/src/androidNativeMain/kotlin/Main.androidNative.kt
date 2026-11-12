@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import cn.enaium.camera.example.codec.CodecDemo
import cn.enaium.camera.example.codec.CodecKind
import cn.enaium.camera.example.codec.DEFAULT_BITRATE_KBPS
import cn.enaium.camera.example.codec.DemoOptions
import cn.enaium.camera.example.codec.SourceKind
import cn.enaium.sdl.SDL
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Android entry point. The SDL activity loads `libmain.so` and calls the
 * exported `SDL_main` symbol on its own thread. The activity has already asked
 * for the camera permission before SDL starts.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    fun env(name: String): String? = getenv(name)?.toKString()
    val options = DemoOptions(
        frames = env("CAMERA_CODEC_FRAMES")?.toIntOrNull() ?: 0,
        codec = CodecKind.parse(env("CAMERA_CODEC_CODEC")),
        source = SourceKind.parse(env("CAMERA_CODEC_SOURCE")),
        headless = false,
        width = env("CAMERA_CODEC_WIDTH")?.toIntOrNull() ?: 640,
        height = env("CAMERA_CODEC_HEIGHT")?.toIntOrNull() ?: 480,
        frameRate = env("CAMERA_CODEC_FPS")?.toIntOrNull() ?: 30,
        bitrateKbps = env("CAMERA_CODEC_BITRATE")?.toIntOrNull() ?: DEFAULT_BITRATE_KBPS,
        quitAfterMillis = 0,
    )
    val uiScale = env("CAMERA_CODEC_DPI_SCALE")?.toFloatOrNull() ?: 1f
    println("camera-kmp codec example: ${options.codec.displayName}, ${options.width}x${options.height}")
    CodecDemo.run(options, uiScale)
    SDL.quit()
    return 0
}
