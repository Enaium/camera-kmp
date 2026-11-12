package cn.enaium.camera

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Desktop macOS backend: AVFoundation through the Objective-C runtime.
 *
 * The device list, formats, session and sample-buffer callback mirror the
 * Kotlin/Native AVFoundation backend; only the call syntax differs. Frames are
 * copied out of the NV12 pixel buffer on AVFoundation's dispatch queue, then
 * converted/rotated by the shared processor.
 */
internal class MacCameraSystem(
    private val backendKind: CameraBackend = CameraBackend.AVFOUNDATION,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "AVFoundation"

    override fun cameras(): List<CameraInfo> = JvmNative.autoreleasePool {
        val devices = macDevices()
        val defaultId = macDefaultDeviceId()
        devices.map { device ->
            CameraInfo(
                id = JvmNative.stringValue(JvmNative.send(device.pointer, "uniqueID")),
                name = JvmNative.stringValue(JvmNative.send(device.pointer, "localizedName")),
                lensFacing = device.facing,
                sensorOrientation = 0,
                transport = device.transport,
                isDefault = device.id == defaultId,
                supportedFormats = device.formats,
            )
        }.filter { info ->
            backendKind != CameraBackend.UVC || info.transport == CameraTransport.USB
        }
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
            ?: throw CameraException("no AVFoundation camera is available")
        return MacCameraSession(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        MacCameraSession(camera, config)

    override fun close() = Unit
}

private class MacDevice(
    val pointer: MemorySegment,
    val id: String,
    val facing: CameraLensFacing,
    val transport: CameraTransport,
    val formats: List<CameraFormat>,
)

private fun macMediaType(arena: Arena): MemorySegment = JvmNative.string(arena, "vide")

private fun macDevices(): List<MacDevice> {
    val arena = Arena.ofConfined()
    return try {
        val array = JvmNative.send(
            JvmNative.objcClass("AVCaptureDevice"),
            "devicesWithMediaType:",
            macMediaType(arena),
        )
        if (array.address() == 0L) return emptyList()
        val count = JvmNative.arrayCount(array)
        val devices = ArrayList<MacDevice>(count)
        for (index in 0 until count) {
            val pointer = JvmNative.arrayGet(array, index)
            val id = JvmNative.stringValue(JvmNative.send(pointer, "uniqueID"))
            val position = JvmNative.sendLong(pointer, "position")
            val transportValue = JvmNative.sendInt(pointer, "transportType")
            val transport = when (transportValue) {
                0x626C746E -> CameraTransport.INTERNAL
                0x75736220 -> CameraTransport.USB
                0x76697274 -> CameraTransport.VIRTUAL
                else -> CameraTransport.UNKNOWN
            }
            val facing = when (position) {
                2L -> CameraLensFacing.FRONT
                1L -> CameraLensFacing.BACK
                else -> when (transport) {
                    CameraTransport.INTERNAL -> CameraLensFacing.FRONT
                    CameraTransport.USB, CameraTransport.VIRTUAL -> CameraLensFacing.EXTERNAL
                    CameraTransport.UNKNOWN -> CameraLensFacing.UNKNOWN
                }
            }
            devices += MacDevice(pointer, id, facing, transport, macFormats(pointer, arena))
        }
        devices
    } finally {
        arena.close()
    }
}

private fun macDefaultDeviceId(): String? {
    val arena = Arena.ofConfined()
    return try {
        val device = JvmNative.send(
            JvmNative.objcClass("AVCaptureDevice"),
            "defaultDeviceWithMediaType:",
            macMediaType(arena),
        )
        if (device.address() == 0L) null else JvmNative.stringValue(JvmNative.send(device, "uniqueID"))
    } finally {
        arena.close()
    }
}

private val CM_TIME_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_LONG.withName("value"),
    ValueLayout.JAVA_INT.withName("timescale"),
    ValueLayout.JAVA_INT.withName("flags"),
    ValueLayout.JAVA_LONG.withName("epoch"),
)

private val CM_VIDEO_DIMENSIONS_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_INT.withName("width"),
    ValueLayout.JAVA_INT.withName("height"),
)

