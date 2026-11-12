@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import cn.enaium.camera.mediafoundation.IID_IMFMediaSource
import cn.enaium.camera.mediafoundation.IMFActivate
import cn.enaium.camera.mediafoundation.IMFAttributes
import cn.enaium.camera.mediafoundation.IMFMediaBuffer
import cn.enaium.camera.mediafoundation.IMFMediaSource
import cn.enaium.camera.mediafoundation.IMFMediaType
import cn.enaium.camera.mediafoundation.IMFSample
import cn.enaium.camera.mediafoundation.IMFSourceReader
import cn.enaium.camera.mediafoundation.MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME
import cn.enaium.camera.mediafoundation.MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID
import cn.enaium.camera.mediafoundation.MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK
import cn.enaium.camera.mediafoundation.MF_E_ATTRIBUTENOTFOUND
import cn.enaium.camera.mediafoundation.MF_E_INVALIDMEDIATYPE
import cn.enaium.camera.mediafoundation.MF_E_NO_MORE_TYPES
import cn.enaium.camera.mediafoundation.MF_MT_DEFAULT_STRIDE
import cn.enaium.camera.mediafoundation.MF_MT_FRAME_RATE
import cn.enaium.camera.mediafoundation.MF_MT_FRAME_RATE_RANGE_MAX
import cn.enaium.camera.mediafoundation.MF_MT_FRAME_SIZE
import cn.enaium.camera.mediafoundation.MF_MT_MAJOR_TYPE
import cn.enaium.camera.mediafoundation.MF_MT_SUBTYPE
import cn.enaium.camera.mediafoundation.MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING
import cn.enaium.camera.mediafoundation.MF_SOURCE_READER_FIRST_VIDEO_STREAM
import cn.enaium.camera.mediafoundation.MF_SOURCE_READERF_ENDOFSTREAM
import cn.enaium.camera.mediafoundation.MF_SOURCE_READERF_ERROR
import cn.enaium.camera.mediafoundation.MF_VERSION
import cn.enaium.camera.mediafoundation.MFSTARTUP_FULL
import cn.enaium.camera.mediafoundation.MFEnumDeviceSources
import cn.enaium.camera.mediafoundation.MFCreateAttributes
import cn.enaium.camera.mediafoundation.MFCreateMediaType
import cn.enaium.camera.mediafoundation.MFMediaType_Video
import cn.enaium.camera.mediafoundation.MFShutdown
import cn.enaium.camera.mediafoundation.MFStartup
import cn.enaium.camera.mediafoundation.MFVideoFormat_NV12
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.posix._GUID
import platform.posix.memcmp
import platform.windows.COINIT_MULTITHREADED
import platform.windows.CoInitializeEx
import platform.windows.CoTaskMemFree
import platform.windows.CoUninitialize
import platform.windows.GetProcAddress
import platform.windows.HMODULE
import platform.windows.LoadLibraryW

/**
 * Windows capture through Media Foundation.
 *
 * The backend asks Media Foundation's source reader for NV12 output; the
 * reader inserts the converters if the camera only records MJPG/YUY2, so every
 * session delivers NV12 with the stride the reader reports. Enumeration and
 * the capture loop are the only places that touch COM: each interface is called
 * through its `lpVtbl` and released after use.
 *
 * Media Foundation is started once per [MediaFoundationCameraSystem] and shut
 * down in [MediaFoundationCameraSystem.close], so a system must stay open for
 * as long as any of its sessions runs.
 *
 * `MFCreateSourceReaderFromMediaSource` is the one entry point that the MinGW
 * sysroot has no import library for; it is resolved from `mfreadwrite.dll` on
 * first use instead of being linked statically.
 */
