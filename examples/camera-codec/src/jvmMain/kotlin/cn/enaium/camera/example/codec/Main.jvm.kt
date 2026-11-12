package cn.enaium.camera.example.codec

/** Parses `--frames N`, `--codec h264|hevc`, `--source camera|synthetic`, `--headless`, size and bitrate flags. */
private fun parseOptions(args: Array<String>): DemoOptions {
    var frames = 0
    var codec = CodecKind.H264
    var source = SourceKind.CAMERA
    var headless = false
    var width = 640
    var height = 480
    var frameRate = 30
    var bitrateKbps = DEFAULT_BITRATE_KBPS
    var quitAfterMillis = 0L
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        fun value(): String? = if (index + 1 < args.size) args[++index] else null
        when {
            arg == "--frames" -> frames = value()?.toIntOrNull() ?: 0
            arg.startsWith("--frames=") -> frames = arg.substringAfter('=').toIntOrNull() ?: 0
            arg == "--codec" -> codec = CodecKind.parse(value())
            arg.startsWith("--codec=") -> codec = CodecKind.parse(arg.substringAfter('='))
            arg == "--source" -> source = SourceKind.parse(value())
            arg.startsWith("--source=") -> source = SourceKind.parse(arg.substringAfter('='))
            arg == "--headless" -> headless = true
            arg == "--width" -> width = value()?.toIntOrNull() ?: width
            arg.startsWith("--width=") -> width = arg.substringAfter('=').toIntOrNull() ?: width
            arg == "--height" -> height = value()?.toIntOrNull() ?: height
            arg.startsWith("--height=") -> height = arg.substringAfter('=').toIntOrNull() ?: height
            arg == "--fps" -> frameRate = value()?.toIntOrNull() ?: frameRate
            arg.startsWith("--fps=") -> frameRate = arg.substringAfter('=').toIntOrNull() ?: frameRate
            arg == "--bitrate" -> bitrateKbps = value()?.toIntOrNull() ?: bitrateKbps
            arg.startsWith("--bitrate=") -> bitrateKbps = arg.substringAfter('=').toIntOrNull() ?: bitrateKbps
            arg == "--quit-after-ms" -> quitAfterMillis = value()?.toLongOrNull() ?: quitAfterMillis
            arg.startsWith("--quit-after-ms=") -> quitAfterMillis = arg.substringAfter('=').toLongOrNull() ?: quitAfterMillis
        }
        index++
    }
    return DemoOptions(frames, codec, source, headless, width, height, frameRate, bitrateKbps, quitAfterMillis)
}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    println("camera-kmp codec example: ${options.codec.displayName}, ${options.width}x${options.height}")
    CodecDemo.run(options)
}
