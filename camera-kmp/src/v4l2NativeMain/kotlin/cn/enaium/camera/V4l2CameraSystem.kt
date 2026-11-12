@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import cn.enaium.camera.v4l2.VIDIOC_DQBUF
import cn.enaium.camera.v4l2.VIDIOC_ENUM_FMT
import cn.enaium.camera.v4l2.VIDIOC_ENUM_FRAMEINTERVALS
import cn.enaium.camera.v4l2.VIDIOC_ENUM_FRAMESIZES
import cn.enaium.camera.v4l2.VIDIOC_QBUF
import cn.enaium.camera.v4l2.VIDIOC_QUERYBUF
import cn.enaium.camera.v4l2.VIDIOC_QUERYCAP
import cn.enaium.camera.v4l2.VIDIOC_REQBUFS
import cn.enaium.camera.v4l2.VIDIOC_S_FMT
import cn.enaium.camera.v4l2.VIDIOC_S_PARM
import cn.enaium.camera.v4l2.VIDIOC_STREAMOFF
import cn.enaium.camera.v4l2.VIDIOC_STREAMON
import cn.enaium.camera.v4l2.V4L2_BUF_TYPE_VIDEO_CAPTURE
import cn.enaium.camera.v4l2.V4L2_CAP_DEVICE_CAPS
import cn.enaium.camera.v4l2.V4L2_CAP_VIDEO_CAPTURE
import cn.enaium.camera.v4l2.V4L2_FIELD_ANY
import cn.enaium.camera.v4l2.V4L2_FRMIVAL_TYPE_DISCRETE
import cn.enaium.camera.v4l2.V4L2_FRMIVAL_TYPE_STEPWISE
import cn.enaium.camera.v4l2.V4L2_FRMSIZE_TYPE_DISCRETE
import cn.enaium.camera.v4l2.V4L2_FRMSIZE_TYPE_STEPWISE
import cn.enaium.camera.v4l2.V4L2_MEMORY_MMAP
import cn.enaium.camera.v4l2.V4L2_PIX_FMT_NV12
import cn.enaium.camera.v4l2.V4L2_PIX_FMT_NV21
import cn.enaium.camera.v4l2.V4L2_PIX_FMT_UYVY
import cn.enaium.camera.v4l2.V4L2_PIX_FMT_YUV420
import cn.enaium.camera.v4l2.V4L2_PIX_FMT_YUYV
import cn.enaium.camera.v4l2.v4l2_buffer
import cn.enaium.camera.v4l2.v4l2_capability
import cn.enaium.camera.v4l2.v4l2_format
import cn.enaium.camera.v4l2.v4l2_fmtdesc
import cn.enaium.camera.v4l2.v4l2_frmivalenum
import cn.enaium.camera.v4l2.v4l2_frmsizeenum
import cn.enaium.camera.v4l2.v4l2_requestbuffers
import cn.enaium.camera.v4l2.v4l2_streamparm
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror
import platform.posix.usleep
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * Linux and Android Native capture through V4L2.
 *
 * The same implementation is compiled into the Linux targets and the Android
 * Native targets: both expose cameras as `/dev/video*`, and Android's NDK
 * sysroot ships the kernel header used by the cinterop. UVC cameras are
 * ordinary V4L2 devices, selected by their USB bus info.
 */