internal class MediaFoundationCameraSystem(
    private val backendKind: CameraBackend = CameraBackend.MEDIA_FOUNDATION,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "Media Foundation"

    private var closed = false

    init {
        MfRuntime.acquire()
    }

    override fun cameras(): List<CameraInfo> {
        checkOpen()
        return withComApartment {
            val devices = enumerateDevices()
            devices.mapIndexed { index, device ->
                CameraInfo(
                    id = device.id,
                    name = device.name,
                    lensFacing = device.lensFacing,
                    sensorOrientation = 0,
                    transport = device.transport,
                    isDefault = index == 0,
                    supportedFormats = device.formats,
                )
            }
        }
    }

    override fun defaultCamera(lensFacing: CameraLensFacing): CameraInfo? {
        val all = cameras()
        return all.firstOrNull { it.lensFacing == lensFacing } ?: all.firstOrNull()
    }

    override fun open(config: CameraConfig): CameraSession {
        checkOpen()
        val all = cameras()
        val info = config.cameraId?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull { it.lensFacing == config.lensFacing }
            ?: all.firstOrNull()
            ?: throw CameraException(
                "no Media Foundation video capture device is available; " +
                    "connect a camera and allow desktop apps to use it in Windows settings",
            )
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession {
        checkOpen()
        return MediaFoundationCameraSession(camera, config)
    }

    override fun close() {
        if (closed) return
        closed = true
        MfRuntime.release()
    }

    private fun checkOpen() {
        check(!closed) { "Media Foundation camera system is closed" }
    }

    private fun enumerateDevices(): List<MfDevice> {
        val activates = acquireActivates()
        try {
            return activates.mapIndexed { index, activate -> describeDevice(activate, index) }
        } finally {
            for (activate in activates) releaseActivate(activate)
        }
    }

    private fun describeDevice(activate: CPointer<IMFActivate>, index: Int): MfDevice {
        val symbolicLink = allocatedString(activate, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK.ptr)
        val friendlyName = allocatedString(activate, MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME.ptr)
        val id = symbolicLink?.takeIf { it.isNotBlank() } ?: "mf:vidcap:$index"
        val name = friendlyName?.takeIf { it.isNotBlank() } ?: "Camera $index"
        val transport = classifyTransport(symbolicLink, name)
        val lensFacing = lensFacingFor(transport)
        val formats = try {
            enumerateFormats(activate)
        } catch (error: CameraException) {
            // A camera that is currently busy cannot be described; keep it in
            // the list so the caller sees it and gets the real error on open.
            emptyList()
        }
        return MfDevice(id, name, lensFacing, transport, formats)
    }

    /** Enumerates the media types the camera records natively and advertises them as NV12. */
    private fun enumerateFormats(activate: CPointer<IMFActivate>): List<CameraFormat> = memScoped {
        val sourceOut = alloc<COpaquePointerVar>()
        val activateHr = activate.pointed.lpVtbl!!.pointed.ActivateObject!!(
            activate,
            IID_IMFMediaSource.ptr,
            sourceOut.ptr,
        )
        if (activateHr < 0) return emptyList()
        val source = sourceOut.value?.reinterpret<IMFMediaSource>() ?: return emptyList()
        try {
            val readerOut = alloc<CPointerVar<IMFSourceReader>>()
            val readerHr = MfSourceReaderFactory.create(source, null, readerOut.ptr)
            if (readerHr < 0) return emptyList()
            val reader = readerOut.value ?: return emptyList()
            try {
                enumerateNativeFormats(reader)
            } finally {
                releaseSourceReader(reader)
            }
        } finally {
            releaseMediaSource(source)
        }
    }

    private fun enumerateNativeFormats(reader: CPointer<IMFSourceReader>): List<CameraFormat> = memScoped {
        val formats = LinkedHashSet<CameraFormat>()
        val getNative = reader.pointed.lpVtbl!!.pointed.GetNativeMediaType!!
        var index = 0u
        while (index < MAX_NATIVE_MEDIA_TYPES) {
            val typeOut = alloc<CPointerVar<IMFMediaType>>()
            val hr = getNative(reader, MF_SOURCE_READER_FIRST_VIDEO_STREAM, index, typeOut.ptr)
            if (hr == MF_E_NO_MORE_TYPES || hr < 0) break
            val type = typeOut.value ?: break
            try {
                val format = readNativeFormat(type)
                if (format != null) formats += format
            } finally {
                releaseMediaType(type)
            }
            index++
        }
        formats.toList()
    }

    /** Reads `width x height @fps` from a native media type, without its pixel layout. */
    private fun readNativeFormat(type: CPointer<IMFMediaType>): CameraFormat? = memScoped {
        val vtbl = type.pointed.lpVtbl!!.pointed
        val sizeOut = alloc<ULongVar>()
        if (vtbl.GetUINT64!!(type, MF_MT_FRAME_SIZE.ptr, sizeOut.ptr) < 0) return null
        val width = (sizeOut.value shr 32).toInt()
        val height = (sizeOut.value and 0xFFFFFFFFuL).toInt()
        if (width <= 0 || height <= 0) return null

        val rateOut = alloc<ULongVar>()
        val frameRate = when {
            vtbl.GetUINT64!!(type, MF_MT_FRAME_RATE.ptr, rateOut.ptr) >= 0 -> frameRateFrom(rateOut.value)
            vtbl.GetUINT64!!(type, MF_MT_FRAME_RATE_RANGE_MAX.ptr, rateOut.ptr) >= 0 -> frameRateFrom(rateOut.value)
            else -> CameraFormat.DEFAULT_FRAME_RATE
        }
        CameraFormat(width, height, PixelFormat.NV12, frameRate)
    }

    private data class MfDevice(
        val id: String,
        val name: String,
        val lensFacing: CameraLensFacing,
        val transport: CameraTransport,
        val formats: List<CameraFormat>,
    )

    private companion object {
        /** Guards against drivers that never return `MF_E_NO_MORE_TYPES`. */
        const val MAX_NATIVE_MEDIA_TYPES = 256u
    }
}

/** The capture session: opens the source reader on a worker and copies NV12 samples. */
private class MediaFoundationCameraSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, mfSelectFormat(camera, config)) {

