package cn.enaium.camera

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

private val LITTLE_ENDIAN: Boolean = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

/**
 * Desktop Linux backend: Video4Linux2 through the Foreign Function & Memory API.
 *
 * Enumeration and capture mirror the Kotlin/Native V4L2 backend: `/dev/video0`
 * to `/dev/video63` are queried with `VIDIOC_QUERYCAP`, formats/sizes/intervals
 * are read with the `VIDIOC_ENUM_*` ioctls, and frames are captured with MMAP
 * buffers (`REQBUFS`/`QUERYBUF`/`mmap` + `STREAMON`) on a dedicated worker
 * thread that polls, dequeues, copies and requeues buffers.
 *
 * Only the single-planar capture API is used, so devices that expose
 * `V4L2_CAP_VIDEO_CAPTURE_MPLANE` exclusively are skipped rather than opened
 * with a wrong buffer layout. The struct layouts and ioctl request numbers
 * below match the 64-bit Linux ABI (`videodev2.h`): the ioctls encode
 * `sizeof(struct)` in their request number, so a mismatched layout would be
 * rejected by the kernel instead of silently corrupting memory.
 */
internal class JvmV4l2CameraSystem(
    private val backendKind: CameraBackend = CameraBackend.V4L2,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "V4L2"

    override fun cameras(): List<CameraInfo> {
        val result = ArrayList<CameraInfo>()
        for (index in 0 until V4l2.MAX_DEVICES) {
            val path = "/dev/video$index"
            val device = runCatching { queryDevice(path) }.getOrNull() ?: continue
            if (device.formats.isEmpty()) continue
            if (backendKind == CameraBackend.UVC && device.transport != CameraTransport.USB) continue
            result += CameraInfo(
                id = path,
                name = device.name,
                lensFacing = if (device.transport == CameraTransport.USB) {
                    CameraLensFacing.EXTERNAL
                } else {
                    CameraLensFacing.UNKNOWN
                },
                sensorOrientation = 0,
                transport = device.transport,
                isDefault = result.isEmpty(),
                devicePath = path,
                supportedFormats = device.formats,
            )
        }
        return result
    }

    override fun defaultCamera(lensFacing: CameraLensFacing): CameraInfo? {
        val all = cameras()
        return all.firstOrNull { it.lensFacing == lensFacing } ?: all.firstOrNull()
    }

    override fun open(config: CameraConfig): CameraSession {
        val all = cameras()
        val info = config.cameraId?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull { it.lensFacing == config.lensFacing }
            ?: all.firstOrNull()
            ?: throw CameraException(
                "no V4L2 capture device is available: check that a camera is connected and that " +
                    "/dev/video* is readable by the current user (usually the 'video' group)",
            )
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        V4l2Session(camera, config)

    override fun close() = Unit
}

private class V4l2Device(
    val path: String,
    val name: String,
    val transport: CameraTransport,
    val formats: List<CameraFormat>,
)

private class V4l2Capability(
    val driver: String,
    val card: String,
    val busInfo: String,
    val capabilities: Long,
)

private class V4l2Mapping(
    val address: Long,
    val length: Int,
)

private class V4l2ConfiguredFormat(
    val format: CameraFormat,
    val stride: Int,
    val sizeImage: Int,
)

private fun queryDevice(path: String): V4l2Device? {
    val fd = LinuxNative.open(path)
    if (fd < 0) return null
    try {
        val capability = queryCapability(fd) ?: return null
        if ((capability.capabilities and V4l2.CAP_VIDEO_CAPTURE) == 0L) return null
        val formats = enumerateFormats(fd)
        return V4l2Device(
            path = path,
            name = capability.card.ifBlank { "V4L2 camera ${path.substringAfterLast('/')}" },
            transport = transportOf(capability.driver, capability.busInfo),
            formats = formats,
        )
    } finally {
        LinuxNative.close(fd)
    }
}

private fun queryCapability(fd: Int): V4l2Capability? {
    val arena = Arena.ofConfined()
    try {
        val capability = arena.allocate(V4l2.CAPABILITY_LAYOUT)
        capability.zero()
        if (LinuxNative.ioctl(fd, V4l2.VIDIOC_QUERYCAP, capability) < 0) return null
        val driver = capability.cString(V4l2.CAP_DRIVER, 16)
        val card = capability.cString(V4l2.CAP_CARD, 32)
        val busInfo = capability.cString(V4l2.CAP_BUS_INFO, 32)
        val declared = capability.get(ValueLayout.JAVA_INT, V4l2.CAP_CAPABILITIES).toLong() and 0xFFFFFFFFL
        val device = capability.get(ValueLayout.JAVA_INT, V4l2.CAP_DEVICE_CAPS).toLong() and 0xFFFFFFFFL
        val capabilities = if ((declared and V4l2.CAP_DEVICE_CAPS_FLAG) != 0L) device else declared
        return V4l2Capability(driver, card, busInfo, capabilities)
    } finally {
        arena.close()
    }
}

private fun transportOf(driver: String, busInfo: String): CameraTransport = when {
    busInfo.contains("usb", ignoreCase = true) || driver.contains("uvc", ignoreCase = true) ->
        CameraTransport.USB

    busInfo.contains("platform", ignoreCase = true) -> CameraTransport.INTERNAL
    else -> CameraTransport.UNKNOWN
}

private fun enumerateFormats(fd: Int): List<CameraFormat> {
    val formats = ArrayList<CameraFormat>()
    val arena = Arena.ofConfined()
    try {
        val description = arena.allocate(V4l2.FMTDESC_LAYOUT)
        var index = 0
        while (index < V4l2.MAX_FORMATS) {
            description.zero()
            description.set(ValueLayout.JAVA_INT, V4l2.FMTDESC_INDEX, index)
            description.set(ValueLayout.JAVA_INT, V4l2.FMTDESC_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_ENUM_FMT, description) < 0) break
            val fourcc = description.get(ValueLayout.JAVA_INT, V4l2.FMTDESC_PIXELFORMAT)
            val pixelFormat = pixelFormatOf(fourcc)
            if (pixelFormat != null) {
                for (size in enumerateSizes(fd, fourcc)) {
                    val rates = enumerateFrameRates(fd, fourcc, size.first, size.second)
                        .ifEmpty { listOf(CameraFormat.DEFAULT_FRAME_RATE) }
                    for (rate in rates) {
                        formats += CameraFormat(size.first, size.second, pixelFormat, rate)
                    }
                }
            }
            index++
        }
    } finally {
        arena.close()
    }
    return formats.distinct()
}