internal class V4l2CameraSystem(
    private val backendKind: CameraBackend = CameraBackend.V4L2,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "V4L2"

    override fun cameras(): List<CameraInfo> {
        val result = ArrayList<CameraInfo>()
        for (index in 0 until MAX_DEVICES) {
            val path = "/dev/video$index"
            val device = queryDevice(path) ?: continue
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
            ?: throw CameraException("no V4L2 camera is available")
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        V4l2CameraSession(camera, config)

    override fun close() = Unit

    private fun queryDevice(path: String): V4l2Device? {
        val fd = open(path, O_RDWR)
        if (fd < 0) return null
        try {
            val info = memScoped {
                val caps = alloc<v4l2_capability>()
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_QUERYCAP), caps.ptr) < 0) return null
                var flags = caps.capabilities
                if (flags.toInt() and V4L2_CAP_DEVICE_CAPS.toInt() != 0) flags = caps.device_caps
                if (flags.toInt() and V4L2_CAP_VIDEO_CAPTURE == 0) return null
                Triple(
                    caps.driver.reinterpret<ByteVar>().toKString(),
                    caps.card.reinterpret<ByteVar>().toKString(),
                    caps.bus_info.reinterpret<ByteVar>().toKString(),
                )
            }
            val driver = info.first
            val card = info.second
            val bus = info.third
            val transport = when {
                bus.contains("usb", ignoreCase = true) || driver.contains("uvc", ignoreCase = true) -> CameraTransport.USB
                bus.contains("platform", ignoreCase = true) -> CameraTransport.INTERNAL
                else -> CameraTransport.UNKNOWN
            }
            return V4l2Device(
                path = path,
                name = card.ifBlank { "V4L2 camera ${path.substringAfterLast('/')}" },
                transport = transport,
                formats = enumerateFormats(fd),
            )
        } finally {
            close(fd)
        }
    }

    private fun enumerateFormats(fd: Int): List<CameraFormat> {
        val formats = ArrayList<CameraFormat>()
        var formatIndex = 0u
        while (formatIndex < 64u) {
            val description = memScoped {
                val desc = alloc<v4l2_fmtdesc>()
                desc.index = formatIndex
                desc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_ENUM_FMT), desc.ptr) < 0) {
                    // ENUM_FMT uses EINVAL for "no more formats"; keep the
                    // formats collected so far instead of discarding them.
                    return formats
                }
                val pixelFormat = desc.pixelformat
                mapPixelFormat(pixelFormat)
            } ?: run {
                formatIndex++
                continue
            }
            val native = description
            val pixelFormat = when (native) {
                PixelFormat.YUY2 -> V4L2_PIX_FMT_YUYV
                PixelFormat.UYVY -> V4L2_PIX_FMT_UYVY
                PixelFormat.NV12 -> V4L2_PIX_FMT_NV12
                PixelFormat.NV21 -> V4L2_PIX_FMT_NV21
                PixelFormat.I420 -> V4L2_PIX_FMT_YUV420
                else -> error("unsupported V4L2 pixel format $native")
            }
            for (size in enumerateSizes(fd, pixelFormat)) {
                val rates = enumerateFrameRates(fd, pixelFormat, size.first, size.second)
                val fpsList = rates.ifEmpty { listOf(CameraFormat.DEFAULT_FRAME_RATE) }
                for (fps in fpsList) {
                    formats += CameraFormat(size.first, size.second, native, fps)
                }
            }
            formatIndex++
        }
        return formats.distinct()
    }

    private fun enumerateSizes(fd: Int, pixelFormat: UInt): List<Pair<Int, Int>> {
        val sizes = LinkedHashSet<Pair<Int, Int>>()
        var index = 0u
        while (index < 128u) {
            memScoped {
                val description = alloc<v4l2_frmsizeenum>()
                description.index = index
                description.pixel_format = pixelFormat
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_ENUM_FRAMESIZES), description.ptr) < 0) {
                    return sizes.toList()
                }
                when (description.type) {
                    V4L2_FRMSIZE_TYPE_DISCRETE -> {
                        val width = description.discrete.width.toInt()
                        val height = description.discrete.height.toInt()
                        if (width > 0 && height > 0) sizes += width to height
                    }

                    V4L2_FRMSIZE_TYPE_STEPWISE -> {
                        for ((width, height) in COMMON_SIZES) {
                            val step = description.stepwise
                            if (width in step.min_width.toInt()..step.max_width.toInt() &&
                                height in step.min_height.toInt()..step.max_height.toInt()
                            ) {
                                sizes += width to height
                            }
                        }
                    }

                    else -> Unit
                }
            }
            index++
        }
        return sizes.toList()
    }

    private fun enumerateFrameRates(fd: Int, pixelFormat: UInt, width: Int, height: Int): List<Int> {
        val rates = LinkedHashSet<Int>()
        var index = 0u
        while (index < 128u) {
            memScoped {
                val description = alloc<v4l2_frmivalenum>()
                description.index = index
                description.pixel_format = pixelFormat
                description.width = width.toUInt()
                description.height = height.toUInt()
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_ENUM_FRAMEINTERVALS), description.ptr) < 0) {
                    return rates.toList()
                }
                when (description.type) {
                    V4L2_FRMIVAL_TYPE_DISCRETE -> {
                        val numerator = description.discrete.numerator.toInt()
                        val denominator = description.discrete.denominator.toInt()
                        if (denominator > 0) rates += ((numerator + denominator / 2) / denominator).coerceAtLeast(1)
                    }

                    V4L2_FRMIVAL_TYPE_STEPWISE -> {
                        val step = description.stepwise
                        val minNum = step.min.numerator.toInt()
                        val minDen = step.min.denominator.toInt().coerceAtLeast(1)
                        val maxNum = step.max.numerator.toInt()
                        val maxDen = step.max.denominator.toInt().coerceAtLeast(1)
                        val minFps = ((maxDen + maxNum / 2) / maxNum.coerceAtLeast(1)).coerceAtLeast(1)
                        val maxFps = ((minDen + minNum / 2) / minNum.coerceAtLeast(1)).coerceAtLeast(minFps)
                        for (candidate in COMMON_FRAME_RATES) if (candidate in minFps..maxFps) rates += candidate
                    }

                    else -> Unit
                }
            }
            index++
        }
        return rates.toList()
    }

    private data class V4l2Device(
        val path: String,
        val name: String,
        val transport: CameraTransport,
        val formats: List<CameraFormat>,
    )

    private companion object {
        const val MAX_DEVICES = 64
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
    }
}