    private val requestedFormat: CameraFormat = mfSelectFormat(camera, config)
    private val ioLock = PlatformLock()

    private var worker: Worker? = null
    private var future: Future<Unit>? = null
    private var reader: CPointer<IMFSourceReader>? = null
    private var started = false

    override fun startCapture() {
        if (started) return
        started = true
        val worker = Worker.start()
        this.worker = worker
        try {
            this.future = worker.execute(TransferMode.SAFE, { this }) { session -> session.captureLoop() }
        } catch (error: Throwable) {
            started = false
            this.worker = null
            future = null
            worker.requestTermination()
            throw error as? CameraException
                ?: CameraException("cannot start ${camera.name}: ${error.message}", error)
        }
    }

    override fun stopCapture() {
        if (!started) return
        started = false
        flushReader()
        runCatching { future?.result }
        future = null
        worker?.requestTermination()
        worker = null
    }

    override fun release() = Unit

    /**
     * Unblocks a pending `ReadSample` so the worker can exit promptly. The
     * reader is only released by the worker, while this lock is held, so the
     * flush can never race with teardown.
     */
    private fun flushReader() {
        ioLock.guard {
            val current = reader ?: return@guard
            runCatching { current.pointed.lpVtbl!!.pointed.Flush!!(current, MF_SOURCE_READER_FIRST_VIDEO_STREAM) }
        }
    }

    private fun captureLoop() {
        var comInitialized = false
        var opened: MfOpenedReader? = null
        try {
            // Media Foundation marshals its objects through COM; the worker is
            // its own apartment. RPC_E_CHANGED_MODE is harmless (the thread is
            // already initialized), and MFStartup already ran for the process.
            comInitialized = CoInitializeEx(null, COINIT_MULTITHREADED) >= 0
            opened = openSourceReader()
            ioLock.guard { reader = opened.reader }
            updateNegotiatedFormat(opened.format)
            if (!isCaptureActive()) return
            readSamples(opened)
        } catch (error: Throwable) {
            if (isCaptureActive()) {
                reportError(
                    error as? CameraException
                        ?: CameraException("Media Foundation capture failed for ${camera.name}: ${error.message}", error),
                )
            }
        } finally {
            val target = opened
            if (target != null) {
                ioLock.guard {
                    reader = null
                    target.close()
                }
            }
            if (comInitialized) CoUninitialize()
        }
    }