private fun enumerateSizes(fd: Int, fourcc: Int): List<Pair<Int, Int>> {
    val sizes = LinkedHashSet<Pair<Int, Int>>()
    val arena = Arena.ofConfined()
    try {
        val description = arena.allocate(V4l2.FRMSIZE_LAYOUT)
        var index = 0
        while (index < V4l2.MAX_SIZES) {
            description.zero()
            description.set(ValueLayout.JAVA_INT, V4l2.FRMSIZE_INDEX, index)
            description.set(ValueLayout.JAVA_INT, V4l2.FRMSIZE_PIXEL_FORMAT, fourcc)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_ENUM_FRAMESIZES, description) < 0) break
            when (description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_TYPE)) {
                V4l2.FRMSIZE_TYPE_DISCRETE -> {
                    val width = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE)
                    val height = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE + 4)
                    if (width > 0 && height > 0) sizes += width to height
                }

                V4l2.FRMSIZE_TYPE_STEPWISE -> {
                    val minWidth = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE)
                    val maxWidth = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE + 4)
                    val minHeight = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE + 12)
                    val maxHeight = description.get(ValueLayout.JAVA_INT, V4l2.FRMSIZE_VALUE + 16)
                    for ((width, height) in V4l2.COMMON_SIZES) {
                        if (width in minWidth..maxWidth && height in minHeight..maxHeight) {
                            sizes += width to height
                        }
                    }
                }

                else -> break
            }
            index++
        }
    } finally {
        arena.close()
    }
    return sizes.toList()
}

private fun enumerateFrameRates(fd: Int, fourcc: Int, width: Int, height: Int): List<Int> {
    val rates = LinkedHashSet<Int>()
    val arena = Arena.ofConfined()
    try {
        val description = arena.allocate(V4l2.FRMIVAL_LAYOUT)
        var index = 0
        while (index < V4l2.MAX_INTERVALS) {
            description.zero()
            description.set(ValueLayout.JAVA_INT, V4l2.FRMIVAL_INDEX, index)
            description.set(ValueLayout.JAVA_INT, V4l2.FRMIVAL_PIXEL_FORMAT, fourcc)
            description.set(ValueLayout.JAVA_INT, V4l2.FRMIVAL_WIDTH, width)
            description.set(ValueLayout.JAVA_INT, V4l2.FRMIVAL_HEIGHT, height)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_ENUM_FRAMEINTERVALS, description) < 0) break
            when (description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_TYPE)) {
                V4l2.FRMIVAL_TYPE_DISCRETE -> {
                    val numerator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE)
                    val denominator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE + 4)
                    fps(numerator, denominator)?.let { rates += it }
                }

                V4l2.FRMIVAL_TYPE_STEPWISE -> {
                    // The union holds three v4l2_fract values: min, max and step
                    // intervals in seconds, so the highest rate comes from the
                    // min interval and the lowest from the max interval.
                    val minNumerator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE)
                    val minDenominator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE + 4)
                    val maxNumerator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE + 8)
                    val maxDenominator = description.get(ValueLayout.JAVA_INT, V4l2.FRMIVAL_VALUE + 12)
                    val maxFps = fps(minNumerator, minDenominator) ?: 1
                    val minFps = fps(maxNumerator, maxDenominator) ?: maxFps
                    for (candidate in V4l2.COMMON_FRAME_RATES) {
                        if (candidate in minFps..maxFps) rates += candidate
                    }
                }

                else -> break
            }
            index++
        }
    } finally {
        arena.close()
    }
    return rates.toList()
}

/** Frame rate of the interval [numerator]/[denominator] seconds, rounded. */
private fun fps(numerator: Int, denominator: Int): Int? {
    if (numerator <= 0 || denominator <= 0) return null
    return ((denominator + numerator / 2) / numerator).coerceAtLeast(1)
}

private class V4l2Session(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, v4l2BaseFormat(camera, config)) {

    private val lock = Any()
    private var descriptor: Int = -1
    private var mappings: List<V4l2Mapping> = emptyList()
    private var worker: Thread? = null
    private var started = false

    override fun startCapture() {
        synchronized(lock) {
            if (started) return
            val path = camera.devicePath ?: camera.id
            val device = queryDevice(path)
                ?: throw CameraException("V4L2 camera ${camera.id} is no longer attached or readable")
            if (device.formats.isEmpty()) {
                throw CameraException("V4L2 camera ${camera.name} exposes no supported capture format")
            }
            val native = selectNativeFormat(device, config)
            val fd = LinuxNative.open(path)
            if (fd < 0) {
                throw CameraException("cannot open $path: ${LinuxNative.lastError()}")
            }
            var mapped: List<V4l2Mapping> = emptyList()
            var streamed = false
            try {
                val configured = configureFormat(fd, device, native)
                val count = requestBuffers(fd, config.bufferCount)
                mapped = mapBuffers(fd, count)
                for (index in mapped.indices) queueBuffer(fd, index)
                stream(fd, on = true)
                streamed = true
                updateNegotiatedFormat(configured.format)

                descriptor = fd
                mappings = mapped
                started = true
                val captureThread = thread(
                    name = "camera-kmp-v4l2-${camera.id.substringAfterLast('/')}",
                    isDaemon = true,
                ) {
                    captureLoop(
                        fd = fd,
                        format = configured.format,
                        stride = configured.stride,
                        sizeImage = configured.sizeImage,
                        mappings = mapped,
                    )
                }
                worker = captureThread
            } catch (error: Throwable) {
                if (streamed) runCatching { stream(fd, on = false) }
                for (mapping in mapped) LinuxNative.munmap(mapping.address, mapping.length)
                LinuxNative.close(fd)
                descriptor = -1
                mappings = emptyList()
                started = false
                worker = null
                throw (
                    (error as? CameraException)
                        ?: CameraException("cannot start V4L2 camera ${camera.name}: ${error.message}", error)
                    )
            }
        }
    }

