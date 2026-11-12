package cn.enaium.camera.example.codec

import cn.enaium.camera.CameraLensFacing
import cn.enaium.camera.CameraResolution
import cn.enaium.camera.I420Frame
import cn.enaium.camera.cameraSystem
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLEventType
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.TimeSource

/** Command line / environment options of the example. */
data class DemoOptions(
    val frames: Int = 0,
    val codec: CodecKind = CodecKind.H264,
    val source: SourceKind = SourceKind.CAMERA,
    val headless: Boolean = false,
    val width: Int = 640,
    val height: Int = 480,
    val frameRate: Int = 30,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    /** Closes the window after this many milliseconds; 0 keeps it open. */
    val quitAfterMillis: Long = 0,
) {
    init {
        require(width > 0 && height > 0) { "size must be positive" }
        require(frameRate > 0) { "frame rate must be positive" }
        require(bitrateKbps > 0) { "bitrate must be positive" }
    }
}

/**
 * Two live views: the camera picture and the same picture after a full
 * encode/decode round trip.
 *
 * `--headless` runs the pipeline without a window, which is what CI executes;
 * the camera is used when it delivers a frame within a few seconds, otherwise
 * the deterministic [TestPattern] takes over.
 */
object CodecDemo {

    fun run(options: DemoOptions, uiScale: Float = 1f) {
        if (options.headless) {
            runHeadless(options)
        } else {
            runWindowed(options, uiScale)
        }
    }

    // ==================== headless ====================

    private fun runHeadless(options: DemoOptions) {
        val limit = if (options.frames <= 0) 60 else options.frames
        val pipeline = PipelineController(options.codec, options.frameRate, options.bitrateKbps)
        val started = TimeSource.Monotonic.markNow()
        val decoded = ArrayList<I420Frame>()
        var input = 0
        var sourceName = "synthetic"
        var sourceWidth = options.width
        var sourceHeight = options.height

        try {
            runBlocking {
                val channel = Channel<I420Frame>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                val camera = CameraSource(options.width, options.height, options.frameRate)
                var usingCamera = false

                if (options.source == SourceKind.CAMERA) {
                    usingCamera = camera.start(
                        onFrame = { channel.trySend(it) },
                        onError = { println("camera error: $it") },
                        onFailure = { println("camera unavailable: ${it.message ?: it}") },
                    )
                    if (usingCamera) {
                        val first = withTimeoutOrNull(3_000) { channel.receive() }
                        if (first == null) {
                            println("camera produced no frame (permission or busy device); using the test pattern")
                            camera.close()
                            usingCamera = false
                        } else {
                            input++
                            sourceName = camera.cameraName ?: "camera"
                            sourceWidth = first.width
                            sourceHeight = first.height
                            decoded += pipeline.process(first)
                        }
                    }
                }

                if (usingCamera) {
                    while (input < limit) {
                        val frame = withTimeoutOrNull(3_000) { channel.receive() } ?: break
                        input++
                        decoded += pipeline.process(frame)
                    }
                } else {
                    val pattern = TestPattern(options.width, options.height, options.frameRate)
                    while (input < limit) {
                        val frame = pattern.next()
                        input++
                        decoded += pipeline.process(frame)
                    }
                }
                camera.close()
                decoded += pipeline.flush()
                channel.close()
            }

            val elapsed = started.elapsedNow().inWholeMilliseconds / 1000.0
            val current = pipeline.current
            val stats = current?.stats(options.codec, if (elapsed > 0) input / elapsed else 0.0)
                ?: CodecStats(codec = options.codec)
            println("source=$sourceName")
            println("$stats")
            check(input > 0) { "the example produced no input frames" }
            check(current != null && current.encodedFrames > 0) { "no frame was encoded" }
            check(decoded.isNotEmpty()) { "the decoder produced no picture" }
            val first = decoded.first()
            check(first.width == sourceWidth && first.height == sourceHeight) {
                "decoded picture is ${first.width}x${first.height}, expected ${sourceWidth}x$sourceHeight"
            }
            println("verification: ${decoded.size} decoded pictures, ${decoded.sumOf { it.toByteArray().size }} decoded bytes")
        } finally {
            pipeline.close()
        }
    }

    // ==================== windowed ====================

    private data class WindowSettings(
        val codec: CodecKind,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrateKbps: Int,
        val source: SourceKind,
    )