    private fun openSourceReader(): MfOpenedReader = memScoped {
        val attributesOut = alloc<CPointerVar<IMFAttributes>>()
        checkHr(MFCreateAttributes(attributesOut.ptr, 2u), "MFCreateAttributes")
        val attributes = attributesOut.value
            ?: throw CameraException("MFCreateAttributes returned no attribute store")
        try {
            // Without this the reader only offers the camera's native layout.
            attributes.pointed.lpVtbl!!.pointed.SetUINT32!!(
                attributes,
                MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING.ptr,
                1u,
            )
            val activate = acquireMatchingActivate(camera.id)
                ?: throw CameraException("Media Foundation camera ${camera.name} is no longer attached")
            try {
                val sourceOut = alloc<COpaquePointerVar>()
                checkHr(
                    activate.pointed.lpVtbl!!.pointed.ActivateObject!!(
                        activate,
                        IID_IMFMediaSource.ptr,
                        sourceOut.ptr,
                    ),
                    "IMFActivate::ActivateObject for ${camera.name}",
                )
                val source = sourceOut.value?.reinterpret<IMFMediaSource>()
                    ?: throw CameraException("ActivateObject returned no media source for ${camera.name}")
                val readerOut = alloc<CPointerVar<IMFSourceReader>>()
                try {
                    checkHr(
                        MfSourceReaderFactory.create(source, attributes, readerOut.ptr),
                        "MFCreateSourceReaderFromMediaSource for ${camera.name}",
                    )
                } finally {
                    // The source reader holds its own reference to the source.
                    releaseMediaSource(source)
                }
                val sourceReader = readerOut.value
                    ?: throw CameraException(
                        "MFCreateSourceReaderFromMediaSource returned no reader for ${camera.name}",
                    )
                try {
                    return@memScoped openOutput(sourceReader)
                } catch (error: Throwable) {
                    releaseSourceReader(sourceReader)
                    throw error
                }
            } finally {
                releaseActivate(activate)
            }
        } finally {
            attributes.pointed.lpVtbl!!.pointed.Release!!(attributes)
        }
    }

    /** Negotiates NV12 for the requested size and reads back what the device chose. */
    private fun openOutput(sourceReader: CPointer<IMFSourceReader>): MfOpenedReader {
        sourceReader.pointed.lpVtbl!!.pointed.SetStreamSelection!!(
            sourceReader,
            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
            1,
        )
        val sized = setOutputType(sourceReader, requestedFormat, includeFrameSize = true)
        if (sized < 0) {
            val plain = setOutputType(sourceReader, requestedFormat, includeFrameSize = false)
            if (plain < 0) {
                throw CameraException(
                    "${camera.name} cannot deliver NV12 " +
                        "(${requestedFormat.width}x${requestedFormat.height}): ${describeHresult(plain)}",
                )
            }
        }
        return readNegotiatedOutput(sourceReader)
    }

    private fun setOutputType(
        sourceReader: CPointer<IMFSourceReader>,
        format: CameraFormat,
        includeFrameSize: Boolean,
    ): Int = memScoped {
        val typeOut = alloc<CPointerVar<IMFMediaType>>()
        checkHr(MFCreateMediaType(typeOut.ptr), "MFCreateMediaType")
        val type = typeOut.value ?: throw CameraException("MFCreateMediaType returned no media type")
        try {
            val typeVtbl = type.pointed.lpVtbl!!.pointed
            typeVtbl.SetGUID!!(type, MF_MT_MAJOR_TYPE.ptr, MFMediaType_Video.ptr)
            typeVtbl.SetGUID!!(type, MF_MT_SUBTYPE.ptr, MFVideoFormat_NV12.ptr)
            if (includeFrameSize) {
                typeVtbl.SetUINT64!!(
                    type,
                    MF_MT_FRAME_SIZE.ptr,
                    (format.width.toULong() shl 32) or format.height.toULong(),
                )
            }
            sourceReader.pointed.lpVtbl!!.pointed.SetCurrentMediaType!!(
                sourceReader,
                MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                null,
                type,
            )
        } finally {
            releaseMediaType(type)
        }
    }