    override fun stopCapture() {
        val captureThread = synchronized(lock) {
            started = false
            val current = worker
            worker = null
            current
        }
        if (captureThread != null && captureThread !== Thread.currentThread()) {
            captureThread.join(JOIN_TIMEOUT_MILLIS)
        }
        synchronized(lock) {
            releaseNative()
        }
    }

    override fun release() {
        stopCapture()
    }

    private fun releaseNative() {
        val fd = descriptor
        val mapped = mappings
        descriptor = -1
        mappings = emptyList()
        if (fd < 0) return
        runCatching { stream(fd, on = false) }
        for (mapping in mapped) {
            LinuxNative.munmap(mapping.address, mapping.length)
        }
        LinuxNative.close(fd)
    }

    private fun captureLoop(
        fd: Int,
        format: CameraFormat,
        stride: Int,
        sizeImage: Int,
        mappings: List<V4l2Mapping>,
    ) {
        val arena = Arena.ofConfined()
        try {
            val pollDescriptor = arena.allocate(V4l2.POLLFD_LAYOUT)
            val buffer = arena.allocate(V4l2.BUFFER_LAYOUT)
            while (isCaptureActive()) {
                pollDescriptor.set(ValueLayout.JAVA_INT, V4l2.POLLFD_FD, fd)
                pollDescriptor.set(ValueLayout.JAVA_SHORT, V4l2.POLLFD_EVENTS, V4l2.POLLIN.toShort())
                val ready = LinuxNative.poll(pollDescriptor, 1L, POLL_TIMEOUT_MILLIS)
                if (ready < 0) {
                    if (LinuxNative.lastErrno() == V4l2.EINTR) continue
                    reportError(
                        CameraException(
                            "polling ${camera.name} failed: ${LinuxNative.lastError()}",
                        ),
                    )
                    return
                }
                if (ready == 0) continue

                val revents = pollDescriptor.get(ValueLayout.JAVA_SHORT, V4l2.POLLFD_REVENTS).toInt()
                if ((revents and (V4l2.POLLIN or V4l2.POLLERR or V4l2.POLLNVAL)) == 0) continue

                buffer.zero()
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP)
                if (LinuxNative.ioctl(fd, V4l2.VIDIOC_DQBUF, buffer) < 0) {
                    val code = LinuxNative.lastErrno()
                    // A buffer interrupted between poll and dequeue is only a
                    // missed frame; anything else means the stream died.
                    if (code == V4l2.EAGAIN || code == V4l2.EINTR) continue
                    reportError(
                        CameraException(
                            "V4L2 dequeuing from ${camera.name} failed: ${LinuxNative.errorText(code)}",
                        ),
                    )
                    return
                }

                val index = buffer.get(ValueLayout.JAVA_INT, V4l2.BUFFER_INDEX)
                val mapping = mappings.getOrNull(index)
                if (mapping != null) {
                    val bytesused = buffer.get(ValueLayout.JAVA_INT, V4l2.BUFFER_BYTESUSED)
                    val available = when {
                        bytesused in 1..mapping.length -> bytesused
                        sizeImage in 1..mapping.length -> sizeImage
                        else -> mapping.length
                    }
                    val frame = buildSourceFrame(
                        mapping,
                        available,
                        format,
                        stride,
                        bufferTimestamp(buffer),
                    )
                    if (frame != null) {
                        publish(frame)
                    }
                }
                buffer.zero()
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_INDEX, index)
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP)
                if (LinuxNative.ioctl(fd, V4l2.VIDIOC_QBUF, buffer) < 0) {
                    reportError(
                        CameraException(
                            "V4L2 requeueing a frame of ${camera.name} failed: ${LinuxNative.lastError()}",
                        ),
                    )
                    return
                }
            }
        } finally {
            arena.close()
        }
    }

    private fun bufferTimestamp(buffer: MemorySegment): Long {
        val seconds = buffer.get(ValueLayout.JAVA_LONG, V4l2.BUFFER_TIMESTAMP)
        val micros = buffer.get(ValueLayout.JAVA_LONG, V4l2.BUFFER_TIMESTAMP_USEC)
        if (seconds <= 0L || seconds > MAX_TIMESTAMP_SECONDS) return monotonicNanos()
        return seconds * 1_000_000_000L + micros * 1_000L
    }

    /**
     * Copies one device buffer into tightly packed plane arrays.
     *
     * `bytesperline` of the negotiated `v4l2_pix_format` is the row stride, so
     * padded rows are copied row by row; the plane heights and chroma strides
     * follow the kernel's layout for each fourcc.
     */
    private fun buildSourceFrame(
        mapping: V4l2Mapping,
        available: Int,
        format: CameraFormat,
        stride: Int,
        timestamp: Long,
    ): SourceFrame? {
        val source = MemorySegment.ofAddress(mapping.address).reinterpret(mapping.length.toLong())
        val width = format.width
        val height = format.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaStride = if (stride > 0) stride else width
        return when (format.pixelFormat) {
            PixelFormat.YUY2, PixelFormat.UYVY -> {
                val rowBytes = width * 2
                if (lumaStride < rowBytes) return null
                val bytes = copyRows(source, 0L, lumaStride, rowBytes, height, available) ?: return null
                SourceFrame(
                    width = width,
                    height = height,
                    pixelFormat = format.pixelFormat,
                    planes = listOf(SourcePlane.packed(bytes, width, height, 2)),
                    timestampNanos = timestamp,
                )
            }

            PixelFormat.NV12, PixelFormat.NV21 -> {
                val luma = copyRows(source, 0L, lumaStride, width, height, available) ?: return null
                val chromaRowBytes = chromaWidth * 2
                // UVC devices usually pad both semi-planes to bytesperline;
                // fall back to the tight chroma stride when the buffer is
                // exactly luma + interleaved chroma.
                val chromaStride = if (
                    lumaStride.toLong() * (height + chromaHeight) <= available.toLong()
                ) {
                    lumaStride
                } else {
                    chromaRowBytes
                }
                val chroma = copyRows(
                    source,
                    lumaStride.toLong() * height,
                    chromaStride,
                    chromaRowBytes,
                    chromaHeight,
                    available,
                ) ?: return null
                SourceFrame(
                    width = width,
                    height = height,
                    pixelFormat = format.pixelFormat,
                    planes = listOf(
                        SourcePlane(luma, 0, width, 1, width, height),
                        SourcePlane(chroma, 0, chromaRowBytes, 2, chromaWidth, chromaHeight),
                    ),
                    timestampNanos = timestamp,
                )
            }

            PixelFormat.I420 -> {
                val luma = copyRows(source, 0L, lumaStride, width, height, available) ?: return null
                val chromaStride = max(chromaWidth, lumaStride / 2)
                val uOffset = lumaStride.toLong() * height
                val u = copyRows(source, uOffset, chromaStride, chromaWidth, chromaHeight, available)
                    ?: return null
                val v = copyRows(
                    source,
                    uOffset + chromaStride.toLong() * chromaHeight,
                    chromaStride,
                    chromaWidth,
                    chromaHeight,
                    available,
                ) ?: return null
                SourceFrame(
                    width = width,
                    height = height,
                    pixelFormat = PixelFormat.I420,
                    planes = listOf(
                        SourcePlane(luma, 0, width, 1, width, height),
                        SourcePlane(u, 0, chromaWidth, 1, chromaWidth, chromaHeight),
                        SourcePlane(v, 0, chromaWidth, 1, chromaWidth, chromaHeight),
                    ),
                    timestampNanos = timestamp,
                )
            }

            else -> null
        }
    }

    private fun copyRows(
        source: MemorySegment,
        sourceOffset: Long,
        sourceStride: Int,
        rowBytes: Int,
        rows: Int,
        available: Int,
    ): ByteArray? {
        if (rows <= 0 || rowBytes <= 0 || sourceStride < rowBytes) return null
        val end = sourceOffset + sourceStride.toLong() * (rows - 1L) + rowBytes
        if (end > available.toLong()) return null
        val bytes = ByteArray(rowBytes * rows)
        for (row in 0 until rows) {
            MemorySegment.copy(
                source,
                ValueLayout.JAVA_BYTE,
                sourceOffset + sourceStride.toLong() * row,
                bytes,
                row * rowBytes,
                rowBytes,
            )
        }
        return bytes
    }

    private fun configureFormat(fd: Int, device: V4l2Device, native: CameraFormat): V4l2ConfiguredFormat {
        val fourcc = fourccOf(native.pixelFormat)
            ?: throw CameraException("V4L2 cannot capture ${native.pixelFormat}")
        val arena = Arena.ofConfined()
        try {
            val format = arena.allocate(V4l2.FORMAT_LAYOUT)
            format.zero()
            format.set(ValueLayout.JAVA_INT, V4l2.FORMAT_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            val pix = format.asSlice(V4l2.FORMAT_PIX)
            pix.set(ValueLayout.JAVA_INT, V4l2.PIX_WIDTH, native.width)
            pix.set(ValueLayout.JAVA_INT, V4l2.PIX_HEIGHT, native.height)
            pix.set(ValueLayout.JAVA_INT, V4l2.PIX_PIXEL_FORMAT, fourcc)
            pix.set(ValueLayout.JAVA_INT, V4l2.PIX_FIELD, V4l2.FIELD_ANY)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_S_FMT, format) < 0) {
                throw CameraException("VIDIOC_S_FMT on ${device.path} failed: ${LinuxNative.lastError()}")
            }

            val width = pix.get(ValueLayout.JAVA_INT, V4l2.PIX_WIDTH)
            val height = pix.get(ValueLayout.JAVA_INT, V4l2.PIX_HEIGHT)
            val pixelFormat = pixelFormatOf(pix.get(ValueLayout.JAVA_INT, V4l2.PIX_PIXEL_FORMAT))
                ?: throw CameraException(
                    "V4L2 selected the unsupported pixel format 0x" +
                        pix.get(ValueLayout.JAVA_INT, V4l2.PIX_PIXEL_FORMAT).toUInt().toString(16),
                )
            if (width <= 0 || height <= 0) {
                throw CameraException("V4L2 negotiated an empty frame size ${width}x$height")
            }
            val stride = pix.get(ValueLayout.JAVA_INT, V4l2.PIX_BYTESPERLINE)
            val sizeImage = pix.get(ValueLayout.JAVA_INT, V4l2.PIX_SIZEIMAGE)
            val strideValue = if (stride > 0) {
                stride
            } else {
                when (pixelFormat) {
                    PixelFormat.YUY2, PixelFormat.UYVY -> width * 2
                    else -> width
                }
            }
            val rates = device.formats.asSequence()
                .filter { it.width == width && it.height == height && it.pixelFormat == pixelFormat }
                .map { it.frameRate }
                .distinct()
                .toList()
            val frameRate = rates.minByOrNull { abs(it - config.format.frameRate) }
                ?: native.frameRate
            val actualFrameRate = setFrameRate(fd, frameRate)
            return V4l2ConfiguredFormat(
                format = CameraFormat(width, height, pixelFormat, actualFrameRate),
                stride = strideValue,
                sizeImage = sizeImage,
            )
        } finally {
            arena.close()
        }
    }

    private fun setFrameRate(fd: Int, requested: Int): Int {
        val arena = Arena.ofConfined()
        try {
            val parameter = arena.allocate(V4l2.STREAMPARM_LAYOUT)
            parameter.zero()
            parameter.set(ValueLayout.JAVA_INT, V4l2.STREAMPARM_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            parameter.set(ValueLayout.JAVA_INT, V4l2.STREAMPARM_TIME_NUMERATOR, 1)
            parameter.set(ValueLayout.JAVA_INT, V4l2.STREAMPARM_TIME_DENOMINATOR, requested)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_S_PARM, parameter) < 0) return requested
            val numerator = parameter.get(ValueLayout.JAVA_INT, V4l2.STREAMPARM_TIME_NUMERATOR)
            val denominator = parameter.get(ValueLayout.JAVA_INT, V4l2.STREAMPARM_TIME_DENOMINATOR)
            return if (numerator > 0 && denominator > 0) {
                (denominator + numerator / 2) / numerator
            } else {
                requested
            }
        } finally {
            arena.close()
        }
    }

    private fun requestBuffers(fd: Int, count: Int): Int {
        val arena = Arena.ofConfined()
        try {
            val request = arena.allocate(V4l2.REQUEST_BUFFERS_LAYOUT)
            request.zero()
            request.set(ValueLayout.JAVA_INT, V4l2.REQ_COUNT, count)
            request.set(ValueLayout.JAVA_INT, V4l2.REQ_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            request.set(ValueLayout.JAVA_INT, V4l2.REQ_MEMORY, V4l2.MEMORY_MMAP)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_REQBUFS, request) < 0) {
                throw CameraException("VIDIOC_REQBUFS failed: ${LinuxNative.lastError()}")
            }
            val granted = request.get(ValueLayout.JAVA_INT, V4l2.REQ_COUNT)
            if (granted < 1) throw CameraException("V4L2 granted no MMAP capture buffers")
            return granted
        } finally {
            arena.close()
        }
    }

    private fun mapBuffers(fd: Int, count: Int): List<V4l2Mapping> {
        val arena = Arena.ofConfined()
        val result = ArrayList<V4l2Mapping>(count)
        try {
            val buffer = arena.allocate(V4l2.BUFFER_LAYOUT)
            for (index in 0 until count) {
                buffer.zero()
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_INDEX, index)
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
                buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP)
                if (LinuxNative.ioctl(fd, V4l2.VIDIOC_QUERYBUF, buffer) < 0) {
                    throw CameraException("VIDIOC_QUERYBUF failed for buffer $index: ${LinuxNative.lastError()}")
                }
                val length = buffer.get(ValueLayout.JAVA_INT, V4l2.BUFFER_LENGTH)
                if (length <= 0) throw CameraException("V4L2 buffer $index has no length")
                // `m` is a union of a 32-bit offset and a pointer. The kernel
                // writes the offset into the first 32 bits of the 8-byte slot,
                // so the low half is the offset on little-endian and the high
                // half on big-endian.
                val offset = if (LITTLE_ENDIAN) {
                    buffer.get(ValueLayout.JAVA_LONG, V4l2.BUFFER_M) and 0xFFFFFFFFL
                } else {
                    buffer.get(ValueLayout.JAVA_INT, V4l2.BUFFER_M + 4).toLong() and 0xFFFFFFFFL
                }
                val address = LinuxNative.mmap(
                    length = length.toLong(),
                    prot = V4l2.PROT_READ or V4l2.PROT_WRITE,
                    flags = V4l2.MAP_SHARED,
                    fd = fd,
                    offset = offset,
                )
                if (address.address() == 0L || address.address() == -1L) {
                    throw CameraException("mmap failed for V4L2 buffer $index: ${LinuxNative.lastError()}")
                }
                result += V4l2Mapping(address.address(), length)
            }
            return result
        } catch (error: Throwable) {
            for (mapping in result) LinuxNative.munmap(mapping.address, mapping.length)
            throw error
        } finally {
            arena.close()
        }
    }

    private fun queueBuffer(fd: Int, index: Int) {
        val arena = Arena.ofConfined()
        try {
            val buffer = arena.allocate(V4l2.BUFFER_LAYOUT)
            buffer.zero()
            buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_INDEX, index)
            buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_TYPE, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            buffer.set(ValueLayout.JAVA_INT, V4l2.BUFFER_MEMORY, V4l2.MEMORY_MMAP)
            if (LinuxNative.ioctl(fd, V4l2.VIDIOC_QBUF, buffer) < 0) {
                throw CameraException("VIDIOC_QBUF failed for buffer $index: ${LinuxNative.lastError()}")
            }
        } finally {
            arena.close()
        }
    }

    private fun stream(fd: Int, on: Boolean) {
        val arena = Arena.ofConfined()
        try {
            val type = arena.allocate(ValueLayout.JAVA_INT)
            type.set(ValueLayout.JAVA_INT, 0L, V4l2.BUF_TYPE_VIDEO_CAPTURE)
            val request = if (on) V4l2.VIDIOC_STREAMON else V4l2.VIDIOC_STREAMOFF
            if (LinuxNative.ioctl(fd, request, type) < 0) {
                throw CameraException(
                    "${if (on) "VIDIOC_STREAMON" else "VIDIOC_STREAMOFF"} failed: ${LinuxNative.lastError()}",
                )
            }
        } finally {
            arena.close()
        }
    }

    private companion object {
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val POLL_TIMEOUT_MILLIS = 200
        const val MAX_TIMESTAMP_SECONDS = 4_102_444_800L
    }
}