private fun macFormats(device: MemorySegment, arena: Arena): List<CameraFormat> {
    val array = JvmNative.send(device, "formats")
    if (array.address() == 0L) return emptyList()
    val modes = LinkedHashSet<CameraFormat>()
    val count = JvmNative.arrayCount(array)
    for (index in 0 until count) {
        val format = JvmNative.arrayGet(array, index)
        val description = JvmNative.send(format, "formatDescription")
        if (description.address() == 0L) continue
        val dimensions = JvmNative.rawStruct(
            "CMVideoFormatDescriptionGetDimensions",
            CM_VIDEO_DIMENSIONS_LAYOUT,
            ValueLayout.ADDRESS,
            arena = arena,
            args = listOf(description),
        )
        val width = dimensions.get(ValueLayout.JAVA_INT, 0)
        val height = dimensions.get(ValueLayout.JAVA_INT, 4)
        if (width <= 0 || height <= 0) continue
        val ranges = JvmNative.send(format, "videoSupportedFrameRateRanges")
        val rateCount = if (ranges.address() == 0L) 0 else JvmNative.arrayCount(ranges)
        val rates = LinkedHashSet<Int>()
        for (rangeIndex in 0 until rateCount) {
            val range = JvmNative.arrayGet(ranges, rangeIndex)
            val min = JvmNative.sendDouble(range, "minFrameRate").toInt().coerceAtLeast(1)
            val max = JvmNative.sendDouble(range, "maxFrameRate").toInt().coerceAtLeast(min)
            for (candidate in COMMON_FRAME_RATES) if (candidate in min..max) rates += candidate
            rates += max
        }
        if (rates.isEmpty()) rates += CameraFormat.DEFAULT_FRAME_RATE
        for (rate in rates) {
            modes += CameraFormat(width, height, PixelFormat.NV12, rate)
        }
    }
    return modes.toList()
}

private val COMMON_FRAME_RATES = intArrayOf(15, 24, 25, 30, 60, 120, 240)

private class MacCameraSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, macBaseFormat(camera, config)) {

    private var session: MemorySegment = MemorySegment.NULL
    private var output: MemorySegment = MemorySegment.NULL
    private var delegate: MemorySegment = MemorySegment.NULL
    private var queue: MemorySegment = MemorySegment.NULL
    private var started = false

    override fun startCapture() {
        val devices = macDevices()
        val device = devices.firstOrNull { it.id == camera.id }
            ?: throw CameraException("AVFoundation camera ${camera.id} is no longer attached")
        val arena = Arena.ofConfined()
        try {
            val selected = selectFormat(device, arena, config.format.width, config.format.height)
            val input = createInput(device.pointer, arena)
            val sessionObject = JvmNative.send(JvmNative.objcClass("AVCaptureSession"), "new")
            val outputObject = JvmNative.send(JvmNative.objcClass("AVCaptureVideoDataOutput"), "new")
            val delegateObject = MacCaptureCallback.createDelegate()
            val queueObject = createQueue(arena)

            JvmNative.sendVoid(sessionObject, "beginConfiguration")
            try {
                if (!JvmNative.sendBool(sessionObject, "canAddInput:", input)) {
                    throw CameraException("AVFoundation cannot add the input of ${camera.name}")
                }
                JvmNative.sendVoid(sessionObject, "addInput:", input)

                JvmNative.sendVoid(outputObject, "setAlwaysDiscardsLateVideoFrames:", true)
                setVideoSettings(outputObject, selected.width, selected.height, arena)
                JvmNative.sendVoid(outputObject, "setSampleBufferDelegate:queue:", delegateObject, queueObject)
                if (!JvmNative.sendBool(sessionObject, "canAddOutput:", outputObject)) {
                    throw CameraException("AVFoundation cannot add a video data output")
                }
                JvmNative.sendVoid(sessionObject, "addOutput:", outputObject)

                configureDevice(device.pointer, selected, config.format.frameRate, arena)
                JvmNative.sendVoid(sessionObject, "commitConfiguration")

                session = sessionObject
                output = outputObject
                delegate = delegateObject
                queue = queueObject
                MacCaptureCallback.register(delegateObject, this)
                updateNegotiatedFormat(
                    CameraFormat(
                        selected.width,
                        selected.height,
                        PixelFormat.NV12,
                        selected.frameRate,
                    ),
                )
                JvmNative.sendVoid(sessionObject, "startRunning")
                started = true
            } catch (error: Throwable) {
                JvmNative.sendVoid(sessionObject, "commitConfiguration")
                runCatching { MacCaptureCallback.unregister(delegateObject) }
                JvmNative.release(delegateObject)
                JvmNative.release(outputObject)
                JvmNative.release(sessionObject)
                throw error
            }
        } finally {
            arena.close()
        }
    }