    private fun readNegotiatedOutput(sourceReader: CPointer<IMFSourceReader>): MfOpenedReader = memScoped {
        val vtbl = sourceReader.pointed.lpVtbl!!.pointed
        val typeOut = alloc<CPointerVar<IMFMediaType>>()
        checkHr(
            vtbl.GetCurrentMediaType!!(sourceReader, MF_SOURCE_READER_FIRST_VIDEO_STREAM, typeOut.ptr),
            "IMFSourceReader::GetCurrentMediaType for ${camera.name}",
        )
        val type = typeOut.value
            ?: throw CameraException("GetCurrentMediaType returned no media type for ${camera.name}")
        try {
            val typeVtbl = type.pointed.lpVtbl!!.pointed
            val subtype = alloc<_GUID>()
            checkHr(
                typeVtbl.GetGUID!!(type, MF_MT_SUBTYPE.ptr, subtype.ptr),
                "IMFMediaType::GetGUID(MF_MT_SUBTYPE)",
            )
            if (memcmp(subtype.ptr, MFVideoFormat_NV12.ptr, sizeOf<_GUID>().toULong()) != 0) {
                throw CameraException("${camera.name} negotiated a non-NV12 output type")
            }

            val sizeOut = alloc<ULongVar>()
            checkHr(
                typeVtbl.GetUINT64!!(type, MF_MT_FRAME_SIZE.ptr, sizeOut.ptr),
                "IMFMediaType::GetUINT64(MF_MT_FRAME_SIZE)",
            )
            val width = (sizeOut.value shr 32).toInt()
            val height = (sizeOut.value and 0xFFFFFFFFuL).toInt()
            if (width <= 0 || height <= 0) {
                throw CameraException("${camera.name} reported an invalid frame size ${width}x$height")
            }

            val strideOut = alloc<UIntVar>()
            // MF_MT_DEFAULT_STRIDE is signed; a negative value is a bottom-up
            // image, the byte distance is still its magnitude.
            val reportedStride = if (typeVtbl.GetUINT32!!(type, MF_MT_DEFAULT_STRIDE.ptr, strideOut.ptr) >= 0) {
                strideOut.value.toInt()
            } else {
                0
            }
            val stride = when {
                reportedStride > 0 -> reportedStride
                reportedStride < 0 -> -reportedStride
                else -> width
            }

            val rateOut = alloc<ULongVar>()
            val frameRate = if (typeVtbl.GetUINT64!!(type, MF_MT_FRAME_RATE.ptr, rateOut.ptr) >= 0) {
                frameRateFrom(rateOut.value)
            } else {
                requestedFormat.frameRate
            }

            MfOpenedReader(sourceReader, width, height, stride, frameRate)
        } finally {
            releaseMediaType(type)
        }
    }

    private fun readSamples(opened: MfOpenedReader) {
        val sourceReader = opened.reader
        val vtbl = sourceReader.pointed.lpVtbl!!.pointed
        memScoped {
            val actualStream = alloc<UIntVar>()
            val flags = alloc<UIntVar>()
            val timestamp = alloc<LongVar>()
            val sampleOut = alloc<CPointerVar<IMFSample>>()
            while (isCaptureActive()) {
                sampleOut.value = null
                val hr = vtbl.ReadSample!!(
                    sourceReader,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                    0u,
                    actualStream.ptr,
                    flags.ptr,
                    timestamp.ptr,
                    sampleOut.ptr,
                )
                if (hr < 0) {
                    // Flush makes a pending ReadSample fail; that is the normal
                    // path when the session is being stopped.
                    if (!isCaptureActive()) break
                    throw CameraException("${camera.name}: ReadSample failed (${describeHresult(hr)})")
                }
                if ((flags.value and MF_SOURCE_READERF_ERROR) != 0u) {
                    throw CameraException("${camera.name}: Media Foundation reported a capture error")
                }
                val sample = sampleOut.value
                if (sample != null) {
                    try {
                        copySample(sample, opened, timestamp.value)?.let { frame -> publish(frame) }
                    } finally {
                        sample.pointed.lpVtbl!!.pointed.Release!!(sample)
                    }
                }
                if ((flags.value and MF_SOURCE_READERF_ENDOFSTREAM) != 0u) {
                    if (isCaptureActive()) {
                        throw CameraException("${camera.name} stopped delivering frames")
                    }
                    break
                }
            }
        }
    }