private fun v4l2BaseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val mode = camera.bestFormat(
        config.format.width,
        config.format.height,
        null,
        config.format.frameRate,
    ) ?: CameraFormat(config.format.width, config.format.height, PixelFormat.NV12, config.format.frameRate)
    return CameraFormat(mode.width, mode.height, mode.pixelFormat, mode.frameRate)
}

/**
 * Picks the advertised mode closest to the requested one, preferring the
 * requested pixel layout so the shared converter has the least work.
 */
private fun selectNativeFormat(device: V4l2Device, config: CameraConfig): CameraFormat {
    val supported = device.formats
    if (supported.isEmpty()) throw CameraException("${device.path} exposes no supported V4L2 format")
    val requested = config.format
    val matching = supported.filter { it.pixelFormat == requested.pixelFormat }
    val candidates = matching.ifEmpty { supported }
    val targetPixels = requested.width.toLong() * requested.height
    val targetAspect = requested.width.toDouble() / requested.height
    return candidates.minByOrNull { mode ->
        val aspect = abs(mode.width.toDouble() / mode.height - targetAspect)
        val size = abs(mode.width.toLong() * mode.height - targetPixels)
        (aspect * 1_000_000).toLong() + size + abs(mode.frameRate - requested.frameRate).toLong() * 1_000L
    } ?: throw CameraException("${device.path} advertised no usable V4L2 format")
}