    private fun runWindowed(options: DemoOptions, uiScale: Float) {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                error("SDL_Init failed: ${SDL.error()}")
            }
        }

        val resolutions = resolutionChoices(options)
        val initial = WindowSettings(
            options.codec,
            options.width,
            options.height,
            options.frameRate,
            options.bitrateKbps,
            options.source,
        )
        val window = SDL.createWindow(
            title = "camera-kmp codec loop",
            width = 1280,
            height = 800,
            flags = cn.enaium.sdl.SDLWindowFlags.RESIZABLE or cn.enaium.sdl.SDLWindowFlags.HIGH_PIXEL_DENSITY,
        )
        // The software renderer keeps SDL_RenderPresent from waiting on a
        // Metal drawable: when the window is occluded or being closed, the
        // accelerated renderer can block inside present() and never return to
        // the event loop, which looks like a frozen window. ImGui and the two
        // video textures are cheap enough for the software path.
        val renderer = runCatching { SDL.createRenderer(window, name = "software") }
            .getOrElse { SDL.createRenderer(window) }
        println("video driver=${SDL.getCurrentVideoDriver()}, renderer=${renderer.name}")

        val imguiContext = ImGui.createContext()
        val imgui = ImGuiSdlBackend(window)
        val imguiRenderer = ImGuiSdlRendererBackend(renderer)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                println("pipeline coroutine failed: ${error.message}")
                error.printStackTrace()
            },
        )
        val settings = MutableStateFlow(initial)
        val direct = MutableStateFlow<I420Frame?>(null)
        val decoded = MutableStateFlow<I420Frame?>(null)
        val stats = MutableStateFlow(
            CodecStats(codec = initial.codec, width = initial.width, height = initial.height),
        )
        val errorText = MutableStateFlow<String?>(null)
        val ui = UiState(resolutions, initial)
        val windowStarted = TimeSource.Monotonic.markNow()
        var sessionJob: kotlinx.coroutines.Job? = null
        var left: Panel? = null
        var right: Panel? = null
        var running = true
        var rendered = 0
        val limit = if (options.frames <= 0) Int.MAX_VALUE else options.frames
        val interfaceScale = maxOf(uiScale, 0.5f)

        try {
            imgui.init()
            // Match the desktop ImGui/SDL setup: the atlas is rasterized at
            // the framebuffer density, while [interfaceScale] carries the
            // Android display density because SDL reports that window in
            // physical pixels.
            val fontDensity = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
            val fonts = ImGui.getIO().fonts
            fonts.addFontDefault(
                ImFontConfig(
                    sizePixels = 13f * fontDensity * interfaceScale,
                    rasterizerDensity = fontDensity,
                ),
            )
            check(fonts.build()) { "font atlas build failed" }
            val atlas = fonts.getTexDataAsRGBA32()
            fonts.setTexID(imguiRenderer.uploadFontTexture(atlas.pixels, atlas.width, atlas.height))

            // One session at a time: a settings change cancels the previous
            // camera and pipeline (collectLatest) and starts the next one on
            // the same worker, so the render loop never blocks on camera
            // start/stop or encoder reconfiguration.
            sessionJob = scope.launch {
                settings.collectLatest { current ->
                    direct.value = null
                    decoded.value = null
                    errorText.value = null
                    stats.value = CodecStats(
                        codec = current.codec,
                        width = current.width,
                        height = current.height,
                    )
                    runSession(current, direct, decoded, stats, errorText)
                }
            }

            while (running && rendered < limit) {
                if (options.quitAfterMillis > 0 &&
                    windowStarted.elapsedNow().inWholeMilliseconds >= options.quitAfterMillis
                ) {
                    running = false
                    continue
                }
                while (true) {
                    val event = SDL.pollEvent() ?: break
                    when (event) {
                        is SDLEvent.Quit -> running = false
                        is SDLEvent.Window -> {
                            if (event.type == SDLWindowEventType.CLOSE_REQUESTED) {
                                running = false
                            } else {
                                imgui.processEvent(event)
                            }
                        }

                        is SDLEvent.TouchFinger -> {
                            // SDL3 may deliver raw finger events on touch
                            // platforms; the ImGui SDL backend consumes the
                            // desktop mouse queue, so translate them here.
                            val size = window.size
                            val x = event.x * size.x
                            val y = event.y * size.y
                            when (event.type) {
                                SDLEventType.FINGER_DOWN -> {
                                    imgui.processEvent(SDLEvent.MouseMotion(event.timestamp, 0, x, y, 0f, 0f))
                                    imgui.processEvent(SDLEvent.MouseButton(event.timestamp, 0, true, 1, 1, x, y))
                                }

                                SDLEventType.FINGER_MOTION ->
                                    imgui.processEvent(SDLEvent.MouseMotion(event.timestamp, 0, x, y, 0f, 0f))

                                SDLEventType.FINGER_UP -> {
                                    imgui.processEvent(SDLEvent.MouseMotion(event.timestamp, 0, x, y, 0f, 0f))
                                    imgui.processEvent(SDLEvent.MouseButton(event.timestamp, 0, false, 1, 1, x, y))
                                }

                                else -> Unit
                            }
                        }

                        else -> imgui.processEvent(event)
                    }
                }

                direct.value?.let { frame ->
                    val panel = left?.takeIf { it.matches(frame) } ?: Panel(renderer, imguiRenderer, frame).also {
                        left?.close()
                        left = it
                    }
                    panel.update(frame)
                }
                decoded.value?.let { frame ->
                    val panel = right?.takeIf { it.matches(frame) } ?: Panel(renderer, imguiRenderer, frame).also {
                        right?.close()
                        right = it
                    }
                    panel.update(frame)
                }

                imgui.newFrame()
                drawUi(ui, settings, left, right, stats.value, errorText.value, interfaceScale)
                ImGui.render()

                renderer.drawColor = SDLColor(18, 18, 24, 255)
                renderer.clear()
                imguiRenderer.renderDrawData(ImGui.getDrawData())
                renderer.present()

                rendered++
                if (rendered % 120 == 0) {
                    println("rendered $rendered frames, ${stats.value}")
                }
                // Present at the capture rate instead of spinning at ~1000 Hz:
                // the Metal renderer can run out of drawables (and block in
                // present()) long before the event loop gets another turn.
                SDL.delay((1000 / settings.value.frameRate).coerceAtLeast(1))
            }
            println("stopped after $rendered rendered frames, ${stats.value}")
        } finally {
            scope.cancel()
            runBlocking { sessionJob?.join() }
            left?.close()
            right?.close()
            imguiRenderer.close()
            ImGui.destroyContext(imguiContext)
            renderer.close()
            window.close()
        }
    }

    private class UiState(
        val resolutions: List<CameraResolution>,
        initial: WindowSettings,
    ) {
        val resolutionLabels: Array<String> = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
        val resolutionIndex = IntArray(1) {
            resolutions.indexOfFirst { it.width == initial.width && it.height == initial.height }
                .coerceAtLeast(0)
        }
        val codecLabels: Array<String> = CodecKind.entries.map { it.displayName }.toTypedArray()
        val codecIndex = IntArray(1) { CodecKind.entries.indexOf(initial.codec).coerceAtLeast(0) }
        val sourceLabels: Array<String> = arrayOf("Camera", "Synthetic test pattern")
        val sourceIndex = IntArray(1) { if (initial.source == SourceKind.CAMERA) 0 else 1 }
        val bitrate = IntArray(1) { initial.bitrateKbps }
        val frameRate = IntArray(1) { initial.frameRate }
    }

    private fun drawUi(
        ui: UiState,
        settings: MutableStateFlow<WindowSettings>,
        left: Panel?,
        right: Panel?,
        stats: CodecStats,
        error: String?,
        scale: Float,
    ) {
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(ImGui.getIO().displaySize, ImGuiCond.ALWAYS)
        val flags = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_SAVED_SETTINGS

        val open = ImGui.begin("##camera-kmp", flags = flags)
        if (open) {
            val available = ImGui.getContentRegionAvail()
            val controlsWidth = min(290f * scale, available.x * 0.45f)
            if (controlsWidth > 1f) {
                if (ImGui.beginChild("##controls", ImVec2(controlsWidth, 0f), ImGuiChildFlags.BORDERS)) {
                    ImGui.text("camera-kmp codec")
                    ImGui.separatorText("Capture")

                    ImGui.setNextItemWidth(190f * scale)
                    if (ImGui.combo("Resolution", ui.resolutionIndex, ui.resolutionLabels)) {
                        val picked = ui.resolutions.getOrNull(ui.resolutionIndex[0])
                        if (picked != null) {
                            settings.value = settings.value.copy(width = picked.width, height = picked.height)
                            println("resolution -> ${picked.width}x${picked.height}")
                        }
                    }

                    ImGui.setNextItemWidth(190f * scale)
                    if (ImGui.combo("Source", ui.sourceIndex, ui.sourceLabels)) {
                        val picked = SourceKind.entries.getOrNull(ui.sourceIndex[0]) ?: SourceKind.CAMERA
                        settings.value = settings.value.copy(source = picked)
                        println("source -> ${picked.name.lowercase()}")
                    }

                    ImGui.separatorText("Encoder")
                    ImGui.setNextItemWidth(190f * scale)
                    if (ImGui.combo("Codec", ui.codecIndex, ui.codecLabels)) {
                        val picked = CodecKind.entries.getOrNull(ui.codecIndex[0]) ?: CodecKind.H264
                        settings.value = settings.value.copy(codec = picked)
                        println("codec -> ${picked.displayName}")
                    }

                    ImGui.setNextItemWidth(190f * scale)
                    ImGui.sliderInt("Bitrate (kbps)", ui.bitrate, 200, 12_000, "%d kbps")
                    if (ImGui.isItemDeactivatedAfterEdit() &&
                        ui.bitrate[0] != settings.value.bitrateKbps
                    ) {
                        settings.value = settings.value.copy(bitrateKbps = ui.bitrate[0])
                    }

                    ImGui.setNextItemWidth(190f * scale)
                    ImGui.sliderInt("Frame rate", ui.frameRate, 5, 60, "%d fps")
                    if (ImGui.isItemDeactivatedAfterEdit() &&
                        ui.frameRate[0] != settings.value.frameRate
                    ) {
                        settings.value = settings.value.copy(frameRate = ui.frameRate[0])
                    }
                    ImGui.textDisabled("Apply on release")

                    ImGui.separatorText("Statistics")
                    ImGui.text("Input ${stats.inputFrames}   encoded ${stats.encodedFrames}")
                    ImGui.text("Decoded ${stats.decodedFrames}   dropped ${stats.droppedFrames}")
                    ImGui.text("${stats.averageFrameBytes} B/frame")
                    ImGui.text("${(stats.measuredFps * 10).toInt() / 10.0} fps")
                    ImGui.textDisabled("${settings.value.codec.displayName} @ ${settings.value.bitrateKbps} kbps")
                    if (!error.isNullOrBlank()) {
                        ImGui.separator()
                        ImGui.textColored(ImVec4(1f, 0.35f, 0.35f, 1f), error)
                    }
                }
                ImGui.endChild()
                ImGui.sameLine()
            }

            if (ImGui.beginChild("##views", ImVec2(0f, 0f))) {
                val views = ImGui.getContentRegionAvail()
                val gap = 8f * scale
                val cellWidth = (views.x - gap) / 2f
                drawTexturePanel("Camera", left, cellWidth, views.y)
                if (cellWidth > 1f) ImGui.sameLine()
                drawTexturePanel("Encoded / decoded", right, cellWidth, views.y)
            }
            ImGui.endChild()
        }
        ImGui.end()
    }

    private fun drawTexturePanel(
        title: String,
        panel: Panel?,
        width: Float,
        height: Float,
    ) {
        if (width <= 1f || height <= 1f) return
        if (ImGui.beginChild("##$title", ImVec2(width, height), ImGuiChildFlags.BORDERS)) {
            ImGui.text(title)
            val available = ImGui.getContentRegionAvail()
            if (panel == null || !panel.ready) {
                ImGui.textDisabled("waiting for frames...")
            } else {
                val imageScale = min(available.x / panel.width, available.y / panel.height)
                val imageWidth = panel.width * imageScale
                val imageHeight = panel.height * imageScale
                if (imageWidth > 1f && imageHeight > 1f) {
                    val cursor = ImGui.getCursorPos()
                    ImGui.setCursorPos(
                        ImVec2(
                            cursor.x + (available.x - imageWidth) / 2f,
                            cursor.y + (available.y - imageHeight) / 2f,
                        ),
                    )
                    ImGui.image(panel.texture.toImTextureID(), ImVec2(imageWidth, imageHeight))
                }
            }
        }
        ImGui.endChild()
    }

    private suspend fun runSession(
        session: WindowSettings,
        direct: MutableStateFlow<I420Frame?>,
        decoded: MutableStateFlow<I420Frame?>,
        stats: MutableStateFlow<CodecStats>,
        errorText: MutableStateFlow<String?>,
    ) = coroutineScope {
        errorText.value = null
        val channel = Channel<I420Frame>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val pipeline = PipelineController(session.codec, session.frameRate, session.bitrateKbps)
        val camera = CameraSource(session.width, session.height, session.frameRate)
        val sessionStarted = TimeSource.Monotonic.markNow()
        var syntheticStarted = false
        var cameraOpen = false

        fun startSynthetic() {
            if (syntheticStarted) return
            syntheticStarted = true
            println("using the synthetic test pattern")
            launch {
                val pattern = TestPattern(session.width, session.height, session.frameRate)
                while (isActive) {
                    val frame = pattern.next()
                    direct.value = frame
                    channel.send(frame)
                    delay((1000 / session.frameRate).coerceAtLeast(1).toLong())
                }
            }
        }

        try {
            if (session.source == SourceKind.CAMERA) {
                cameraOpen = camera.start(
                    onFrame = { frame ->
                        direct.value = frame
                        channel.trySend(frame)
                    },
                    onError = {
                        println("camera error: $it")
                        errorText.value = it
                    },
                    onFailure = {
                        println("camera unavailable: ${it.message ?: it}")
                        errorText.value = it.message ?: it.toString()
                    },
                )
            }
            if (!cameraOpen) {
                startSynthetic()
            } else {
                launch {
                    delay(2_500)
                    if (direct.value == null) {
                        println("camera produced no frame within 2.5 s; falling back to the test pattern")
                        camera.close()
                        startSynthetic()
                    }
                }
            }

            var inputFrames = 0L
            while (isActive) {
                val frame = withTimeoutOrNull(500) { channel.receive() } ?: continue
                inputFrames++
                val pictures = pipeline.process(frame)
                pictures.lastOrNull()?.let { decoded.value = it }
                pipeline.current?.let { current ->
                    val elapsed = sessionStarted.elapsedNow().inWholeMilliseconds / 1000.0
                    stats.value = current.stats(
                        session.codec,
                        if (elapsed > 0) current.inputFrames / elapsed else 0.0,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            println("codec pipeline failed: ${error.message}")
            error.printStackTrace()
            errorText.value = error.message ?: error.toString()
        } finally {
            channel.close()
            camera.close()
            pipeline.close()
        }
    }

    private fun resolutionChoices(options: DemoOptions): List<CameraResolution> {
        val supported = runCatching {
            cameraSystem().use { system ->
                system.defaultCamera(CameraLensFacing.UNKNOWN)?.resolutions.orEmpty()
            }
        }.getOrDefault(emptyList())
        val common = listOf(
            CameraResolution(320, 240),
            CameraResolution(640, 480),
            CameraResolution(1280, 720),
            CameraResolution(1920, 1080),
        )
        val initial = CameraResolution(options.width, options.height)
        return (listOf(initial) + supported + common)
            .filter { it.width % 2 == 0 && it.height % 2 == 0 }
            .distinct()
            .sortedByDescending { it.pixelCount }
            .take(12)
    }

    private class Panel(
        private val renderer: cn.enaium.sdl.SDLRenderer,
        private val imgui: ImGuiSdlRendererBackend,
        frame: I420Frame,
    ) : AutoCloseable {
        var width: Int = frame.width
            private set
        var height: Int = frame.height
            private set
        var texture: SDLTexture = renderer.createTexture(
            SDLPixelFormat.RGBA32,
            SDLTextureAccess.STREAMING,
            width,
            height,
        )
            private set
        var ready: Boolean = false
            private set

        private var rgba = ByteArray(width * height * 4)

        init {
            imgui.registerTexture(texture)
        }

        fun matches(frame: I420Frame): Boolean = frame.width == width && frame.height == height

        fun update(frame: I420Frame) {
            if (!matches(frame)) {
                imgui.unregisterTexture(texture)
                texture.close()
                width = frame.width
                height = frame.height
                texture = renderer.createTexture(
                    SDLPixelFormat.RGBA32,
                    SDLTextureAccess.STREAMING,
                    width,
                    height,
                )
                imgui.registerTexture(texture)
                rgba = ByteArray(width * height * 4)
            }
            cn.enaium.libyuv.LibYuv.i420ToAbgr(
                frame.y, frame.u, frame.v, rgba,
                width, height,
                dstStrideAbgr = width * 4,
            )
            texture.update(null, rgba, width * 4)
            ready = true
        }

        override fun close() {
            imgui.unregisterTexture(texture)
            texture.close()
        }
    }
}