    /** Copies the sample's contiguous buffer and wraps it as an NV12 [SourceFrame]. */
    private fun copySample(
        sample: CPointer<IMFSample>,
        opened: MfOpenedReader,
        timestampTicks: Long,
    ): SourceFrame? = memScoped {
        val bufferOut = alloc<CPointerVar<IMFMediaBuffer>>()
        if (sample.pointed.lpVtbl!!.pointed.ConvertToContiguousBuffer!!(sample, bufferOut.ptr) < 0) {
            return null
        }
        val buffer = bufferOut.value ?: return null
        try {
            val dataOut = alloc<CPointerVar<UByteVar>>()
            val maxLength = alloc<UIntVar>()
            val currentLength = alloc<UIntVar>()
            val lockHr = buffer.pointed.lpVtbl!!.pointed.Lock!!(
                buffer,
                dataOut.ptr,
                maxLength.ptr,
                currentLength.ptr,
            )
            if (lockHr < 0) return null
            try {
                val pointer = dataOut.value ?: return null
                val length = currentLength.value.toInt()
                if (length <= 0) return null
                return buildFrame(pointer.readBytes(length), opened, timestampTicks)
            } finally {
                buffer.pointed.lpVtbl!!.pointed.Unlock!!(buffer)
            }
        } finally {
            buffer.pointed.lpVtbl!!.pointed.Release!!(buffer)
        }
    }

    private fun buildFrame(bytes: ByteArray, opened: MfOpenedReader, timestampTicks: Long): SourceFrame? {
        val width = opened.width
        val height = opened.height
        val chromaHeight = (height + 1) / 2
        val padding = opened.stride * height + opened.stride * chromaHeight
        // Some drivers hand out a tight buffer even though they report a larger
        // stride; in that case the padded layout does not fit, so use the
        // packed stride instead of reading past the buffer.
        val stride = if (opened.stride >= width && bytes.size >= padding) opened.stride else width
        val lumaSize = stride * height
        val required = lumaSize + stride * chromaHeight
        if (bytes.size < required) return null

        val timestampNanos = if (timestampTicks > 0) timestampTicks * 100L else monotonicNanos()
        return SourceFrame(
            width = width,
            height = height,
            pixelFormat = PixelFormat.NV12,
            planes = listOf(
                SourcePlane(bytes, 0, stride, 1, width, height),
                SourcePlane(bytes, lumaSize, stride, 2, (width + 1) / 2, chromaHeight),
            ),
            timestampNanos = timestampNanos,
        )
    }
}

/** A configured source reader, with the mode the device negotiated. */
private class MfOpenedReader(
    val reader: CPointer<IMFSourceReader>,
    val width: Int,
    val height: Int,
    val stride: Int,
    val frameRate: Int,
) {
    val format: CameraFormat = CameraFormat(width, height, PixelFormat.NV12, frameRate)

    fun close() {
        releaseSourceReader(reader)
    }
}

/**
 * Process-wide Media Foundation lifetime.
 *
 * `MFStartup`/`MFShutdown` are reference counted so that several camera
 * systems can coexist and the platform is only torn down after the last one
 * closes. COM is initialized (MTA) on the same thread for the duration.
 */
private object MfRuntime {
    private val lock = PlatformLock()
    private var references = 0
    private var comInitialized = false

    fun acquire() {
        lock.guard {
            if (references == 0) {
                val comResult = CoInitializeEx(null, COINIT_MULTITHREADED)
                comInitialized = comResult >= 0
                val hr = MFStartup(MF_VERSION.toUInt(), MFSTARTUP_FULL.toUInt())
                if (hr < 0) {
                    if (comInitialized) {
                        CoUninitialize()
                        comInitialized = false
                    }
                    throw CameraException(
                        "MFStartup failed (${describeHresult(hr)}); Media Foundation is not available on this system",
                    )
                }
            }
            references++
        }
    }

    fun release() {
        lock.guard {
            if (references == 0) return
            references--
            if (references == 0) {
                MFShutdown()
                if (comInitialized) {
                    CoUninitialize()
                    comInitialized = false
                }
            }
        }
    }
}

/**
 * Loads `MFCreateSourceReaderFromMediaSource` from `mfreadwrite.dll`.
 *
 * MinGW ships no import library for mfreadwrite, so the entry point is resolved
 * on first use. The module stays loaded for the process lifetime, exactly like
 * the Media Foundation runtime itself.
 */
private typealias CreateSourceReaderFunction = CFunction<(
    CPointer<IMFMediaSource>?,
    CPointer<IMFAttributes>?,
    CPointer<CPointerVar<IMFSourceReader>>?,
) -> Int>

private object MfSourceReaderFactory {
    private val lock = PlatformLock()
    private var module: HMODULE? = null
    private var function: CPointer<CreateSourceReaderFunction>? = null
    private var failure: String? = null