private fun pixelFormatOf(fourcc: Int): PixelFormat? = when (fourcc) {
    V4l2.PIX_FMT_YUYV -> PixelFormat.YUY2
    V4l2.PIX_FMT_UYVY -> PixelFormat.UYVY
    V4l2.PIX_FMT_NV12 -> PixelFormat.NV12
    V4l2.PIX_FMT_NV21 -> PixelFormat.NV21
    V4l2.PIX_FMT_YUV420 -> PixelFormat.I420
    else -> null
}

private fun fourccOf(format: PixelFormat): Int? = when (format) {
    PixelFormat.YUY2 -> V4l2.PIX_FMT_YUYV
    PixelFormat.UYVY -> V4l2.PIX_FMT_UYVY
    PixelFormat.NV12 -> V4l2.PIX_FMT_NV12
    PixelFormat.NV21 -> V4l2.PIX_FMT_NV21
    PixelFormat.I420 -> V4l2.PIX_FMT_YUV420
    else -> null
}

private fun MemorySegment.zero() {
    fill(0.toByte())
}

private fun MemorySegment.cString(offset: Long, capacity: Int): String {
    val slice = asSlice(offset, capacity.toLong())
    return runCatching { slice.getString(0) }.getOrElse { "" }.trim()
}

private fun fourcc(a: Char, b: Char, c: Char, d: Char): Int =
    a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)