private class V4l2MappedBuffer(
    val pointer: COpaquePointer,
    val length: Int,
)

private class DequeuedV4l2Buffer(
    val index: Int,
    val length: Int,
    val timestampNanos: Long,
)

private class V4l2CameraSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, v4l2BaseFormat(camera, config)) {

    private var fd: Int = -1
    private var buffers: List<V4l2MappedBuffer> = emptyList()
    private var nativeFormat: CameraFormat = v4l2BaseFormat(camera, config)
    private var worker: Worker? = null
    private var future: Future<Unit>? = null
    private var started = false
    private var captureStride: Int = 0

    override fun startCapture() {
        val device = V4l2CameraSystem().cameras().firstOrNull { it.id == camera.id }
            ?: throw CameraException("V4L2 camera ${camera.id} is no longer attached")
        val native = selectNativeFormat(device, config)
        fd = open(device.id, O_RDWR)
        if (fd < 0) throw CameraException("cannot open ${device.id}: ${lastError()}")

        try {
            val requested = mapToV4l2(native.pixelFormat)
                ?: throw CameraException("unsupported V4L2 pixel format ${native.pixelFormat}")
            val actual = memScoped {
                val format = alloc<v4l2_format>()
                format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
                format.fmt.pix.width = native.width.toUInt()
                format.fmt.pix.height = native.height.toUInt()
                format.fmt.pix.pixelformat = requested
                format.fmt.pix.field = V4L2_FIELD_ANY
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_S_FMT), format.ptr) < 0) {
                    throw CameraException("VIDIOC_S_FMT failed for ${device.id}: ${lastError()}")
                }
                val width = format.fmt.pix.width.toInt()
                val height = format.fmt.pix.height.toInt()
                val pixel = mapPixelFormat(format.fmt.pix.pixelformat)
                    ?: throw CameraException(
                        "V4L2 selected an unsupported format 0x${format.fmt.pix.pixelformat.toString(16)}",
                    )
                val stride = format.fmt.pix.bytesperline.toInt().takeIf { it > 0 } ?: width
                Pair(CameraFormat(width, height, pixel, native.frameRate), stride)
            }
            nativeFormat = actual.first
            val stride = actual.second
            val bufferCount = requestBuffers(config.bufferCount)
            mapBuffers(bufferCount)
            for (index in buffers.indices) queueBuffer(index)
            stream(on = true)
            updateNegotiatedFormat(nativeFormat)
            started = true

            captureStride = stride
            val worker = Worker.start()
            this.worker = worker
            this.future = worker.execute(TransferMode.SAFE, { this }) { session ->
                session.captureLoop()
            }
        } catch (error: Throwable) {
            releaseNative()
            throw error as? CameraException ?: CameraException("cannot start ${device.id}: ${error.message}", error)
        }
    }

    override fun stopCapture() {
        if (!started) return
        started = false
        runCatching { future?.result }
        future = null
        worker?.requestTermination()
        worker = null
        releaseNative()
    }

    private fun releaseNative() {
        if (fd >= 0) {
            runCatching { stream(on = false) }
        }
        for (buffer in buffers) {
            v4l2Munmap(buffer.pointer, buffer.length)
        }
        buffers = emptyList()
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }

    private fun captureLoop() {
        val stride = captureStride
        while (isCaptureActive()) {
            if (!v4l2Poll(fd, 0x0009, 200)) continue

            val buffer = memScoped {
                val value = alloc<v4l2_buffer>()
                value.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
                value.memory = V4L2_MEMORY_MMAP
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_DQBUF), value.ptr) < 0) {
                    if (errno == EAGAIN || errno == EINTR) return@memScoped null
                    return
                }
                // Copy out of the memScope: the kernel struct must not be read
                // after its native allocation is released.
                val timestampSeconds = value.timestamp.tv_sec
                val timestampMicros = value.timestamp.tv_usec
                DequeuedV4l2Buffer(
                    index = value.index.toInt(),
                    length = value.length.toInt(),
                    timestampNanos = if (timestampSeconds > 0) {
                        timestampSeconds * 1_000_000_000L + timestampMicros * 1_000L
                    } else {
                        monotonicNanos()
                    },
                )
            } ?: continue

            val index = buffer.index
            val mapped = buffers.getOrNull(index)
            if (mapped != null) {
                val length = minOf(
                    if (buffer.length > 0) buffer.length else mapped.length,
                    mapped.length,
                )
                val bytes = mapped.pointer.reinterpret<ByteVar>().readBytes(length)
                val frame = buildSourceFrame(bytes, nativeFormat, stride, buffer.timestampNanos)
                if (frame != null) publish(frame)
            }
            queueBuffer(index)
        }
    }

    private fun buildSourceFrame(
        bytes: ByteArray,
        format: CameraFormat,
        stride: Int,
        timestamp: Long,
    ): SourceFrame? {
        val width = format.width
        val height = format.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return when (format.pixelFormat) {
            PixelFormat.YUY2, PixelFormat.UYVY -> {
                if (bytes.size < stride * height) return null
                SourceFrame(
                    width, height, format.pixelFormat,
                    listOf(SourcePlane(bytes, 0, stride, 2, width, height)),
                    timestamp,
                )
            }

            PixelFormat.NV12, PixelFormat.NV21 -> {
                val uvStride = stride
                if (bytes.size < stride * height + uvStride * chromaHeight) return null
                SourceFrame(
                    width, height, format.pixelFormat,
                    listOf(
                        SourcePlane(bytes, 0, stride, 1, width, height),
                        SourcePlane(bytes, stride * height, uvStride, 2, chromaWidth, chromaHeight),
                    ),
                    timestamp,
                )
            }

            PixelFormat.I420 -> {
                val uvStride = (stride / 2).takeIf { it >= chromaWidth } ?: chromaWidth
                val uOffset = stride * height
                val vOffset = uOffset + uvStride * chromaHeight
                if (bytes.size < vOffset + uvStride * chromaHeight) return null
                SourceFrame(
                    width, height, PixelFormat.I420,
                    listOf(
                        SourcePlane(bytes, 0, stride, 1, width, height),
                        SourcePlane(bytes, uOffset, uvStride, 1, chromaWidth, chromaHeight),
                        SourcePlane(bytes, vOffset, uvStride, 1, chromaWidth, chromaHeight),
                    ),
                    timestamp,
                )
            }

            else -> null
        }
    }

    private fun setFrameRate(fd: Int, frameRate: Int): Int = memScoped {
        val parameter = alloc<v4l2_streamparm>()
        parameter.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
        parameter.parm.capture.timeperframe.numerator = 1u
        parameter.parm.capture.timeperframe.denominator = frameRate.toUInt()
        if (ioctlChecked(fd, ioctlRequest(VIDIOC_S_PARM), parameter.ptr) < 0) {
            frameRate
        } else {
            val numerator = parameter.parm.capture.timeperframe.numerator.toInt()
            val denominator = parameter.parm.capture.timeperframe.denominator.toInt()
            if (numerator > 0 && denominator > 0) (denominator + numerator / 2) / numerator else frameRate
        }
    }

    private fun requestBuffers(count: Int): Int {
        val actual = memScoped {
            val request = alloc<v4l2_requestbuffers>()
            request.count = count.toUInt()
            request.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
            request.memory = V4L2_MEMORY_MMAP
            if (ioctlChecked(fd, ioctlRequest(VIDIOC_REQBUFS), request.ptr) < 0) {
                throw CameraException("VIDIOC_REQBUFS failed: ${lastError()}")
            }
            request.count.toInt()
        }
        if (actual < 1) throw CameraException("V4L2 granted no capture buffers")
        return actual
    }

    private fun mapBuffers(count: Int) {
        val mapped = ArrayList<V4l2MappedBuffer>()
        for (index in 0 until count) {
            val pointer = memScoped {
                val buffer = alloc<v4l2_buffer>()
                buffer.index = index.toUInt()
                buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
                buffer.memory = V4L2_MEMORY_MMAP
                if (ioctlChecked(fd, ioctlRequest(VIDIOC_QUERYBUF), buffer.ptr) < 0) {
                    throw CameraException("VIDIOC_QUERYBUF failed: ${lastError()}")
                }
                val length = buffer.length.toInt()
                val offset = buffer.m.offset.toLong()
                val address = v4l2Mmap(length.toULong(), fd, offset)
                if (address == null || address.rawValue.toLong() == -1L) {
                    throw CameraException("mmap failed for V4L2 buffer $index: ${lastError()}")
                }
                V4l2MappedBuffer(address, length)
            }
            mapped += pointer
        }
        buffers = mapped
    }

    private fun queueBuffer(index: Int) {
        memScoped {
            val buffer = alloc<v4l2_buffer>()
            buffer.index = index.toUInt()
            buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE
            buffer.memory = V4L2_MEMORY_MMAP
            if (ioctlChecked(fd, ioctlRequest(VIDIOC_QBUF), buffer.ptr) < 0) {
                throw CameraException("VIDIOC_QBUF failed: ${lastError()}")
            }
        }
    }

    private fun stream(on: Boolean) {
        memScoped {
            val type = alloc<UIntVar>()
            type.value = V4L2_BUF_TYPE_VIDEO_CAPTURE
            val request = if (on) VIDIOC_STREAMON else VIDIOC_STREAMOFF
            ioctlChecked(fd, ioctlRequest(request), type.ptr)
        }
    }
}