    override fun stopCapture() {
        if (!started) return
        started = false
        MacCaptureCallback.unregister(delegate)
        JvmNative.sendVoid(output, "setSampleBufferDelegate:queue:", MemorySegment.NULL, MemorySegment.NULL)
        JvmNative.sendVoid(session, "stopRunning")
        JvmNative.release(delegate)
        JvmNative.release(queue)
        JvmNative.release(output)
        JvmNative.release(session)
        delegate = MemorySegment.NULL
        queue = MemorySegment.NULL
        output = MemorySegment.NULL
        session = MemorySegment.NULL
    }

    override fun release() {
        stopCapture()
    }

    private fun createInput(device: MemorySegment, arena: Arena): MemorySegment {
        val errorSlot = arena.allocate(ValueLayout.ADDRESS)
        errorSlot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
        val input = JvmNative.raw(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(
                JvmNative.objcClass("AVCaptureDeviceInput"),
                JvmNative.selector("deviceInputWithDevice:error:"),
                device,
                errorSlot,
            ),
        ) as MemorySegment
        if (input.address() == 0L) {
            val error = errorSlot.get(ValueLayout.ADDRESS, 0)
            throw CameraException("AVFoundation rejected the camera: ${JvmNative.errorMessage(error)}")
        }
        return input
    }

    private fun createQueue(arena: Arena): MemorySegment = JvmNative.raw(
        "dispatch_queue_create",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(arena.allocateFrom("cn.enaium.camera.jvm"), MemorySegment.NULL),
    ) as MemorySegment

    private fun setVideoSettings(output: MemorySegment, width: Int, height: Int, arena: Arena) {
        val dictionary = JvmNative.send(JvmNative.objcClass("NSMutableDictionary"), "dictionary")
        fun put(key: String, value: MemorySegment) {
            JvmNative.sendVoid(dictionary, "setObject:forKey:", value, JvmNative.string(arena, key))
        }
        put(
            "PixelFormatType",
            JvmNative.sendObjectInt(JvmNative.objcClass("NSNumber"), "numberWithInt:", PIXEL_FORMAT_420V),
        )
        put("Width", JvmNative.sendObjectInt(JvmNative.objcClass("NSNumber"), "numberWithInt:", width))
        put("Height", JvmNative.sendObjectInt(JvmNative.objcClass("NSNumber"), "numberWithInt:", height))
        JvmNative.sendVoid(output, "setVideoSettings:", dictionary)
    }

    private fun selectFormat(
        device: MacDevice,
        arena: Arena,
        width: Int,
        height: Int,
    ): SelectedFormat {
        val array = JvmNative.send(device.pointer, "formats")
        val count = if (array.address() == 0L) 0 else JvmNative.arrayCount(array)
        var best: SelectedFormat? = null
        var bestScore = Long.MAX_VALUE
        val targetPixels = width.toLong() * height
        val targetAspect = width.toDouble() / height
        for (index in 0 until count) {
            val format = JvmNative.arrayGet(array, index)
            val description = JvmNative.send(format, "formatDescription")
            if (description.address() == 0L) continue
            val dimensions = JvmNative.rawStruct(
                "CMVideoFormatDescriptionGetDimensions",
                CM_VIDEO_DIMENSIONS_LAYOUT,
                ValueLayout.ADDRESS,
                arena = arena,
                args = listOf(description),
            )
            val w = dimensions.get(ValueLayout.JAVA_INT, 0)
            val h = dimensions.get(ValueLayout.JAVA_INT, 4)
            if (w <= 0 || h <= 0) continue
            val score = (abs(w.toDouble() / h - targetAspect) * 1_000_000).toLong() +
                abs(w.toLong() * h - targetPixels)
            if (score < bestScore) {
                val ranges = JvmNative.send(format, "videoSupportedFrameRateRanges")
                val rateCount = if (ranges.address() == 0L) 0 else JvmNative.arrayCount(ranges)
                var min = config.format.frameRate
                var max = config.format.frameRate
                if (rateCount > 0) {
                    val range = JvmNative.arrayGet(ranges, 0)
                    min = JvmNative.sendDouble(range, "minFrameRate").toInt().coerceAtLeast(1)
                    max = JvmNative.sendDouble(range, "maxFrameRate").toInt().coerceAtLeast(min)
                }
                val fps = config.format.frameRate.coerceIn(min, max)
                best = SelectedFormat(format, w, h, fps)
                bestScore = score
            }
        }
        return best ?: throw CameraException("AVFoundation camera ${camera.name} has no usable format")
    }