/**
 * FFM layer over the libc calls V4L2 needs: `open`, `ioctl`, `mmap`, `munmap`,
 * `poll` and `close`. Every symbol is looked up once and wrapped in a downcall
 * handle with its exact signature; `ioctl` is variadic (`int ioctl(int, unsigned
 * long, ...)`), so it is registered with `firstVariadicArg` and the pointer is
 * passed as the only variadic argument.
 */
private object LinuxNative {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = linker.defaultLookup()
    private val handles = ConcurrentHashMap<String, MethodHandle>()

    private val OPEN_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
    )
    private val IOCTL_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
    )
    private val MMAP_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
    )
    private val MUNMAP_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )
    private val POLL_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
    )
    private val CLOSE_DESCRIPTOR = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    private val ERRNO_DESCRIPTOR = FunctionDescriptor.of(ValueLayout.ADDRESS)
    private val STRERROR_DESCRIPTOR = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
    )

    private val openHandle: MethodHandle =
        downcall("open", OPEN_DESCRIPTOR, arrayOf(Linker.Option.firstVariadicArg(2)))
    private val ioctlHandle: MethodHandle =
        downcall("ioctl", IOCTL_DESCRIPTOR, arrayOf(Linker.Option.firstVariadicArg(2)))
    private val mmapHandle: MethodHandle = downcall("mmap", MMAP_DESCRIPTOR)
    private val munmapHandle: MethodHandle = downcall("munmap", MUNMAP_DESCRIPTOR)
    private val pollHandle: MethodHandle = downcall("poll", POLL_DESCRIPTOR)
    private val closeHandle: MethodHandle = downcall("close", CLOSE_DESCRIPTOR)
    private val errnoHandle: MethodHandle = downcall("__errno_location", ERRNO_DESCRIPTOR)
    private val strerrorHandle: MethodHandle = downcall("strerror", STRERROR_DESCRIPTOR)

    private fun downcall(
        name: String,
        descriptor: FunctionDescriptor,
        options: Array<Linker.Option> = emptyArray(),
    ): MethodHandle = handles.computeIfAbsent("$name|$descriptor|${options.size}") {
        val symbol = lookup.find(name).orElse(null)
            ?: throw CameraUnsupportedException("the C library symbol $name is not available")
        linker.downcallHandle(symbol, descriptor, *options)
    }

    fun open(path: String): Int {
        val arena = Arena.ofConfined()
        try {
            val arguments = listOf(arena.allocateFrom(path), V4l2.O_RDWR, 0)
            return openHandle.invokeWithArguments(arguments) as Int
        } finally {
            arena.close()
        }
    }

    fun ioctl(descriptor: Int, request: Long, argument: MemorySegment): Int =
        ioctlHandle.invokeWithArguments(listOf(descriptor, request, argument)) as Int

    fun mmap(length: Long, prot: Int, flags: Int, fd: Int, offset: Long): MemorySegment =
        mmapHandle.invokeWithArguments(
            listOf(MemorySegment.NULL, length, prot, flags, fd, offset),
        ) as MemorySegment

    fun munmap(address: Long, length: Int) {
        if (address == 0L) return
        runCatching {
            munmapHandle.invokeWithArguments(
                listOf(MemorySegment.ofAddress(address), length.toLong()),
            )
        }
    }

    fun poll(descriptor: MemorySegment, count: Long, timeoutMillis: Int): Int =
        pollHandle.invokeWithArguments(listOf(descriptor, count, timeoutMillis)) as Int

    fun close(descriptor: Int) {
        if (descriptor < 0) return
        runCatching { closeHandle.invokeWithArguments(listOf(descriptor)) }
    }

    fun lastErrno(): Int {
        val pointer = errnoHandle.invokeWithArguments(emptyList<Any?>()) as MemorySegment
        return pointer.reinterpret(4L).get(ValueLayout.JAVA_INT, 0L)
    }

    fun errorText(code: Int): String {
        val pointer = strerrorHandle.invokeWithArguments(listOf(code)) as MemorySegment
        if (pointer.address() == 0L) return "errno=$code"
        return pointer.reinterpret(256L).getString(0)
    }

    fun lastError(): String = errorText(lastErrno())
}

