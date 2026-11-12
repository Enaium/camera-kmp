package cn.enaium.camera

import kotlinx.browser.window

/**
 * getUserMedia backend shared by Kotlin/JS and Kotlin/Wasm.
 *
 * The browser hides most capabilities until the user grants permission, so
 * [cameras] starts with what `enumerateDevices()` exposes (id, label, maybe a
 * capability range) and refines the format list after a session has been
 * opened. [refresh] starts a new asynchronous enumeration; the result is
 * visible on a later [cameras] call.
 */
class WebCameraSystem internal constructor() : CameraSystem {
    override val backend: CameraBackend = CameraBackend.WEB
    override val name: String = "getUserMedia"

    private val discovered = mutableMapOf<String, CameraInfo>()

    init {
        if (!WebBridge.supported()) {
            throw CameraUnsupportedException(
                "getUserMedia is not available: open the page over HTTPS (or localhost) in a browser",
            )
        }
        WebBridge.refreshDevices()
    }

    override fun refresh() {
        WebBridge.refreshDevices()
    }

    override fun cameras(): List<CameraInfo> {
        val count = WebBridge.deviceCount()
        val list = ArrayList<CameraInfo>(count)
        for (index in 0 until count) {
            val id = WebBridge.deviceId(index)
            val label = WebBridge.deviceLabel(index)
            val facing = facing(WebBridge.deviceFacing(index))
            val maxWidth = WebBridge.deviceMaxWidth(index)
            val maxHeight = WebBridge.deviceMaxHeight(index)
            val maxFrameRate = WebBridge.deviceMaxFrameRate(index)
            val previous = discovered[id]
            val modes = if (maxWidth > 0 && maxHeight > 0) {
                deriveFormats(maxWidth, maxHeight, maxFrameRate)
            } else {
                previous?.supportedFormats ?: emptyList()
            }
            list += CameraInfo(
                id = id,
                name = label.ifBlank { "Camera ${index + 1}" },
                lensFacing = if (previous != null && previous.lensFacing != CameraLensFacing.UNKNOWN) {
                    previous.lensFacing
                } else {
                    facing
                },
                transport = if (facing == CameraLensFacing.EXTERNAL) CameraTransport.USB else CameraTransport.INTERNAL,
                isDefault = index == 0,
                supportedFormats = if (previous != null && previous.supportedFormats.size > modes.size) {
                    previous.supportedFormats
                } else {
                    modes
                },
            )
        }
        return list
    }

    override fun defaultCamera(lensFacing: CameraLensFacing): CameraInfo? {
        val all = cameras()
        return all.firstOrNull { it.lensFacing == lensFacing }
            ?: all.firstOrNull { it.lensFacing == CameraLensFacing.UNKNOWN }
            ?: all.firstOrNull()
    }