private fun v4l2BaseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    return camera.bestFormat(
        config.format.width,
        config.format.height,
        null,
        config.format.frameRate,
    ) ?: CameraFormat(config.format.width, config.format.height, PixelFormat.NV12, config.format.frameRate)
}

private fun selectNativeFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val requested = config.format.pixelFormat
    val direct = camera.supportedFormats.filter { it.pixelFormat == requested }
    val candidates = when {
        direct.isNotEmpty() -> direct
        else -> camera.supportedFormats
    }
    val targetPixels = config.format.width.toLong() * config.format.height
    val targetAspect = config.format.width.toDouble() / config.format.height
    return candidates.minByOrNull { mode ->
        val aspect = kotlin.math.abs(mode.width.toDouble() / mode.height - targetAspect)
        val size = kotlin.math.abs(mode.width.toLong() * mode.height - targetPixels)
        (aspect * 1_000_000).toLong() + size
    } ?: throw CameraException("${camera.name} advertised no usable V4L2 format")
}

private fun mapPixelFormat(format: UInt): PixelFormat? = when (format) {
    V4L2_PIX_FMT_YUYV -> PixelFormat.YUY2
    V4L2_PIX_FMT_UYVY -> PixelFormat.UYVY
    V4L2_PIX_FMT_NV12 -> PixelFormat.NV12
    V4L2_PIX_FMT_NV21 -> PixelFormat.NV21
    V4L2_PIX_FMT_YUV420 -> PixelFormat.I420
    else -> null
}

private fun mapToV4l2(format: PixelFormat): UInt? = when (format) {
    PixelFormat.YUY2 -> V4L2_PIX_FMT_YUYV
    PixelFormat.UYVY -> V4L2_PIX_FMT_UYVY
    PixelFormat.NV12 -> V4L2_PIX_FMT_NV12
    PixelFormat.NV21 -> V4L2_PIX_FMT_NV21
    PixelFormat.I420 -> V4L2_PIX_FMT_YUV420
    else -> null
}

/** The ioctl request constants are UInt on Android and ULong on Linux. */
private fun ioctlRequest(value: ULong): ULong = value

private fun ioctlRequest(value: UInt): ULong = value.toULong()

private fun ioctlChecked(fd: Int, request: ULong, pointer: CPointer<*>?): Int {
    var result: Int
    do {
        result = v4l2Ioctl(fd, request, pointer)
    } while (result < 0 && errno == EINTR)
    return result
}

private fun lastError(): String = strerror(errno)?.toKString() ?: "errno=$errno"