/**
 * V4L2 constants, struct layouts and field offsets for the 64-bit Linux ABI.
 *
 * The layouts are explicit (including the padding the C compiler inserts) so
 * their sizes match the ones encoded in the ioctl request numbers:
 * `v4l2_capability` 104, `v4l2_format` 208, `v4l2_buffer` 88,
 * `v4l2_requestbuffers` 20, `v4l2_fmtdesc` 64, `v4l2_frmsizeenum` 44,
 * `v4l2_frmivalenum` 52.
 */
private object V4l2 {
    const val MAX_DEVICES = 64
    const val MAX_FORMATS = 64
    const val MAX_SIZES = 128
    const val MAX_INTERVALS = 128

    const val O_RDWR = 2
    const val PROT_READ = 1
    const val PROT_WRITE = 2
    const val MAP_SHARED = 1
    const val POLLIN = 0x0001
    const val POLLERR = 0x0008
    const val POLLNVAL = 0x0020
    const val EINTR = 4
    const val EAGAIN = 11

    const val VIDIOC_QUERYCAP = 0x80685600L
    const val VIDIOC_ENUM_FMT = 0xC0405602L
    const val VIDIOC_S_FMT = 0xC0D05605L
    const val VIDIOC_REQBUFS = 0xC0145608L
    const val VIDIOC_QUERYBUF = 0xC0585609L
    const val VIDIOC_QBUF = 0xC058560FL
    const val VIDIOC_DQBUF = 0xC0585611L
    const val VIDIOC_STREAMON = 0x40045612L
    const val VIDIOC_STREAMOFF = 0x40045613L
    const val VIDIOC_ENUM_FRAMESIZES = 0xC02C564AL
    const val VIDIOC_ENUM_FRAMEINTERVALS = 0xC034564BL
    const val VIDIOC_S_PARM = 0xC0CC5616L

    const val BUF_TYPE_VIDEO_CAPTURE = 1
    const val MEMORY_MMAP = 1
    const val FIELD_ANY = 0
    const val FRMSIZE_TYPE_DISCRETE = 1
    const val FRMSIZE_TYPE_STEPWISE = 3
    const val FRMIVAL_TYPE_DISCRETE = 1
    const val FRMIVAL_TYPE_STEPWISE = 3
    const val CAP_VIDEO_CAPTURE = 1L
    const val CAP_DEVICE_CAPS_FLAG = 0x80000000L

    val PIX_FMT_YUYV = fourcc('Y', 'U', 'Y', 'V')
    val PIX_FMT_UYVY = fourcc('U', 'Y', 'V', 'Y')
    val PIX_FMT_NV12 = fourcc('N', 'V', '1', '2')
    val PIX_FMT_NV21 = fourcc('N', 'V', '2', '1')
    val PIX_FMT_YUV420 = fourcc('Y', 'U', '1', '2')

    val COMMON_SIZES = listOf(
        640 to 480,
        800 to 600,
        1024 to 768,
        1280 to 720,
        1280 to 960,
        1600 to 1200,
        1920 to 1080,
        2560 to 1440,
        3840 to 2160,
    )
    val COMMON_FRAME_RATES = intArrayOf(15, 24, 25, 30, 60, 120)

