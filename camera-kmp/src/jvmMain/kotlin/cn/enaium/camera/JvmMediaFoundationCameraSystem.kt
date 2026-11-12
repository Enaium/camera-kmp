package cn.enaium.camera

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Desktop Windows backend: Media Foundation through the Foreign Function &
 * Memory API.
 *
 * Devices are enumerated with `MFEnumDeviceSources`, opened through
 * `IMFActivate::ActivateObject`, and driven by an `IMFSourceReader` that is
 * asked for an NV12 output type. The source reader runs in asynchronous mode so
 * that `stop()` can flush the stream and wait for in-flight callbacks instead
 * of leaving a worker blocked inside `ReadSample` after the camera was
 * unplugged. A dedicated control thread owns the COM apartment for the
 * lifetime of the session (device activation, media type negotiation, flush and
 * release), while samples are copied straight out of Media Foundation's worker
 * threads.
 */
internal class JvmMediaFoundationCameraSystem(
    private val backendKind: CameraBackend = CameraBackend.MEDIA_FOUNDATION,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "Media Foundation"

    private val stateLock = Any()
    private var closed = false

    init {
        if (!isWindowsHost()) {
            throw CameraUnsupportedException(
                "Media Foundation is only available on Windows, but this JVM runs on " +
                    System.getProperty("os.name"),
            )
        }
        MfRuntime.acquire()
    }

    override fun cameras(): List<CameraInfo> {
        ensureOpen()
        return withCom {
            mfDevices().use { devices ->
                devices.entries.mapIndexed { index, entry -> mfCameraInfo(entry, index) }
            }
        }
    }

    override fun defaultCamera(lensFacing: CameraLensFacing): CameraInfo? {
        val all = cameras()
        return all.firstOrNull { it.lensFacing == lensFacing }
            ?: all.firstOrNull()
    }

    override fun open(config: CameraConfig): CameraSession {
        ensureOpen()
        val all = cameras()
        val selected = config.cameraId?.let { id ->
            all.firstOrNull { it.id == id } ?: throw CameraException(
                "Media Foundation camera ${id} is not attached; call cameras() to enumerate again",
            )
        } ?: all.firstOrNull { it.lensFacing == config.lensFacing }
            ?: all.firstOrNull()
            ?: throw CameraException(
                "no Media Foundation camera is available; connect a camera and retry",
            )
        return JvmMediaFoundationCameraSession(selected, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession {
        ensureOpen()
        return JvmMediaFoundationCameraSession(camera, config)
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        MfRuntime.release()
    }

    private fun ensureOpen() {
        synchronized(stateLock) {
            if (closed) throw CameraException("the Media Foundation camera system is closed")
        }
    }
}

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")

/**
 * Runs [block] with the calling thread inside a multithreaded COM apartment.
 *
 * Media Foundation enumeration creates COM objects on the calling thread, and
 * `CoInitializeEx` must be balanced by `CoUninitialize` even when the thread was
 * already initialized (`S_FALSE`). `RPC_E_CHANGED_MODE` means the thread is
 * already in another apartment, which is usable and must not be uninitialized.
 */
private fun <T> withCom(block: () -> T): T {
    val result = MfCom.result(
        MfCom.invoke("ole32.dll", "CoInitializeEx", D_CO_INITIALIZE, MemorySegment.NULL, 0),
    )
    if (result < 0 && result != RPC_E_CHANGED_MODE) {
        throw mfFailure("cannot initialize COM for Media Foundation", result)
    }
    val owned = result >= 0
    try {
        return block()
    } finally {
        if (owned) runCatching { MfCom.invoke("ole32.dll", "CoUninitialize", D_CO_UNINITIALIZE) }
    }
}

/** Picks the native mode closest to [config] from the modes the device advertised. */
private fun mfBaseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val mode = camera.bestFormat(
        config.format.width,
        config.format.height,
        PixelFormat.NV12,
        config.format.frameRate,
    ) ?: CameraFormat(
        config.format.width,
        config.format.height,
        PixelFormat.NV12,
        config.format.frameRate,
    )
    return CameraFormat(mode.width, mode.height, PixelFormat.NV12, mode.frameRate)
}

private class MfDeviceEntry(
    val activate: MemorySegment,
    val id: String,
    val name: String,
    val symbolicLink: String?,
)

/** Owns the `IMFActivate` array returned by `MFEnumDeviceSources`. */
private class MfDeviceList(
    val entries: List<MfDeviceEntry>,
    private val array: MemorySegment,
) : AutoCloseable {
    override fun close() {
        entries.forEach { entry -> MfCom.release(entry.activate) }
        if (array.address() != 0L) {
            MfCom.invoke("ole32.dll", "CoTaskMemFree", D_CO_TASK_MEM_FREE, array)
        }
    }
}

private fun mfDevices(): MfDeviceList {
    val arena = Arena.ofConfined()
    try {
        val filterOut = arena.allocate(ValueLayout.ADDRESS)
        val filterResult = MfCom.result(
            MfCom.invoke("mfplat.dll", "MFCreateAttributes", D_MF_CREATE_ATTRIBUTES, filterOut, 1),
        )
        if (filterResult < 0) throw mfFailure("cannot create Media Foundation enumeration attributes", filterResult)
        val filter = filterOut.get(ValueLayout.ADDRESS, 0)
        if (filter.address() == 0L) {
            throw CameraException("Media Foundation returned no enumeration attribute store")
        }
        try {
            val setResult = MfCom.result(
                MfCom.call(
                    filter,
                    MF_INDEX_SET_GUID,
                    D_SET_GUID,
                    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                    MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP,
                ),
            )
            if (setResult < 0) {
                throw mfFailure("cannot request Media Foundation video capture devices", setResult)
            }

            val arrayOut = arena.allocate(ValueLayout.ADDRESS)
            val countOut = arena.allocate(ValueLayout.JAVA_INT)
            val result = MfCom.result(
                MfCom.invoke(
                    "mf.dll",
                    "MFEnumDeviceSources",
                    D_MF_ENUM_DEVICE_SOURCES,
                    filter,
                    arrayOut,
                    countOut,
                ),
            )
            if (result < 0) throw mfFailure("cannot enumerate Media Foundation cameras", result)

            val array = arrayOut.get(ValueLayout.ADDRESS, 0)
            val count = countOut.get(ValueLayout.JAVA_INT, 0)
            if (array.address() == 0L || count <= 0) {
                if (array.address() != 0L) {
                    MfCom.invoke("ole32.dll", "CoTaskMemFree", D_CO_TASK_MEM_FREE, array)
                }
                return MfDeviceList(emptyList(), MemorySegment.NULL)
            }

            val pointers = array.reinterpret(count.toLong() * 8L)
            val raw = ArrayList<MemorySegment>(count)
            for (index in 0 until count) {
                val activate = pointers.get(ValueLayout.ADDRESS, index.toLong() * 8L)
                if (activate.address() != 0L) raw += activate
            }

            val entries = ArrayList<MfDeviceEntry>(raw.size)
            val usedIds = HashSet<String>()
            try {
                raw.forEachIndexed { index, activate ->
                    val name = mfAllocatedString(activate, MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?: "Camera ${index + 1}"
                    val link = mfAllocatedString(
                        activate,
                        MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK,
                    )?.takeIf { it.isNotBlank() }
                    var id = link ?: "$name#$index"
                    if (!usedIds.add(id)) {
                        id = "$id#$index"
                        usedIds.add(id)
                    }
                    entries += MfDeviceEntry(activate, id, name, link)
                }
                return MfDeviceList(entries, array)
            } catch (error: Throwable) {
                raw.forEach { pointer -> MfCom.release(pointer) }
                MfCom.invoke("ole32.dll", "CoTaskMemFree", D_CO_TASK_MEM_FREE, array)
                throw error
            }
        } finally {
            MfCom.release(filter)
        }
    } finally {
        arena.close()
    }
}

private fun mfCameraInfo(entry: MfDeviceEntry, index: Int): CameraInfo {
    val link = entry.symbolicLink.orEmpty()
    val usb = link.contains("usb#", ignoreCase = true)
    val virtual = !usb && (
        link.contains("swd#", ignoreCase = true) ||
            link.contains("root#", ignoreCase = true)
        )
    val transport = when {
        usb -> CameraTransport.USB
        virtual -> CameraTransport.VIRTUAL
        else -> CameraTransport.UNKNOWN
    }
    val facing = when {
        usb || virtual -> CameraLensFacing.EXTERNAL
        else -> CameraLensFacing.UNKNOWN
    }
    return CameraInfo(
        id = entry.id,
        name = entry.name,
        lensFacing = facing,
        sensorOrientation = 0,
        transport = transport,
        isDefault = index == 0,
        supportedFormats = mfFormats(entry.activate),
    )
}

/** Reads a `wchar_t*` attribute allocated with `CoTaskMemAlloc`. */
private fun mfAllocatedString(attributes: MemorySegment, key: MemorySegment): String? {
    val arena = Arena.ofConfined()
    try {
        val valueOut = arena.allocate(ValueLayout.ADDRESS)
        val lengthOut = arena.allocate(ValueLayout.JAVA_INT)
        val result = MfCom.result(
            MfCom.call(
                attributes,
                MF_INDEX_GET_ALLOCATED_STRING,
                D_GET_ALLOCATED_STRING,
                key,
                valueOut,
                lengthOut,
            ),
        )
        if (result < 0) return null
        val pointer = valueOut.get(ValueLayout.ADDRESS, 0)
        if (pointer.address() == 0L) return null
        return try {
            val length = lengthOut.get(ValueLayout.JAVA_INT, 0)
            if (length <= 0) {
                ""
            } else {
                val bytes = ByteArray(length * 2)
                MemorySegment.copy(
                    pointer.reinterpret(length.toLong() * 2L),
                    ValueLayout.JAVA_BYTE,
                    0L,
                    bytes,
                    0,
                    bytes.size,
                )
                String(bytes, Charsets.UTF_16LE)
            }
        } finally {
            MfCom.invoke("ole32.dll", "CoTaskMemFree", D_CO_TASK_MEM_FREE, pointer)
        }
    } finally {
        arena.close()
    }
}

/**
 * Enumerates the native media types of one device and advertises them as NV12
 * modes, which is the layout the source reader is configured to produce.
 */
private fun mfFormats(activate: MemorySegment): List<CameraFormat> {
    val arena = Arena.ofConfined()
    try {
        val sourceOut = arena.allocate(ValueLayout.ADDRESS)
        val activateResult = MfCom.result(
            MfCom.call(
                activate,
                MF_INDEX_ACTIVATE_OBJECT,
                D_ACTIVATE_OBJECT,
                IID_IMF_MEDIA_SOURCE,
                sourceOut,
            ),
        )
        if (activateResult < 0) return emptyList()
        val source = sourceOut.get(ValueLayout.ADDRESS, 0)
        if (source.address() == 0L) return emptyList()

        val readerOut = arena.allocate(ValueLayout.ADDRESS)
        val readerResult = MfCom.result(
            MfCom.invoke(
                "mfreadwrite.dll",
                "MFCreateSourceReaderFromMediaSource",
                D_MF_CREATE_SOURCE_READER,
                source,
                MemorySegment.NULL,
                readerOut,
            ),
        )
        val reader = readerOut.get(ValueLayout.ADDRESS, 0)
        try {
            if (readerResult < 0 || reader.address() == 0L) return emptyList()
            val modes = LinkedHashSet<CameraFormat>()
            var index = 0
            while (index < 256 && modes.size < 512) {
                val typeOut = arena.allocate(ValueLayout.ADDRESS)
                val result = MfCom.result(
                    MfCom.call(
                        reader,
                        MF_INDEX_GET_NATIVE_MEDIA_TYPE,
                        D_GET_NATIVE_MEDIA_TYPE,
                        MF_FIRST_VIDEO_STREAM,
                        index,
                        typeOut,
                    ),
                )
                if (result < 0) break
                val type = typeOut.get(ValueLayout.ADDRESS, 0)
                if (type.address() == 0L) break
                try {
                    mfReadModes(type, arena, modes)
                } finally {
                    MfCom.release(type)
                }
                index++
            }
            return modes.toList()
        } finally {
            MfCom.release(reader)
            MfCom.release(source)
            runCatching { MfCom.call(activate, MF_INDEX_SHUTDOWN_OBJECT, D_SHUTDOWN_OBJECT) }
        }
    } finally {
        arena.close()
    }
}

private fun mfReadModes(type: MemorySegment, arena: Arena, modes: MutableSet<CameraFormat>) {
    val packedSize = mfReadUint64(type, arena, MF_MT_FRAME_SIZE) ?: return
    val width = (packedSize ushr 32).toInt()
    val height = packedSize.toInt()
    if (width <= 0 || height <= 0) return

    val rates = LinkedHashSet<Int>()
    mfReadUint64(type, arena, MF_MT_FRAME_RATE)?.let { packed ->
        mfFps(packed)?.let { rates += it }
    }
    if (rates.isEmpty()) {
        val minimum = mfReadUint64(type, arena, MF_MT_FRAME_RATE_RANGE_MIN)?.let { mfFps(it) }
        val maximum = mfReadUint64(type, arena, MF_MT_FRAME_RATE_RANGE_MAX)?.let { mfFps(it) }
        if (minimum != null && maximum != null) {
            for (candidate in COMMON_FRAME_RATES) {
                if (candidate in minimum..maximum) rates += candidate
            }
            rates += maximum
        }
    }
    if (rates.isEmpty()) rates += CameraFormat.DEFAULT_FRAME_RATE

    for (rate in rates) {
        modes += CameraFormat(width, height, PixelFormat.NV12, rate)
    }
}

private fun mfReadUint64(attributes: MemorySegment, arena: Arena, key: MemorySegment): Long? {
    val out = arena.allocate(ValueLayout.JAVA_LONG)
    val result = MfCom.result(
        MfCom.call(attributes, MF_INDEX_GET_UINT64, D_GET_UINT64, key, out),
    )
    return if (result >= 0) out.get(ValueLayout.JAVA_LONG, 0) else null
}

private fun mfReadUint32(attributes: MemorySegment, arena: Arena, key: MemorySegment): Int? {
    val out = arena.allocate(ValueLayout.JAVA_INT)
    val result = MfCom.result(
        MfCom.call(attributes, MF_INDEX_GET_UINT32, D_GET_UINT32, key, out),
    )
    return if (result >= 0) out.get(ValueLayout.JAVA_INT, 0) else null
}

/** `MF_MT_FRAME_RATE` packs a 32-bit numerator and denominator in one UINT64. */
private fun mfFps(packed: Long): Int? {
    val numerator = (packed ushr 32).toInt()
    val denominator = packed.toInt()
    if (numerator <= 0 || denominator <= 0) return null
    return (numerator.toDouble() / denominator).roundToInt().coerceAtLeast(1)
}

private fun mfPack(high: Int, low: Int): Long =
    (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)

private val COMMON_FRAME_RATES = intArrayOf(15, 24, 25, 30, 60, 120, 240)

/**
 * One open device.
 *
 * The control thread initializes COM and Media Foundation, negotiates NV12,
 * then idles until [stopCapture]. Samples arrive on Media Foundation worker
 * threads through [MfReaderCallback] and are copied and published from there.
 */
private class JvmMediaFoundationCameraSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, mfBaseFormat(camera, config)) {

    private val initialFormat: CameraFormat = mfBaseFormat(camera, config)
    private val readerLock = Any()

    @Volatile
    private var initFailure: Throwable? = null

    @Volatile
    private var controlThread: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var readerClosed = true

    @Volatile
    private var reader: MemorySegment = MemorySegment.NULL

    @Volatile
    private var source: MemorySegment = MemorySegment.NULL

    @Volatile
    private var attributes: MemorySegment = MemorySegment.NULL

    @Volatile
    private var activate: MemorySegment = MemorySegment.NULL

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    /** Signed stride in bytes; negative means the frame is stored bottom-up. */
    @Volatile
    private var frameStride = 0

    @Volatile
    private var callbackState: MfReaderCallback? = null

    override fun startCapture() {
        val previous = controlThread
        if (previous === Thread.currentThread()) {
            throw CameraException(
                "cannot restart Media Foundation camera ${camera.name} from its own capture thread",
            )
        }
        if (previous != null) {
            requestStop()
            try {
                previous.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            controlThread = null
        }
        stopRequested = false
        initFailure = null
        val latch = CountDownLatch(1)
        val thread = Thread({ runControlThread(latch) }, "camera-kmp-mediafoundation-${camera.name.hashCode()}")
        thread.isDaemon = true
        controlThread = thread
        thread.start()

        try {
            if (!latch.await(MF_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                requestStop()
                thread.join(1_000)
                throw CameraException(
                    "timed out opening Media Foundation camera ${camera.name}; " +
                        "close other applications that may be using it",
                )
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            requestStop()
            throw CameraException(
                "interrupted while opening Media Foundation camera ${camera.name}",
                error,
            )
        }

        val failure = initFailure
        if (failure != null) {
            try {
                thread.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw if (failure is CameraException) {
                failure
            } else {
                CameraException(
                    "failed to open Media Foundation camera ${camera.name}: ${failure.message}",
                    failure,
                )
            }
        }
    }

    override fun stopCapture() {
        requestStop()
        val thread = controlThread ?: return
        controlThread = null
        if (thread === Thread.currentThread()) return
        try {
            thread.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun release() {
        stopCapture()
    }

    private fun requestStop() {
        stopRequested = true
    }

    private fun runControlThread(latch: CountDownLatch) {
        var mediaFoundationStarted = false
        var comInitialized = false
        try {
            comInitialized = mfInitializeCom()
            MfRuntime.acquire()
            mediaFoundationStarted = true
            try {
                openReader()
                if (stopRequested) return
                startStreaming()
                if (stopRequested) return
            } catch (error: Throwable) {
                initFailure = error
                return
            } finally {
                latch.countDown()
            }
            while (!stopRequested) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (error: Throwable) {
            if (latch.count > 0) {
                initFailure = error
            } else {
                reportError(
                    CameraException(
                        "Media Foundation capture from ${camera.name} stopped: ${error.message}",
                        error,
                    ),
                )
            }
        } finally {
            latch.countDown()
            runCatching { closeReader() }
            if (mediaFoundationStarted) MfRuntime.release()
            if (comInitialized) mfUninitializeCom()
        }
    }

    private fun openReader() {
        val arena = Arena.ofConfined()
        try {
            mfDevices().use { devices ->
                val entry = devices.entries.firstOrNull { it.id == camera.id }
                    ?: throw CameraException(
                        "Media Foundation camera ${camera.name} is no longer attached",
                    )
                val sourceOut = arena.allocate(ValueLayout.ADDRESS)
                val result = MfCom.result(
                    MfCom.call(
                        entry.activate,
                        MF_INDEX_ACTIVATE_OBJECT,
                        D_ACTIVATE_OBJECT,
                        IID_IMF_MEDIA_SOURCE,
                        sourceOut,
                    ),
                )
                if (result < 0) throw mfFailure("cannot activate Media Foundation camera ${camera.name}", result)
                val activatedSource = sourceOut.get(ValueLayout.ADDRESS, 0)
                if (activatedSource.address() == 0L) {
                    throw CameraException("Media Foundation returned no media source for ${camera.name}")
                }
                source = activatedSource
                MfCom.addRef(entry.activate)
                activate = entry.activate
            }

            val callback = MfReaderCallback(this)
            callbackState = callback

            val attributesOut = arena.allocate(ValueLayout.ADDRESS)
            val attributesResult = MfCom.result(
                MfCom.invoke("mfplat.dll", "MFCreateAttributes", D_MF_CREATE_ATTRIBUTES, attributesOut, 3),
            )
            if (attributesResult < 0) throw mfFailure("cannot create Media Foundation attributes", attributesResult)
            val createdAttributes = attributesOut.get(ValueLayout.ADDRESS, 0)
            if (createdAttributes.address() == 0L) {
                throw CameraException("Media Foundation returned no attribute store")
            }
            attributes = createdAttributes

            setUnknown(createdAttributes, MF_SOURCE_READER_ASYNC_CALLBACK, callback.handle)
            setUint32(createdAttributes, MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)
            setUint32(createdAttributes, MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, 1)

            val readerOut = arena.allocate(ValueLayout.ADDRESS)
            val readerResult = MfCom.result(
                MfCom.invoke(
                    "mfreadwrite.dll",
                    "MFCreateSourceReaderFromMediaSource",
                    D_MF_CREATE_SOURCE_READER,
                    source,
                    attributes,
                    readerOut,
                ),
            )
            if (readerResult < 0) {
                throw mfFailure("cannot open Media Foundation camera ${camera.name}", readerResult)
            }
            val createdReader = readerOut.get(ValueLayout.ADDRESS, 0)
            if (createdReader.address() == 0L) {
                throw CameraException("Media Foundation returned no source reader for ${camera.name}")
            }
            reader = createdReader

            configureReader(arena)
            applyCurrentMediaType()
            synchronized(readerLock) { readerClosed = false }
        } finally {
            arena.close()
        }
    }

    private fun configureReader(arena: Arena) {
        var lastResult = 0
        for (attempt in 0..2) {
            val withSize = attempt < 2
            val withRate = attempt == 0
            val typeOut = arena.allocate(ValueLayout.ADDRESS)
            val createResult = MfCom.result(
                MfCom.invoke("mfplat.dll", "MFCreateMediaType", D_MF_CREATE_MEDIA_TYPE, typeOut),
            )
            if (createResult < 0) throw mfFailure("cannot create an NV12 media type", createResult)
            val type = typeOut.get(ValueLayout.ADDRESS, 0)
            if (type.address() == 0L) throw CameraException("Media Foundation returned no media type")
            try {
                setGuid(type, MF_MT_MAJOR_TYPE, MFMediaType_Video)
                setGuid(type, MF_MT_SUBTYPE, MFVideoFormat_NV12)
                setUint32(type, MF_MT_INTERLACE_MODE, MF_VIDEO_INTERLACE_PROGRESSIVE)
                if (withSize) {
                    setUint64(type, MF_MT_FRAME_SIZE, mfPack(initialFormat.width, initialFormat.height))
                }
                if (withRate) {
                    setUint64(type, MF_MT_FRAME_RATE, mfPack(initialFormat.frameRate, 1))
                }
                lastResult = MfCom.result(
                    MfCom.call(
                        reader,
                        MF_INDEX_SET_CURRENT_MEDIA_TYPE,
                        D_SET_CURRENT_MEDIA_TYPE,
                        MF_FIRST_VIDEO_STREAM,
                        MemorySegment.NULL,
                        type,
                    ),
                )
            } finally {
                MfCom.release(type)
            }
            if (lastResult >= 0) return
        }
        throw mfFailure(
            "Media Foundation camera ${camera.name} rejected NV12 capture at " +
                "${initialFormat.width}x${initialFormat.height}",
            lastResult,
        )
    }

    private fun applyCurrentMediaType() {
        val arena = Arena.ofConfined()
        try {
            var width = initialFormat.width
            var height = initialFormat.height
            var stride = width
            var rate = initialFormat.frameRate

            val typeOut = arena.allocate(ValueLayout.ADDRESS)
            val result = MfCom.result(
                MfCom.call(
                    reader,
                    MF_INDEX_GET_CURRENT_MEDIA_TYPE,
                    D_GET_CURRENT_MEDIA_TYPE,
                    MF_FIRST_VIDEO_STREAM,
                    typeOut,
                ),
            )
            if (result >= 0) {
                val type = typeOut.get(ValueLayout.ADDRESS, 0)
                if (type.address() != 0L) {
                    try {
                        mfReadUint64(type, arena, MF_MT_FRAME_SIZE)?.let { packed ->
                            val nativeWidth = (packed ushr 32).toInt()
                            val nativeHeight = packed.toInt()
                            if (nativeWidth > 0 && nativeHeight > 0) {
                                width = nativeWidth
                                height = nativeHeight
                            }
                        }
                        mfReadUint64(type, arena, MF_MT_FRAME_RATE)?.let { packed ->
                            mfFps(packed)?.let { rate = it }
                        }
                        mfReadUint32(type, arena, MF_MT_DEFAULT_STRIDE)?.let { value ->
                            if (value != 0) stride = value
                        }
                    } finally {
                        MfCom.release(type)
                    }
                }
            }

            frameWidth = width
            frameHeight = height
            frameStride = stride
            updateNegotiatedFormat(CameraFormat(width, height, PixelFormat.NV12, rate))
        } finally {
            arena.close()
        }
    }

    private fun startStreaming() {
        synchronized(readerLock) {
            if (readerClosed) throw CameraException("Media Foundation camera ${camera.name} closed while starting")
            val result = readNextSampleLocked()
            if (result < 0) {
                throw mfFailure("Media Foundation camera ${camera.name} failed to start its stream", result)
            }
        }
    }

    /** Requests the next asynchronous sample. Called from callback threads. */
    fun requestNextSample() {
        val callback = callbackState ?: return
        var result = 0
        synchronized(readerLock) {
            if (readerClosed || callback.closed || callback.failed) return
            result = readNextSampleLocked()
        }
        if (result < 0) {
            callback.failed = true
            reportError(
                mfFailure("Media Foundation camera ${camera.name} stopped producing samples", result),
            )
        }
    }

    private fun readNextSampleLocked(): Int {
        val activeReader = reader
        if (activeReader.address() == 0L) return -1
        // Asynchronous mode: every [out] parameter must be NULL, the results
        // arrive through IMFSourceReaderCallback::OnReadSample instead.
        return MfCom.result(
            MfCom.call(
                activeReader,
                MF_INDEX_READ_SAMPLE,
                D_READ_SAMPLE,
                MF_FIRST_VIDEO_STREAM,
                0,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
            ),
        )
    }

    fun onReadSampleFailure(result: Int) {
        reportError(mfFailure("Media Foundation camera ${camera.name} reported a capture error", result))
    }

    fun onStreamEnded() {
        reportError(
            CameraException(
                "the Media Foundation stream of ${camera.name} ended; the camera was probably unplugged",
            ),
        )
    }

    fun onStreamError() {
        reportError(
            CameraException(
                "Media Foundation reported a stream error for ${camera.name}; reconnect the camera",
            ),
        )
    }

    fun onCaptureFailure(message: String, error: Throwable?) {
        reportError(CameraException("$message from ${camera.name}", error))
    }

    /** Copies one NV12 sample out of Media Foundation memory and publishes it. */
    fun consumeSample(sample: MemorySegment, timestamp: Long) {
        val width = frameWidth
        val height = frameHeight
        if (width <= 0 || height <= 0) return

        val arena = Arena.ofConfined()
        try {
            val bufferOut = arena.allocate(ValueLayout.ADDRESS)
            val convertResult = MfCom.result(
                MfCom.call(sample, MF_INDEX_CONVERT_TO_CONTIGUOUS_BUFFER, D_CONVERT_TO_CONTIGUOUS_BUFFER, bufferOut),
            )
            if (convertResult < 0) return
            val buffer = bufferOut.get(ValueLayout.ADDRESS, 0)
            if (buffer.address() == 0L) return
            try {
                val dataOut = arena.allocate(ValueLayout.ADDRESS)
                val lockResult = MfCom.result(
                    MfCom.call(
                        buffer,
                        MF_INDEX_LOCK,
                        D_LOCK,
                        dataOut,
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                    ),
                )
                if (lockResult < 0) return
                try {
                    val data = dataOut.get(ValueLayout.ADDRESS, 0)
                    if (data.address() == 0L) return
                    val lengthOut = arena.allocate(ValueLayout.JAVA_INT)
                    val lengthResult = MfCom.result(
                        MfCom.call(buffer, MF_INDEX_GET_CURRENT_LENGTH, D_GET_CURRENT_LENGTH, lengthOut),
                    )
                    if (lengthResult < 0) return
                    val length = lengthOut.get(ValueLayout.JAVA_INT, 0)
                    if (length <= 0) return
                    publishFrame(data, length, width, height, frameStride, timestamp)
                } finally {
                    MfCom.call(buffer, MF_INDEX_UNLOCK, D_UNLOCK)
                }
            } finally {
                MfCom.release(buffer)
            }
        } finally {
            arena.close()
        }
    }

    private fun publishFrame(
        data: MemorySegment,
        length: Int,
        width: Int,
        height: Int,
        strideValue: Int,
        timestamp: Long,
    ) {
        val chromaHeight = (height + 1) / 2
        val stride = if (strideValue == 0) width else strideValue
        val bottomUp = stride < 0
        var absoluteStride = if (bottomUp) -stride else stride
        if (absoluteStride < width || absoluteStride > width * 16) absoluteStride = width

        val tightSize = width * height + width * chromaHeight
        val stridedSize = absoluteStride * (height + chromaHeight)
        if (length < stridedSize && length >= tightSize) {
            // The buffer is tightly packed even though the media type carried a
            // padded stride; follow the buffer, not the attribute.
            absoluteStride = width
        }

        val sourceMemory = data.reinterpret(length.toLong())
        val yBytes = ByteArray(width * height)
        val uvBytes = ByteArray(width * chromaHeight)

        val availableRows = length / absoluteStride
        val yRows = minOf(height, availableRows)
        for (row in 0 until yRows) {
            val sourceRow = if (bottomUp) height - 1 - row else row
            val sourceOffset = sourceRow.toLong() * absoluteStride
            if (sourceOffset + width > length) break
            MemorySegment.copy(
                sourceMemory,
                ValueLayout.JAVA_BYTE,
                sourceOffset,
                yBytes,
                row * width,
                width,
            )
        }

        val chromaOffset = absoluteStride.toLong() * height
        val chromaAvailable = length - chromaOffset
        if (chromaAvailable > 0) {
            val chromaRows = minOf(chromaHeight, (chromaAvailable / absoluteStride).toInt())
            for (row in 0 until chromaRows) {
                val sourceRow = if (bottomUp) chromaHeight - 1 - row else row
                val sourceOffset = chromaOffset + sourceRow.toLong() * absoluteStride
                if (sourceOffset + width > length) break
                MemorySegment.copy(
                    sourceMemory,
                    ValueLayout.JAVA_BYTE,
                    sourceOffset,
                    uvBytes,
                    row * width,
                    width,
                )
            }
        }

        val nanos = if (timestamp > 0 && timestamp <= Long.MAX_VALUE / 100) {
            timestamp * 100
        } else {
            monotonicNanos()
        }

        publish(
            SourceFrame(
                width = width,
                height = height,
                pixelFormat = PixelFormat.NV12,
                planes = listOf(
                    SourcePlane(yBytes, 0, width, 1, width, height),
                    SourcePlane(uvBytes, 0, width, 2, (width + 1) / 2, chromaHeight),
                ),
                timestampNanos = nanos,
            ),
        )
    }

    private fun closeReader() {
        val callback = callbackState
        callbackState = null
        if (callback != null) {
            callback.markClosed()
            synchronized(readerLock) { readerClosed = true }
            val activeReader = reader
            if (activeReader.address() != 0L) {
                runCatching {
                    MfCom.call(activeReader, MF_INDEX_FLUSH, D_FLUSH, MF_FIRST_VIDEO_STREAM)
                }
            }
            callback.awaitQuiescent(2_000)
        } else {
            synchronized(readerLock) { readerClosed = true }
        }

        val activeReader = reader
        reader = MemorySegment.NULL
        if (activeReader.address() != 0L) MfCom.release(activeReader)

        val activeAttributes = attributes
        attributes = MemorySegment.NULL
        if (activeAttributes.address() != 0L) MfCom.release(activeAttributes)

        val activeSource = source
        source = MemorySegment.NULL
        if (activeSource.address() != 0L) MfCom.release(activeSource)

        val activeActivate = activate
        activate = MemorySegment.NULL
        if (activeActivate.address() != 0L) {
            runCatching { MfCom.call(activeActivate, MF_INDEX_SHUTDOWN_OBJECT, D_SHUTDOWN_OBJECT) }
            MfCom.release(activeActivate)
        }

        callback?.releaseHandle()
    }

    private fun setGuid(attributes: MemorySegment, key: MemorySegment, value: MemorySegment) {
        val result = MfCom.result(
            MfCom.call(attributes, MF_INDEX_SET_GUID, D_SET_GUID, key, value),
        )
        if (result < 0) throw mfFailure("cannot set a Media Foundation attribute", result)
    }

    private fun setUint32(attributes: MemorySegment, key: MemorySegment, value: Int) {
        val result = MfCom.result(
            MfCom.call(attributes, MF_INDEX_SET_UINT32, D_SET_UINT32, key, value),
        )
        if (result < 0) throw mfFailure("cannot set a Media Foundation attribute", result)
    }

    private fun setUint64(attributes: MemorySegment, key: MemorySegment, value: Long) {
        val result = MfCom.result(
            MfCom.call(attributes, MF_INDEX_SET_UINT64, D_SET_UINT64, key, value),
        )
        if (result < 0) throw mfFailure("cannot set a Media Foundation attribute", result)
    }

    private fun setUnknown(attributes: MemorySegment, key: MemorySegment, value: MemorySegment) {
        val result = MfCom.result(
            MfCom.call(attributes, MF_INDEX_SET_UNKNOWN, D_SET_UNKNOWN, key, value),
        )
        if (result < 0) {
            throw mfFailure("Media Foundation cannot enable asynchronous capture", result)
        }
    }

    private fun mfInitializeCom(): Boolean {
        val result = MfCom.result(
            MfCom.invoke("ole32.dll", "CoInitializeEx", D_CO_INITIALIZE, MemorySegment.NULL, 0),
        )
        return when {
            result >= 0 -> true
            result == RPC_E_CHANGED_MODE -> false
            else -> throw mfFailure("cannot initialize COM for Media Foundation", result)
        }
    }

    private fun mfUninitializeCom() {
        runCatching { MfCom.invoke("ole32.dll", "CoUninitialize", D_CO_UNINITIALIZE) }
    }

    private companion object {
        const val MF_OPEN_TIMEOUT_SECONDS = 15L
    }
}

/**
 * COM object handed to Media Foundation as `IMFSourceReaderCallback`.
 *
 * Media Foundation calls it from worker threads, so it only copies the sample
 * and asks for the next one. The object stays allocated in a process-wide
 * arena; after [release] no callback touches a released source reader.
 */
private class MfReaderCallback(
    private val session: JvmMediaFoundationCameraSession,
) {
    val handle: MemorySegment = MfCallbackBridge.register(this)
    val outstanding = AtomicInteger(0)
    private val references = AtomicInteger(1)
    private val lifecycle = Any()

    @Volatile
    var closed = false

    @Volatile
    var failed = false

    /** Blocks new callbacks from starting before teardown releases the reader. */
    fun markClosed() {
        synchronized(lifecycle) { closed = true }
    }

    fun addRef(): Int = references.incrementAndGet()

    fun release(): Int {
        val remaining = references.decrementAndGet()
        if (remaining <= 0) {
            markClosed()
            MfCallbackBridge.unregister(handle)
        }
        return remaining.coerceAtLeast(0)
    }

    fun releaseHandle() {
        // Drop the reference this backend kept when it created the object; the
        // source reader (and its attributes) hold the other references.
        release()
    }

    fun onReadSample(
        result: Int,
        streamIndex: Int,
        streamFlags: Int,
        timestamp: Long,
        sample: MemorySegment,
    ): Int {
        synchronized(lifecycle) {
            if (closed) return S_OK
            outstanding.incrementAndGet()
        }
        var requestNext = false
        try {
            when {
                result < 0 -> {
                    failed = true
                    session.onReadSampleFailure(result)
                }

                streamFlags and MF_SOURCE_READERF_ERROR != 0 -> {
                    failed = true
                    session.onStreamError()
                }

                streamFlags and MF_SOURCE_READERF_ENDOFSTREAM != 0 -> {
                    failed = true
                    session.onStreamEnded()
                }

                sample.address() == 0L -> Unit

                else -> session.consumeSample(sample, timestamp)
            }
            requestNext = !failed
        } catch (error: Throwable) {
            failed = true
            session.onCaptureFailure("failed to copy a Media Foundation frame", error)
        } finally {
            outstanding.decrementAndGet()
        }
        if (requestNext && !closed) {
            try {
                session.requestNextSample()
            } catch (error: Throwable) {
                failed = true
                session.onCaptureFailure("failed to request the next Media Foundation frame", error)
            }
        }
        return S_OK
    }

    /** Waits for callbacks that already entered Media Foundation's dispatch. */
    fun awaitQuiescent(timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline && outstanding.get() != 0) {
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}

/**
 * Allocates the callback object and its vtable.
 *
 * The memory lives in a process-wide shared arena so that late callbacks after
 * [MfReaderCallback.release] can never touch freed memory; they simply find no
 * registration and return.
 */
private object MfCallbackBridge {
    private val arena: Arena = Arena.ofShared()
    private val callbacks = ConcurrentHashMap<Long, MfReaderCallback>()
    private val table: MemorySegment by lazy { createTable() }

    fun register(callback: MfReaderCallback): MemorySegment {
        val objectMemory = arena.allocate(8L)
        objectMemory.set(ValueLayout.ADDRESS, 0L, table)
        callbacks[objectMemory.address()] = callback
        return objectMemory
    }

    fun unregister(handle: MemorySegment) {
        if (handle.address() != 0L) callbacks.remove(handle.address())
    }

    fun find(self: MemorySegment): MfReaderCallback? = callbacks[self.address()]

    fun matches(guid: MemorySegment, expected: MemorySegment): Boolean {
        if (guid.address() == 0L) return false
        val value = guid.reinterpret(16L)
        return value.get(ValueLayout.JAVA_LONG, 0L) == expected.get(ValueLayout.JAVA_LONG, 0L) &&
            value.get(ValueLayout.JAVA_LONG, 8L) == expected.get(ValueLayout.JAVA_LONG, 8L)
    }

    private fun createTable(): MemorySegment {
        val vtable = arena.allocate(6L * 8L)
        vtable.set(
            ValueLayout.ADDRESS,
            0L,
            upcall(
                "queryInterface",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                ),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                    MemorySegment::class.java,
                ),
            ),
        )
        vtable.set(
            ValueLayout.ADDRESS,
            8L,
            upcall(
                "addRef",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                ),
            ),
        )
        vtable.set(
            ValueLayout.ADDRESS,
            16L,
            upcall(
                "release",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                ),
            ),
        )
        vtable.set(
            ValueLayout.ADDRESS,
            24L,
            upcall(
                "onReadSample",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                ),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                ),
            ),
        )
        vtable.set(
            ValueLayout.ADDRESS,
            32L,
            upcall(
                "onFlush",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
            ),
        )
        vtable.set(
            ValueLayout.ADDRESS,
            40L,
            upcall(
                "onEvent",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                ),
                MethodType.methodType(
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                    Int::class.javaPrimitiveType!!,
                    MemorySegment::class.java,
                ),
            ),
        )
        return vtable
    }

    private fun upcall(
        name: String,
        descriptor: FunctionDescriptor,
        methodType: MethodType,
    ): MemorySegment {
        val method = MethodHandles.lookup().findStatic(MfCallbackUpcalls::class.java, name, methodType)
        return Linker.nativeLinker().upcallStub(method, descriptor, arena)
    }
}

private object MfCallbackUpcalls {
    @JvmStatic
    fun queryInterface(self: MemorySegment, riid: MemorySegment, out: MemorySegment): Int {
        return try {
            val callback = MfCallbackBridge.find(self)
            if (callback != null &&
                (MfCallbackBridge.matches(riid, IID_I_UNKNOWN) ||
                    MfCallbackBridge.matches(riid, IID_IMF_SOURCE_READER_CALLBACK))
            ) {
                out.reinterpret(8L).set(ValueLayout.ADDRESS, 0L, self)
                callback.addRef()
                S_OK
            } else {
                if (out.address() != 0L) {
                    out.reinterpret(8L).set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL)
                }
                E_NOINTERFACE
            }
        } catch (_: Throwable) {
            E_FAIL
        }
    }

    @JvmStatic
    fun addRef(self: MemorySegment): Int = try {
        MfCallbackBridge.find(self)?.addRef() ?: 0
    } catch (_: Throwable) {
        0
    }

    @JvmStatic
    fun release(self: MemorySegment): Int = try {
        MfCallbackBridge.find(self)?.release() ?: 0
    } catch (_: Throwable) {
        0
    }

    @JvmStatic
    fun onReadSample(
        self: MemorySegment,
        result: Int,
        streamIndex: Int,
        streamFlags: Int,
        timestamp: Long,
        sample: MemorySegment,
    ): Int = try {
        MfCallbackBridge.find(self)?.onReadSample(result, streamIndex, streamFlags, timestamp, sample) ?: S_OK
    } catch (_: Throwable) {
        E_FAIL
    }

    @JvmStatic
    fun onFlush(self: MemorySegment, streamIndex: Int): Int = S_OK

    @JvmStatic
    fun onEvent(self: MemorySegment, streamIndex: Int, event: MemorySegment): Int = S_OK
}

/** Reference-counted `MFStartup`/`MFShutdown` pair. */
private object MfRuntime {
    private val lock = Any()
    private var references = 0

    fun acquire() {
        synchronized(lock) {
            if (references == 0) {
                val result = withCom {
                    MfCom.result(
                        MfCom.invoke("mfplat.dll", "MFStartup", D_MF_STARTUP, MF_VERSION, MFSTARTUP_FULL),
                    )
                }
                if (result < 0) throw mfFailure("cannot start Media Foundation", result)
            }
            references++
        }
    }

    fun release() {
        synchronized(lock) {
            if (references == 0) return
            references--
            if (references == 0) {
                withCom { runCatching { MfCom.invoke("mfplat.dll", "MFShutdown", D_MF_SHUTDOWN) } }
            }
        }
    }
}

private object MfCom {
    private val linker: Linker = Linker.nativeLinker()
    private val arena: Arena = Arena.ofShared()
    private val libraries = ConcurrentHashMap<String, SymbolLookup>()
    private val functions = ConcurrentHashMap<String, MethodHandle>()
    private val methods = ConcurrentHashMap<String, MethodHandle>()

    private fun lookupLibrary(name: String): SymbolLookup = libraries.computeIfAbsent(name) { key ->
        try {
            SymbolLookup.libraryLookup(key, arena)
        } catch (error: IllegalArgumentException) {
            throw CameraUnsupportedException(
                "Media Foundation library $key is not available; this backend requires Windows 10 or later",
                error,
            )
        } catch (error: UnsatisfiedLinkError) {
            throw CameraUnsupportedException(
                "Media Foundation library $key is not available; this backend requires Windows 10 or later",
                error,
            )
        }
    }

    private fun function(
        library: String,
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = functions.computeIfAbsent("$library#$name#$descriptor") {
        val symbol = lookupLibrary(library).find(name).orElseThrow {
            CameraUnsupportedException("$library does not export $name")
        }
        linker.downcallHandle(symbol, descriptor)
    }

    fun invoke(
        library: String,
        name: String,
        descriptor: FunctionDescriptor,
        vararg args: Any?,
    ): Any? = function(library, name, descriptor).invokeWithArguments(*args)

    fun call(
        pointer: MemorySegment,
        index: Int,
        descriptor: FunctionDescriptor,
        vararg args: Any?,
    ): Any? {
        if (pointer.address() == 0L) {
            throw CameraException("Media Foundation returned a null interface pointer")
        }
        val table = pointer.reinterpret(8L).get(ValueLayout.ADDRESS, 0L)
        if (table.address() == 0L) {
            throw CameraException("Media Foundation returned an interface without a vtable")
        }
        val entry = table.reinterpret((index + 1).toLong() * 8L)
            .get(ValueLayout.ADDRESS, index.toLong() * 8L)
        if (entry.address() == 0L) {
            throw CameraException("Media Foundation returned an interface with an empty vtable entry")
        }
        val handle = methods.computeIfAbsent("${entry.address()}#$descriptor") {
            linker.downcallHandle(entry, descriptor)
        }
        return handle.invokeWithArguments(pointer, *args)
    }

    fun result(value: Any?): Int = value as Int

    fun addRef(pointer: MemorySegment) {
        if (pointer.address() == 0L) return
        call(pointer, MF_INDEX_ADD_REF, D_ADD_REF)
    }

    fun release(pointer: MemorySegment) {
        if (pointer.address() == 0L) return
        runCatching { call(pointer, MF_INDEX_RELEASE, D_RELEASE) }
    }
}

private fun mfFailure(action: String, result: Int): CameraException {
    val code = "0x" + result.toUInt().toString(16).padStart(8, '0')
    val hint = when (result) {
        E_ACCESS_DENIED -> " (access denied: allow camera access in Settings > Privacy & security > Camera)"
        E_SHARING_VIOLATION, E_BUSY -> " (the camera is in use; close other camera applications)"
        E_NOINTERFACE -> " (the driver does not expose the required Media Foundation interface)"
        else -> ""
    }
    return CameraException("$action (HRESULT $code)$hint")
}

private fun mfGuid(data1: Long, data2: Int, data3: Int, vararg tail: Int): MemorySegment {
    val guid = GUID_ARENA.allocate(16L)
    guid.set(ValueLayout.JAVA_INT, 0L, data1.toInt())
    guid.set(ValueLayout.JAVA_SHORT, 4L, data2.toShort())
    guid.set(ValueLayout.JAVA_SHORT, 6L, data3.toShort())
    for (index in 0 until 8) {
        guid.set(ValueLayout.JAVA_BYTE, (8 + index).toLong(), tail[index].toByte())
    }
    return guid
}

private const val S_OK = 0

private val E_NOINTERFACE = 0x80004002.toInt()
private val E_FAIL = 0x80004005.toInt()
private val E_ACCESS_DENIED = 0x80070005.toInt()
private val E_SHARING_VIOLATION = 0x80070020.toInt()
private val E_BUSY = 0x800700AA.toInt()
private val RPC_E_CHANGED_MODE = 0x80010106.toInt()

private const val MF_VERSION = 0x00020070
private const val MFSTARTUP_FULL = 0
private const val MF_INDEX_RELEASE = 2
private const val MF_INDEX_ADD_REF = 1
private const val MF_INDEX_ACTIVATE_OBJECT = 33
private const val MF_INDEX_SHUTDOWN_OBJECT = 34
private const val MF_INDEX_GET_UINT32 = 7
private const val MF_INDEX_GET_UINT64 = 8
private const val MF_INDEX_GET_ALLOCATED_STRING = 13
private const val MF_INDEX_SET_UINT32 = 21
private const val MF_INDEX_SET_UINT64 = 22
private const val MF_INDEX_SET_GUID = 24
private const val MF_INDEX_SET_UNKNOWN = 27
private const val MF_INDEX_GET_NATIVE_MEDIA_TYPE = 5
private const val MF_INDEX_GET_CURRENT_MEDIA_TYPE = 6
private const val MF_INDEX_SET_CURRENT_MEDIA_TYPE = 7
private const val MF_INDEX_READ_SAMPLE = 9
private const val MF_INDEX_FLUSH = 10
private const val MF_INDEX_CONVERT_TO_CONTIGUOUS_BUFFER = 41
private const val MF_INDEX_LOCK = 3
private const val MF_INDEX_UNLOCK = 4
private const val MF_INDEX_GET_CURRENT_LENGTH = 5
private const val MF_VIDEO_INTERLACE_PROGRESSIVE = 2
private const val MF_SOURCE_READERF_ERROR = 0x1
private const val MF_SOURCE_READERF_ENDOFSTREAM = 0x2
private const val MF_FIRST_VIDEO_STREAM = -4

private val GUID_ARENA: Arena = Arena.ofShared()

private val IID_I_UNKNOWN = mfGuid(
    0x00000000, 0x0000, 0x0000,
    0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46,
)
private val IID_IMF_MEDIA_SOURCE = mfGuid(
    0x279a808d, 0xaec7, 0x40c8,
    0x9c, 0x6b, 0xa6, 0xb4, 0x92, 0xc7, 0x8a, 0x66,
)
private val IID_IMF_SOURCE_READER_CALLBACK = mfGuid(
    0xdeec8d99, 0xfa1d, 0x4d82,
    0x84, 0xc2, 0x2c, 0x89, 0x69, 0x94, 0x48, 0x67,
)
private val MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE = mfGuid(
    0xc60ac5fe, 0x252a, 0x478f,
    0xa0, 0xef, 0xbc, 0x8f, 0xa5, 0xf7, 0xca, 0xd3,
)
private val MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP = mfGuid(
    0x8ac3587a, 0x4ae7, 0x42d8,
    0x99, 0xe0, 0x0a, 0x60, 0x13, 0xee, 0xf9, 0x0f,
)
private val MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME = mfGuid(
    0x60d0e559, 0x52f8, 0x4fa2,
    0xbb, 0xce, 0xac, 0xdb, 0x34, 0xa8, 0xec, 0x01,
)
private val MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK = mfGuid(
    0x58f0aad8, 0x22bf, 0x4f8a,
    0xbb, 0x3d, 0xd2, 0xc4, 0x97, 0x8c, 0x6e, 0x2f,
)
private val MF_MT_MAJOR_TYPE = mfGuid(
    0x48eba18e, 0xf8c9, 0x4687,
    0xbf, 0x11, 0x0a, 0x74, 0xc9, 0xf9, 0x6a, 0x8f,
)
private val MF_MT_SUBTYPE = mfGuid(
    0xf7e34c9a, 0x42e8, 0x4714,
    0xb7, 0x4b, 0xcb, 0x29, 0xd7, 0x2c, 0x35, 0xe5,
)
private val MF_MT_FRAME_SIZE = mfGuid(
    0x1652c33d, 0xd6b2, 0x4012,
    0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, 0xa3, 0x7d,
)
private val MF_MT_FRAME_RATE = mfGuid(
    0xc459a2e8, 0x3d2c, 0x4e44,
    0xb1, 0x32, 0xfe, 0xe5, 0x15, 0x6c, 0x7b, 0xb0,
)
private val MF_MT_FRAME_RATE_RANGE_MIN = mfGuid(
    0xd2e7558c, 0xdc1f, 0x403f,
    0x9a, 0x72, 0xd2, 0x8b, 0xb1, 0xeb, 0x3b, 0x5e,
)
private val MF_MT_FRAME_RATE_RANGE_MAX = mfGuid(
    0xe3371d41, 0xb4cf, 0x4a05,
    0xbd, 0x4e, 0x20, 0xb8, 0x8b, 0xb2, 0xc4, 0xd6,
)
private val MF_MT_INTERLACE_MODE = mfGuid(
    0xe2724bb8, 0xe676, 0x4806,
    0xb4, 0xb2, 0xa8, 0xd6, 0xef, 0xb4, 0x4c, 0xcd,
)
private val MF_MT_DEFAULT_STRIDE = mfGuid(
    0x644b4e48, 0x1e02, 0x4516,
    0xb0, 0xeb, 0xc0, 0x1c, 0xa9, 0xd4, 0x9a, 0xc6,
)
private val MFMediaType_Video = mfGuid(
    0x73646976, 0x0000, 0x0010,
    0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71,
)
private val MFVideoFormat_NV12 = mfGuid(
    0x3231564e, 0x0000, 0x0010,
    0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71,
)
private val MF_SOURCE_READER_ASYNC_CALLBACK = mfGuid(
    0x1e3dbeac, 0xbb43, 0x4c35,
    0xb5, 0x07, 0xcd, 0x64, 0x44, 0x64, 0xc9, 0x65,
)
private val MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING = mfGuid(
    0xfb394f3d, 0xccf1, 0x42ee,
    0xbb, 0xb3, 0xf9, 0xb8, 0x45, 0xd5, 0x68, 0x1d,
)
private val MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING = mfGuid(
    0x0f81da2c, 0xb537, 0x4672,
    0xa8, 0xb2, 0xa6, 0x81, 0xb1, 0x73, 0x07, 0xa3,
)

private val D_RELEASE = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
private val D_ADD_REF = D_RELEASE
private val D_SHUTDOWN_OBJECT = D_RELEASE
private val D_ACTIVATE_OBJECT = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_GET_ALLOCATED_STRING = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_GET_NATIVE_MEDIA_TYPE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
)
private val D_GET_CURRENT_MEDIA_TYPE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
)
private val D_SET_CURRENT_MEDIA_TYPE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_READ_SAMPLE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_FLUSH = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
)
private val D_GET_UINT32 = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_GET_UINT64 = D_GET_UINT32
private val D_SET_GUID = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_SET_UINT32 = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
)
private val D_SET_UINT64 = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_LONG,
)
private val D_SET_UNKNOWN = D_SET_GUID
private val D_CONVERT_TO_CONTIGUOUS_BUFFER = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_LOCK = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_UNLOCK = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
private val D_GET_CURRENT_LENGTH = D_CONVERT_TO_CONTIGUOUS_BUFFER
private val D_MF_STARTUP = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_INT,
    ValueLayout.JAVA_INT,
)
private val D_MF_SHUTDOWN = FunctionDescriptor.of(ValueLayout.JAVA_INT)
private val D_MF_ENUM_DEVICE_SOURCES = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_MF_CREATE_ATTRIBUTES = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
)
private val D_MF_CREATE_MEDIA_TYPE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
)
private val D_MF_CREATE_SOURCE_READER = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
    ValueLayout.ADDRESS,
)
private val D_CO_INITIALIZE = FunctionDescriptor.of(
    ValueLayout.JAVA_INT,
    ValueLayout.ADDRESS,
    ValueLayout.JAVA_INT,
)
private val D_CO_UNINITIALIZE = FunctionDescriptor.ofVoid()
private val D_CO_TASK_MEM_FREE = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