    override fun open(config: CameraConfig): CameraSession {
        val all = cameras()
        val id = config.cameraId
        val info = if (id != null) {
            all.firstOrNull { it.id == id }
                ?: CameraInfo(id, id, CameraLensFacing.UNKNOWN)
        } else {
            all.firstOrNull { it.lensFacing == config.lensFacing }
                ?: all.firstOrNull { it.lensFacing == CameraLensFacing.UNKNOWN }
                ?: all.firstOrNull()
                ?: CameraInfo("", "Default camera", CameraLensFacing.UNKNOWN)
        }
        return WebCameraSession(this, info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        WebCameraSession(this, camera, config)

    override fun close() = Unit

    /** Records what the stream actually negotiated, so later enumerations are accurate. */
    internal fun record(
        deviceId: String,
        width: Int,
        height: Int,
        frameRate: Int,
        facing: CameraLensFacing,
        maxWidth: Int,
        maxHeight: Int,
        maxFrameRate: Int,
    ) {
        if (deviceId.isBlank()) return
        val existing = discovered[deviceId]
        val modes = deriveFormats(
            if (maxWidth > 0) maxWidth else width,
            if (maxHeight > 0) maxHeight else height,
            if (maxFrameRate > 0) maxFrameRate else frameRate,
        )
        discovered[deviceId] = CameraInfo(
            id = deviceId,
            name = existing?.name ?: deviceId,
            lensFacing = if (facing != CameraLensFacing.UNKNOWN) facing else existing?.lensFacing ?: CameraLensFacing.UNKNOWN,
            transport = if (facing == CameraLensFacing.EXTERNAL) CameraTransport.USB else CameraTransport.INTERNAL,
            supportedFormats = modes,
            isDefault = existing?.isDefault ?: false,
        )
    }

    private fun facing(code: Int): CameraLensFacing = when (code) {
        0 -> CameraLensFacing.FRONT
        1 -> CameraLensFacing.BACK
        2 -> CameraLensFacing.EXTERNAL
        else -> CameraLensFacing.UNKNOWN
    }

    /**
     * Common capture modes within the browser's capability range. Browsers
     * expose only maximum width/height/frameRate, not a mode list, so these are
     * the usual UVC/laptop modes that fit the advertised maxima.
     */
    private fun deriveFormats(maxWidth: Int, maxHeight: Int, maxFrameRate: Int): List<CameraFormat> {
        if (maxWidth <= 0 || maxHeight <= 0) return emptyList()
        val resolutions = listOf(
            3840 to 2160,
            1920 to 1080,
            1440 to 1080,
            1280 to 720,
            960 to 720,
            854 to 480,
            640 to 480,
            320 to 240,
            160 to 120,
        ).filter { (width, height) -> width <= maxWidth && height <= maxHeight }
        val rates = listOf(60, 30, 15)
            .filter { maxFrameRate <= 0 || it <= maxFrameRate }
            .ifEmpty { listOf(30) }
        val modes = ArrayList<CameraFormat>()
        for ((width, height) in resolutions) {
            for (rate in rates) {
                modes += CameraFormat(width, height, PixelFormat.RGBA, rate)
            }
        }
        if (modes.isEmpty()) {
            modes += CameraFormat(maxWidth, maxHeight, PixelFormat.RGBA, rates.first())
        }
        return modes
    }
}

private class WebCameraSession(
    private val system: WebCameraSystem,
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(
    camera = camera,
    config = config,
    negotiated = CameraFormat(
        config.format.width,
        config.format.height,
        PixelFormat.RGBA,
        config.format.frameRate,
    ),
) {
    private var handle: Int = 0
    private var timer: Int = 0
    private var buffer: ByteArray = ByteArray(0)
    private var failed: Boolean = false
    private var recorded: Boolean = false

    override fun startCapture() {
        handle = WebBridge.open(
            deviceId = camera.id.takeIf { it.isNotBlank() },
            width = config.format.width,
            height = config.format.height,
            frameRate = config.format.frameRate,
        )
        if (handle <= 0) {
            throw CameraException("getUserMedia could not open ${camera.name}")
        }
        val interval = (1000 / config.format.frameRate).coerceAtLeast(5)
        timer = window.setInterval({
            poll()
            null
        }, interval)
        poll()
    }

    override fun stopCapture() {
        if (timer != 0) {
            window.clearInterval(timer)
            timer = 0
        }
        if (handle != 0) {
            WebBridge.close(handle)
            handle = 0
        }
    }

    override fun readFrame(timeoutMillis: Long): CameraFrame? {
        throw CameraUnsupportedException(
            "the browser delivers frames through CameraSession.start; readFrame is not available on the web",
        )
    }

    private fun poll() {
        if (!isRunning || handle == 0) return
        when (WebBridge.status(handle)) {
            -1 -> {
                if (!failed) {
                    failed = true
                    reportError(CameraException(WebBridge.error(handle)))
                    stop()
                }
            }

            0 -> Unit

            else -> {
                val width = WebBridge.width(handle)
                val height = WebBridge.height(handle)
                if (width <= 0 || height <= 0) return
                val frameRate = WebBridge.frameRate(handle).takeIf { it > 0 } ?: config.format.frameRate
                updateNegotiatedFormat(CameraFormat(width, height, PixelFormat.RGBA, frameRate))
                val needed = width * height * 4
                if (buffer.size != needed) buffer = ByteArray(needed)
                val written = WebBridge.frame(handle, buffer)
                if (written <= 0) return
                if (!recorded) {
                    recorded = true
                    // Once permission is granted, enumerateDevices exposes the
                    // real labels and capability ranges.
                    WebBridge.refreshDevices()
                }
                system.record(
                    deviceId = camera.id,
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    facing = facing(WebBridge.facing(handle)),
                    maxWidth = WebBridge.maxWidth(handle),
                    maxHeight = WebBridge.maxHeight(handle),
                    maxFrameRate = WebBridge.maxFrameRate(handle),
                )
                publish(
                    SourceFrame(
                        width = width,
                        height = height,
                        pixelFormat = PixelFormat.RGBA,
                        planes = listOf(SourcePlane.packed(buffer, width, height, pixelStride = 4)),
                        timestampNanos = monotonicNanos(),
                    ),
                )
            }
        }
    }

    private fun facing(code: Int): CameraLensFacing = when (code) {
        0 -> CameraLensFacing.FRONT
        1 -> CameraLensFacing.BACK
        2 -> CameraLensFacing.EXTERNAL
        else -> CameraLensFacing.UNKNOWN
    }
}

actual fun availableCameraBackends(): List<CameraBackend> =
    if (WebBridge.supported()) listOf(CameraBackend.AUTO, CameraBackend.WEB) else emptyList()

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO, CameraBackend.WEB -> WebCameraSystem()
    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available on the web; use getUserMedia",
    )
}