    fun create(
        source: CPointer<IMFMediaSource>,
        attributes: CPointer<IMFAttributes>?,
        reader: CPointer<CPointerVar<IMFSourceReader>>,
    ): Int = lock.guard {
        val resolved = function ?: resolve()
        resolved(source, attributes, reader)
    }

    private fun resolve(): CPointer<CreateSourceReaderFunction> = memScoped {
        val loaded = module
        if (loaded != null) return@memScoped load(loaded)
        val handle = LoadLibraryW("mfreadwrite.dll")
        if (handle == null) {
            failure = "cannot load mfreadwrite.dll; Media Foundation is not installed"
            throw CameraException(failure!!)
        }
        module = handle
        load(handle)
    }

    private fun load(handle: HMODULE): CPointer<CreateSourceReaderFunction> = memScoped {
        function?.let { return@memScoped it }
        failure?.let { throw CameraException(it) }
        val address = GetProcAddress(handle, "MFCreateSourceReaderFromMediaSource")
        if (address == null) {
            failure = "mfreadwrite.dll does not export MFCreateSourceReaderFromMediaSource"
            throw CameraException(failure!!)
        }
        val resolved = address.reinterpret<CreateSourceReaderFunction>()
        function = resolved
        resolved
    }
}

/** Picks the NV12 mode closest to the requested frame size. */
private fun mfSelectFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val requested = CameraFormat(
        width = config.format.width,
        height = config.format.height,
        pixelFormat = PixelFormat.NV12,
        frameRate = config.format.frameRate,
    )
    return camera.bestFormat(
        width = requested.width,
        height = requested.height,
        pixelFormat = PixelFormat.NV12,
        frameRate = requested.frameRate,
    ) ?: requested
}

/** Enumerates the video capture activations, each with one reference for the caller. */
private fun acquireActivates(): List<CPointer<IMFActivate>> = memScoped {
    val attributesOut = alloc<CPointerVar<IMFAttributes>>()
    if (MFCreateAttributes(attributesOut.ptr, 1u) < 0) return emptyList()
    val attributes = attributesOut.value ?: return emptyList()
    var array: CPointer<CPointerVar<IMFActivate>>? = null
    try {
        // MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE has the same GUID value as
        // MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID; MinGW only exports
        // the latter, which is why it is used as both key and value here.
        val guidHr = attributes.pointed.lpVtbl!!.pointed.SetGUID!!(
            attributes,
            MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID.ptr,
            MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID.ptr,
        )
        if (guidHr < 0) return emptyList()
        val arrayOut = alloc<CPointerVar<CPointerVar<IMFActivate>>>()
        val countOut = alloc<UIntVar>()
        if (MFEnumDeviceSources(attributes, arrayOut.ptr, countOut.ptr) < 0) return emptyList()
        array = arrayOut.value
        val count = countOut.value.toInt()
        val result = ArrayList<CPointer<IMFActivate>>(count)
        for (index in 0 until count) {
            // The array owns one reference per activation; ownership moves to
            // the caller, so every entry is released exactly once.
            result += array?.get(index) ?: continue
        }
        result
    } finally {
        attributes.pointed.lpVtbl!!.pointed.Release!!(attributes)
        if (array != null) CoTaskMemFree(array)
    }
}

/** Finds the activation that still matches [id], keeping one reference for the caller. */
private fun acquireMatchingActivate(id: String): CPointer<IMFActivate>? {
    val activates = acquireActivates()
    var match: CPointer<IMFActivate>? = null
    try {
        for ((index, activate) in activates.withIndex()) {
            if (match != null) break
            val symbolicLink = allocatedString(activate, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK.ptr)
            val candidate = symbolicLink?.takeIf { it.isNotBlank() } ?: "mf:vidcap:$index"
            if (candidate == id) match = activate
        }
        return match
    } finally {
        for (activate in activates) {
            if (activate != match) releaseActivate(activate)
        }
    }
}