    private fun configureDevice(
        device: MemorySegment,
        selected: SelectedFormat,
        requestedFrameRate: Int,
        arena: Arena,
    ) {
        val errorSlot = arena.allocate(ValueLayout.ADDRESS)
        errorSlot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL)
        if (!JvmNative.sendBool(device, "lockForConfiguration:", errorSlot)) {
            val error = errorSlot.get(ValueLayout.ADDRESS, 0)
            throw CameraException("AVFoundation cannot configure the camera: ${JvmNative.errorMessage(error)}")
        }
        try {
            JvmNative.sendVoid(device, "setActiveFormat:", selected.pointer)
            val time = arena.allocate(CM_TIME_LAYOUT)
            time.set(ValueLayout.JAVA_LONG, 0, 1L)
            time.set(ValueLayout.JAVA_INT, 8, selected.frameRate)
            time.set(ValueLayout.JAVA_INT, 12, 1) // kCMTimeFlags_Valid
            time.set(ValueLayout.JAVA_LONG, 16, 0L)
            JvmNative.sendVoid(device, "setActiveVideoMinFrameDuration:", time)
            JvmNative.sendVoid(device, "setActiveVideoMaxFrameDuration:", time)
        } finally {
            JvmNative.sendVoid(device, "unlockForConfiguration")
        }
    }

    internal fun onSample(sampleBuffer: MemorySegment) {
        val pixelBuffer = JvmNative.raw(
            "CMSampleBufferGetImageBuffer",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(sampleBuffer),
        ) as MemorySegment
        if (pixelBuffer.address() == 0L) return
        JvmNative.void(
            "CVPixelBufferLockBaseAddress",
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            args = listOf(pixelBuffer, 0L),
        )
        try {
            val width = (
                JvmNative.raw(
                    "CVPixelBufferGetWidth",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    args = listOf(pixelBuffer),
                ) as Long
                ).toInt()
            val height = (
                JvmNative.raw(
                    "CVPixelBufferGetHeight",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    args = listOf(pixelBuffer),
                ) as Long
                ).toInt()
            if (width <= 0 || height <= 0) return
            val planeCount = (
                JvmNative.raw(
                    "CVPixelBufferGetPlaneCount",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    args = listOf(pixelBuffer),
                ) as Long
                ).toInt()
            if (planeCount < 2) return
            val yBase = JvmNative.raw(
                "CVPixelBufferGetBaseAddressOfPlane",
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                args = listOf(pixelBuffer, 0L),
            ) as MemorySegment
            val uvBase = JvmNative.raw(
                "CVPixelBufferGetBaseAddressOfPlane",
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                args = listOf(pixelBuffer, 1L),
            ) as MemorySegment
            if (yBase.address() == 0L || uvBase.address() == 0L) return
            val yStride = (
                JvmNative.raw(
                    "CVPixelBufferGetBytesPerRowOfPlane",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    args = listOf(pixelBuffer, 0L),
                ) as Long
                ).toInt()
            val uvStride = (
                JvmNative.raw(
                    "CVPixelBufferGetBytesPerRowOfPlane",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    args = listOf(pixelBuffer, 1L),
                ) as Long
                ).toInt()
            val chromaHeight = (
                JvmNative.raw(
                    "CVPixelBufferGetHeightOfPlane",
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    args = listOf(pixelBuffer, 1L),
                ) as Long
                ).toInt()
            val yBytes = copyPlane(yBase, yStride * height)
            val uvBytes = copyPlane(uvBase, uvStride * chromaHeight)

            val timeArena = Arena.ofConfined()
            val timestamp = try {
                val time = JvmNative.rawStruct(
                    "CMSampleBufferGetPresentationTimeStamp",
                    CM_TIME_LAYOUT,
                    ValueLayout.ADDRESS,
                    arena = timeArena,
                    args = listOf(sampleBuffer),
                )
                val seconds = time.get(ValueLayout.JAVA_LONG, 0).toDouble() /
                    time.get(ValueLayout.JAVA_INT, 8).coerceAtLeast(1)
                if (seconds.isFinite() && seconds >= 0.0) {
                    (seconds * 1_000_000_000.0).toLong()
                } else {
                    monotonicNanos()
                }
            } finally {
                timeArena.close()
            }
            publish(
                SourceFrame(
                    width = width,
                    height = height,
                    pixelFormat = PixelFormat.NV12,
                    planes = listOf(
                        SourcePlane(yBytes, 0, yStride, 1, width, height),
                        SourcePlane(uvBytes, 0, uvStride, 2, (width + 1) / 2, chromaHeight),
                    ),
                    timestampNanos = timestamp,
                ),
            )
        } finally {
            JvmNative.void(
                "CVPixelBufferUnlockBaseAddress",
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                args = listOf(pixelBuffer, 0L),
            )
        }
    }

    private fun copyPlane(base: MemorySegment, size: Int): ByteArray {
        val bytes = ByteArray(size)
        MemorySegment.copy(
            base.reinterpret(size.toLong()),
            ValueLayout.JAVA_BYTE,
            0,
            bytes,
            0,
            size,
        )
        return bytes
    }

    private data class SelectedFormat(
        val pointer: MemorySegment,
        val width: Int,
        val height: Int,
        val frameRate: Int,
    )

    private companion object {
        const val PIXEL_FORMAT_420V = 0x34323076
    }
}