    // struct v4l2_capability (104 bytes)
    val CAPABILITY_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_BYTE).withName("driver"),
        MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName("card"),
        MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName("bus_info"),
        ValueLayout.JAVA_INT.withName("version"),
        ValueLayout.JAVA_INT.withName("capabilities"),
        ValueLayout.JAVA_INT.withName("device_caps"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("reserved"),
    )
    val CAP_DRIVER: Long = CAPABILITY_LAYOUT.byteOffset(PathElement.groupElement("driver"))
    val CAP_CARD: Long = CAPABILITY_LAYOUT.byteOffset(PathElement.groupElement("card"))
    val CAP_BUS_INFO: Long = CAPABILITY_LAYOUT.byteOffset(PathElement.groupElement("bus_info"))
    val CAP_CAPABILITIES: Long = CAPABILITY_LAYOUT.byteOffset(PathElement.groupElement("capabilities"))
    val CAP_DEVICE_CAPS: Long = CAPABILITY_LAYOUT.byteOffset(PathElement.groupElement("device_caps"))

    // struct v4l2_streamparm (204 bytes): type + 200-byte parm union
    val STREAMPARM_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.sequenceLayout(200, ValueLayout.JAVA_BYTE).withName("parm"),
    )
    const val STREAMPARM_TYPE = 0L
    const val STREAMPARM_TIME_NUMERATOR = 12L
    const val STREAMPARM_TIME_DENOMINATOR = 16L

    // struct v4l2_fmtdesc (64 bytes)
    val FMTDESC_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("index"),
        ValueLayout.JAVA_INT.withName("type"),
        ValueLayout.JAVA_INT.withName("flags"),
        MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName("description"),
        ValueLayout.JAVA_INT.withName("pixelformat"),
        ValueLayout.JAVA_INT.withName("mbus_code"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("reserved"),
    )
    val FMTDESC_INDEX: Long = FMTDESC_LAYOUT.byteOffset(PathElement.groupElement("index"))
    val FMTDESC_TYPE: Long = FMTDESC_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val FMTDESC_PIXELFORMAT: Long = FMTDESC_LAYOUT.byteOffset(PathElement.groupElement("pixelformat"))

    // struct v4l2_frmsizeenum (44 bytes); `value` is the 24-byte union
    val FRMSIZE_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("index"),
        ValueLayout.JAVA_INT.withName("pixel_format"),
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_INT).withName("value"),
        MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("reserved"),
    )
    val FRMSIZE_INDEX: Long = FRMSIZE_LAYOUT.byteOffset(PathElement.groupElement("index"))
    val FRMSIZE_PIXEL_FORMAT: Long = FRMSIZE_LAYOUT.byteOffset(PathElement.groupElement("pixel_format"))
    val FRMSIZE_TYPE: Long = FRMSIZE_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val FRMSIZE_VALUE: Long = FRMSIZE_LAYOUT.byteOffset(PathElement.groupElement("value"))

    // struct v4l2_frmivalenum (52 bytes); `value` is the 24-byte union
    val FRMIVAL_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("index"),
        ValueLayout.JAVA_INT.withName("pixel_format"),
        ValueLayout.JAVA_INT.withName("width"),
        ValueLayout.JAVA_INT.withName("height"),
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_INT).withName("value"),
        MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("reserved"),
    )
    val FRMIVAL_INDEX: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("index"))
    val FRMIVAL_PIXEL_FORMAT: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("pixel_format"))
    val FRMIVAL_WIDTH: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("width"))
    val FRMIVAL_HEIGHT: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("height"))
    val FRMIVAL_TYPE: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val FRMIVAL_VALUE: Long = FRMIVAL_LAYOUT.byteOffset(PathElement.groupElement("value"))

    // struct v4l2_pix_format (48 bytes), the capture member of v4l2_format's union
    private val PIX_FORMAT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("width"),
        ValueLayout.JAVA_INT.withName("height"),
        ValueLayout.JAVA_INT.withName("pixelformat"),
        ValueLayout.JAVA_INT.withName("field"),
        ValueLayout.JAVA_INT.withName("bytesperline"),
        ValueLayout.JAVA_INT.withName("sizeimage"),
        ValueLayout.JAVA_INT.withName("colorspace"),
        ValueLayout.JAVA_INT.withName("priv"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("ycbcr_enc"),
        ValueLayout.JAVA_INT.withName("quantization"),
        ValueLayout.JAVA_INT.withName("xfer_func"),
    )

    // struct v4l2_format (208 bytes): the union is 200 bytes, aligned to 8
    val FORMAT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.paddingLayout(4),
        PIX_FORMAT_LAYOUT.withName("pix"),
        MemoryLayout.sequenceLayout(152, ValueLayout.JAVA_BYTE).withName("reserved"),
    )
    val FORMAT_TYPE: Long = FORMAT_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val FORMAT_PIX: Long = FORMAT_LAYOUT.byteOffset(PathElement.groupElement("pix"))
    val PIX_WIDTH: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("width"))
    val PIX_HEIGHT: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("height"))
    val PIX_PIXEL_FORMAT: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("pixelformat"))
    val PIX_FIELD: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("field"))
    val PIX_BYTESPERLINE: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("bytesperline"))
    val PIX_SIZEIMAGE: Long = PIX_FORMAT_LAYOUT.byteOffset(PathElement.groupElement("sizeimage"))

    // struct v4l2_requestbuffers (20 bytes)
    val REQUEST_BUFFERS_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("count"),
        ValueLayout.JAVA_INT.withName("type"),
        ValueLayout.JAVA_INT.withName("memory"),
        ValueLayout.JAVA_INT.withName("capabilities"),
        ValueLayout.JAVA_BYTE.withName("flags"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("reserved"),
    )
    val REQ_COUNT: Long = REQUEST_BUFFERS_LAYOUT.byteOffset(PathElement.groupElement("count"))
    val REQ_TYPE: Long = REQUEST_BUFFERS_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val REQ_MEMORY: Long = REQUEST_BUFFERS_LAYOUT.byteOffset(PathElement.groupElement("memory"))

    // struct v4l2_buffer (88 bytes)
    private val TIMEVAL_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("tv_sec"),
        ValueLayout.JAVA_LONG.withName("tv_usec"),
    )
    private val TIMECODE_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("type"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_BYTE.withName("frames"),
        ValueLayout.JAVA_BYTE.withName("seconds"),
        ValueLayout.JAVA_BYTE.withName("minutes"),
        ValueLayout.JAVA_BYTE.withName("hours"),
        MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_BYTE).withName("userbits"),
    )
    val BUFFER_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("index"),
        ValueLayout.JAVA_INT.withName("type"),
        ValueLayout.JAVA_INT.withName("bytesused"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("field"),
        MemoryLayout.paddingLayout(4),
        TIMEVAL_LAYOUT.withName("timestamp"),
        TIMECODE_LAYOUT.withName("timecode"),
        ValueLayout.JAVA_INT.withName("sequence"),
        ValueLayout.JAVA_INT.withName("memory"),
        ValueLayout.JAVA_LONG.withName("m"),
        ValueLayout.JAVA_INT.withName("length"),
        ValueLayout.JAVA_INT.withName("reserved2"),
        ValueLayout.JAVA_INT.withName("request_fd"),
        MemoryLayout.paddingLayout(4),
    )
    val BUFFER_INDEX: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("index"))
    val BUFFER_TYPE: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("type"))
    val BUFFER_BYTESUSED: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("bytesused"))
    val BUFFER_MEMORY: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("memory"))
    val BUFFER_TIMESTAMP: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("timestamp"))
    val BUFFER_TIMESTAMP_USEC: Long = BUFFER_LAYOUT.byteOffset(
        PathElement.groupElement("timestamp"),
        PathElement.groupElement("tv_usec"),
    )
    val BUFFER_M: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("m"))
    val BUFFER_LENGTH: Long = BUFFER_LAYOUT.byteOffset(PathElement.groupElement("length"))

    // struct pollfd (8 bytes)
    val POLLFD_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("fd"),
        ValueLayout.JAVA_SHORT.withName("events"),
        ValueLayout.JAVA_SHORT.withName("revents"),
    )
    val POLLFD_FD: Long = POLLFD_LAYOUT.byteOffset(PathElement.groupElement("fd"))
    val POLLFD_EVENTS: Long = POLLFD_LAYOUT.byteOffset(PathElement.groupElement("events"))
    val POLLFD_REVENTS: Long = POLLFD_LAYOUT.byteOffset(PathElement.groupElement("revents"))
}