private fun allocatedString(activate: CPointer<IMFActivate>, key: CPointer<_GUID>): String? = memScoped {
    val valueOut = alloc<CPointerVar<UShortVar>>()
    val lengthOut = alloc<UIntVar>()
    val hr = activate.pointed.lpVtbl!!.pointed.GetAllocatedString!!(
        activate,
        key,
        valueOut.ptr,
        lengthOut.ptr,
    )
    if (hr < 0) return null
    val value = valueOut.value ?: return null
    try {
        value.toKStringFromUtf16()
    } finally {
        CoTaskMemFree(value)
    }
}

private fun classifyTransport(symbolicLink: String?, name: String): CameraTransport {
    val link = symbolicLink?.lowercase().orEmpty()
    val label = name.lowercase()
    return when {
        link.contains("usb#") -> CameraTransport.USB
        label.contains("virtual") || label.contains("obs") || label.contains("snap camera") -> CameraTransport.VIRTUAL
        link.contains("swdevice") || link.contains("root#") -> CameraTransport.INTERNAL
        else -> CameraTransport.UNKNOWN
    }
}

private fun lensFacingFor(transport: CameraTransport): CameraLensFacing = when (transport) {
    CameraTransport.USB, CameraTransport.VIRTUAL -> CameraLensFacing.EXTERNAL
    else -> CameraLensFacing.UNKNOWN
}

/** Reads a frame rate packed as high 32 bits numerator, low 32 bits denominator. */
private fun frameRateFrom(value: ULong): Int {
    val numerator = (value shr 32).toInt()
    val denominator = (value and 0xFFFFFFFFuL).toInt()
    if (numerator <= 0 || denominator <= 0) return CameraFormat.DEFAULT_FRAME_RATE
    return ((numerator + denominator / 2) / denominator).coerceAtLeast(1)
}

private fun releaseActivate(activate: CPointer<IMFActivate>?) {
    val target = activate ?: return
    target.pointed.lpVtbl!!.pointed.Release!!(target)
}

private fun releaseMediaSource(source: CPointer<IMFMediaSource>?) {
    val target = source ?: return
    target.pointed.lpVtbl!!.pointed.Release!!(target)
}

private fun releaseSourceReader(reader: CPointer<IMFSourceReader>?) {
    val target = reader ?: return
    target.pointed.lpVtbl!!.pointed.Release!!(target)
}

private fun releaseMediaType(type: CPointer<IMFMediaType>?) {
    val target = type ?: return
    target.pointed.lpVtbl!!.pointed.Release!!(target)
}

private fun checkHr(hr: Int, operation: String) {
    if (hr < 0) throw CameraException("$operation failed (${describeHresult(hr)})")
}

private fun describeHresult(hr: Int): String {
    val code = hr.toUInt()
    val hex = "0x" + code.toString(16).padStart(8, '0')
    val hint = when (code) {
        // HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED); the Windows camera privacy
        // setting blocks desktop applications by default on some machines.
        0x80070005u -> "access denied; allow desktop apps to use the camera in Windows privacy settings"
        // MF_E_HW_MFT_FAILED_START_STREAMING
        0xC00D3704u -> "the camera is in use by another application"
        // MF_E_SHUTDOWN
        0xC00D3E85u -> "Media Foundation is shut down"
        MF_E_INVALIDMEDIATYPE.toUInt() -> "the camera rejected the media type"
        MF_E_NO_MORE_TYPES.toUInt() -> "no more media types are available"
        MF_E_ATTRIBUTENOTFOUND.toUInt() -> "the camera did not report the requested attribute"
        else -> null
    }
    return if (hint == null) "HRESULT $hex" else "$hint ($hex)"
}

/**
 * Initializes COM on the calling thread for the duration of [block].
 *
 * Media Foundation objects are marshalled per apartment; a caller may
 * enumerate cameras from any thread, not just the one that created the system.
 */
private inline fun <T> withComApartment(block: () -> T): T {
    val initialized = CoInitializeEx(null, COINIT_MULTITHREADED) >= 0
    try {
        return block()
    } finally {
        if (initialized) CoUninitialize()
    }
}

actual fun availableCameraBackends(): List<CameraBackend> = listOf(
    CameraBackend.AUTO,
    CameraBackend.MEDIA_FOUNDATION,
)

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO, CameraBackend.MEDIA_FOUNDATION ->
        MediaFoundationCameraSystem(CameraBackend.MEDIA_FOUNDATION)

    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available on Windows; use Auto or Media Foundation",
    )
}