private fun macBaseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val mode = camera.bestFormat(
        config.format.width,
        config.format.height,
        PixelFormat.NV12,
        config.format.frameRate,
    ) ?: CameraFormat(config.format.width, config.format.height, PixelFormat.NV12, config.format.frameRate)
    return CameraFormat(mode.width, mode.height, PixelFormat.NV12, mode.frameRate)
}

/**
 * Runtime subclass of NSObject implementing
 * `AVCaptureVideoDataOutputSampleBufferDelegate`.
 */
private object MacCaptureCallback {
    private val sessions = ConcurrentHashMap<Long, MacCameraSession>()
    private val arena = Arena.ofShared()
    private val delegateClass: MemorySegment by lazy { createClass() }

    @JvmStatic
    fun capture(
        self: MemorySegment,
        command: MemorySegment,
        output: MemorySegment,
        sampleBuffer: MemorySegment,
        connection: MemorySegment,
    ) {
        val session = sessions[self.address()] ?: return
        if (sampleBuffer.address() == 0L) return
        JvmNative.autoreleasePool {
            runCatching { session.onSample(sampleBuffer) }
        }
    }

    fun register(delegate: MemorySegment, session: MacCameraSession) {
        sessions[delegate.address()] = session
    }

    fun unregister(delegate: MemorySegment) {
        if (delegate.address() != 0L) sessions.remove(delegate.address())
    }

    fun createDelegate(): MemorySegment {
        if (delegateClass.address() == 0L) {
            throw CameraException("cannot create the AVFoundation sample-buffer delegate class")
        }
        return JvmNative.send(delegateClass, "new")
    }

    private fun createClass(): MemorySegment {
        val name = "CameraKmpAVFoundationDelegate"
        val existing = JvmNative.objcClass(name)
        if (existing.address() != 0L) return existing

        val superclass = JvmNative.objcClass("NSObject")
        val cls = JvmNative.raw(
            "objc_allocateClassPair",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            args = listOf(superclass, arena.allocateFrom(name), 0L),
        ) as MemorySegment
        if (cls.address() == 0L) throw CameraException("cannot allocate the AVFoundation delegate class")

        val protocol = JvmNative.raw(
            "objc_getProtocol",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(arena.allocateFrom("AVCaptureVideoDataOutputSampleBufferDelegate")),
        ) as MemorySegment
        if (protocol.address() != 0L) {
            JvmNative.raw(
                "class_addProtocol",
                ValueLayout.JAVA_BYTE,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                args = listOf(cls, protocol),
            )
        }

        val methodType = MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
            MemorySegment::class.java,
        )
        val method = MethodHandles.lookup().findStatic(MacCaptureCallback::class.java, "capture", methodType)
        val stub = JvmNative.linker.upcallStub(
            method,
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
            arena,
        )
        val added = JvmNative.raw(
            "class_addMethod",
            ValueLayout.JAVA_BYTE,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(
                cls,
                JvmNative.selector("captureOutput:didOutputSampleBuffer:fromConnection:"),
                stub,
                arena.allocateFrom("v@:@@@"),
            ),
        ) as Byte
        if (added.toInt() == 0) throw CameraException("cannot add the AVFoundation delegate method")

        JvmNative.void(
            "objc_registerClassPair",
            ValueLayout.ADDRESS,
            args = listOf(cls),
        )
        return cls
    }
}
