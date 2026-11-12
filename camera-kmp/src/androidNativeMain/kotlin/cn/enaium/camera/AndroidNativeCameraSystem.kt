@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import cn.enaium.camera.camera2.ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
import cn.enaium.camera.camera2.ACAMERA_ERROR_CAMERA_DEVICE
import cn.enaium.camera.camera2.ACAMERA_ERROR_CAMERA_DISABLED
import cn.enaium.camera.camera2.ACAMERA_ERROR_CAMERA_DISCONNECTED
import cn.enaium.camera.camera2.ACAMERA_ERROR_CAMERA_IN_USE
import cn.enaium.camera.camera2.ACAMERA_ERROR_CAMERA_SERVICE
import cn.enaium.camera.camera2.ACAMERA_ERROR_INVALID_PARAMETER
import cn.enaium.camera.camera2.ACAMERA_ERROR_MAX_CAMERA_IN_USE
import cn.enaium.camera.camera2.ACAMERA_ERROR_NOT_ENOUGH_MEMORY
import cn.enaium.camera.camera2.ACAMERA_ERROR_PERMISSION_DENIED
import cn.enaium.camera.camera2.ACAMERA_ERROR_STREAM_CONFIGURE_FAIL
import cn.enaium.camera.camera2.ACAMERA_ERROR_UNSUPPORTED_OPERATION
import cn.enaium.camera.camera2.ACAMERA_LENS_FACING
import cn.enaium.camera.camera2.ACAMERA_LENS_FACING_BACK
import cn.enaium.camera.camera2.ACAMERA_LENS_FACING_EXTERNAL
import cn.enaium.camera.camera2.ACAMERA_LENS_FACING_FRONT
import cn.enaium.camera.camera2.ACAMERA_OK
import cn.enaium.camera.camera2.ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS
import cn.enaium.camera.camera2.ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS
import cn.enaium.camera.camera2.ACAMERA_SENSOR_ORIENTATION
import cn.enaium.camera.camera2.ACameraCaptureSession_close
import cn.enaium.camera.camera2.ACameraCaptureSession_setRepeatingRequest
import cn.enaium.camera.camera2.ACameraCaptureSession_stateCallbacks
import cn.enaium.camera.camera2.ACameraDevice_StateCallbacks
import cn.enaium.camera.camera2.ACameraDevice_close
import cn.enaium.camera.camera2.ACameraDevice_createCaptureRequest
import cn.enaium.camera.camera2.ACameraDevice_createCaptureSession
import cn.enaium.camera.camera2.ACameraIdList
import cn.enaium.camera.camera2.ACameraManager_create
import cn.enaium.camera.camera2.ACameraManager_delete
import cn.enaium.camera.camera2.ACameraManager_deleteCameraIdList
import cn.enaium.camera.camera2.ACameraManager_getCameraCharacteristics
import cn.enaium.camera.camera2.ACameraManager_getCameraIdList
import cn.enaium.camera.camera2.ACameraManager_openCamera
import cn.enaium.camera.camera2.ACameraMetadata_const_entry
import cn.enaium.camera.camera2.ACameraMetadata_getConstEntry
import cn.enaium.camera.camera2.ACameraMetadata_free
import cn.enaium.camera.camera2.ACameraOutputTarget_create
import cn.enaium.camera.camera2.ACameraOutputTarget_free
import cn.enaium.camera.camera2.ACaptureRequest_addTarget
import cn.enaium.camera.camera2.ACaptureRequest_free
import cn.enaium.camera.camera2.ACaptureSessionOutputContainer_add
import cn.enaium.camera.camera2.ACaptureSessionOutputContainer_create
import cn.enaium.camera.camera2.ACaptureSessionOutputContainer_free
import cn.enaium.camera.camera2.ACaptureSessionOutput_create
import cn.enaium.camera.camera2.ACaptureSessionOutput_free
import cn.enaium.camera.camera2.AIMAGE_FORMAT_YUV_420_888
import cn.enaium.camera.camera2.AImage_delete
import cn.enaium.camera.camera2.AImage_getHeight
import cn.enaium.camera.camera2.AImage_getNumberOfPlanes
import cn.enaium.camera.camera2.AImage_getPlaneData
import cn.enaium.camera.camera2.AImage_getPlanePixelStride
import cn.enaium.camera.camera2.AImage_getPlaneRowStride
import cn.enaium.camera.camera2.AImage_getTimestamp
import cn.enaium.camera.camera2.AImage_getWidth
import cn.enaium.camera.camera2.AImageReader_acquireLatestImage
import cn.enaium.camera.camera2.AImageReader_delete
import cn.enaium.camera.camera2.AImageReader_getWindow
import cn.enaium.camera.camera2.AImageReader_new
import cn.enaium.camera.camera2.TEMPLATE_RECORD
import cnames.structs.ACameraCaptureSession
import cnames.structs.ACameraDevice
import cnames.structs.ACameraManager
import cnames.structs.ACameraMetadata
import cnames.structs.ACameraOutputTarget
import cnames.structs.ACaptureRequest
import cnames.structs.ACaptureSessionOutput
import cnames.structs.ACaptureSessionOutputContainer
import cnames.structs.AImage
import cnames.structs.AImageReader
import cnames.structs.ANativeWindow
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import platform.posix.memcpy
import platform.posix.usleep

/** Camera2 NDK buffers the reader keeps alive; three or more are required for a steady stream. */
private const val IMAGE_BUFFER_COUNT = 4

/** Idle wait between two image reader polls, in microseconds. */
private const val IMAGE_POLL_INTERVAL_MICROS = 1_000u

/** How many advertised stream configurations are tried before a session fails to start. */
private const val MAX_CONFIGURATION_ATTEMPTS = 4

/** Frame rate advertised when the camera exposes no frame duration/rate metadata at all. */
private const val FALLBACK_FRAME_RATE = 30

/** Resolutions advertised when the camera exposes no stream configuration metadata at all. */
private val FALLBACK_RESOLUTIONS = listOf(640 to 480, 1280 to 720, 1920 to 1080)

actual fun availableCameraBackends(): List<CameraBackend> = listOf(
    CameraBackend.AUTO,
    CameraBackend.CAMERA2,
    CameraBackend.V4L2,
    CameraBackend.UVC,
)

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO -> AndroidNativeCameraSystem()
    CameraBackend.CAMERA2 -> Camera2NdkCameraSystem(CameraBackend.CAMERA2)
    CameraBackend.V4L2 -> V4l2CameraSystem(CameraBackend.V4L2)
    CameraBackend.UVC -> V4l2CameraSystem(CameraBackend.UVC)
    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available on Android: use AUTO, CAMERA2, V4L2 or UVC",
    )
}

/**
 * The Android Native default: Camera2 through the NDK for the cameras the
 * camera service owns, V4L2 for external cameras when the NDK manager lists
 * none.
 */
private class AndroidNativeCameraSystem : CameraSystem {
    override val backend: CameraBackend = CameraBackend.AUTO
    override val name: String = "Android Native (Camera2 NDK)"

    override fun cameras(): List<CameraInfo> {
        val ndkCameras = Camera2NdkCameraSystem(CameraBackend.AUTO).cameras()
        if (ndkCameras.isNotEmpty()) return ndkCameras
        return V4l2CameraSystem(CameraBackend.AUTO).cameras()
    }

    override fun defaultCamera(lensFacing: CameraLensFacing): CameraInfo? {
        val all = cameras()
        return all.firstOrNull { it.lensFacing == lensFacing } ?: all.firstOrNull()
    }

    override fun open(config: CameraConfig): CameraSession {
        val ndkCameras = Camera2NdkCameraSystem(CameraBackend.AUTO).cameras()
        return if (ndkCameras.isNotEmpty()) {
            Camera2NdkCameraSystem(CameraBackend.AUTO).open(config)
        } else {
            V4l2CameraSystem(CameraBackend.AUTO).open(config)
        }
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession = when {
        camera.id.startsWith("/dev/") -> V4l2CameraSystem(CameraBackend.AUTO).open(camera, config)
        else -> Camera2NdkCameraSystem(CameraBackend.AUTO).open(camera, config)
    }

    override fun close() = Unit
}

/** Camera2 through the Android NDK; the cameras are owned by the camera service. */
private class Camera2NdkCameraSystem(
    private val backendKind: CameraBackend,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "Camera2 (NDK)"

    override fun cameras(): List<CameraInfo> {
        val devices = enumerateCamera2Devices().filter { it.formats.isNotEmpty() }
        return devices.mapIndexed { index, device -> device.toCameraInfo(isDefault = index == 0) }
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
            ?: throw CameraException("no Camera2 camera is available through the Android NDK")
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        Camera2NdkSession(camera, config)

    override fun close() = Unit
}

/** One camera as the NDK metadata describes it. */
private class Camera2Device(
    val id: String,
    val facing: CameraLensFacing,
    val orientation: Int,
    val formats: List<CameraFormat>,
) {
    fun toCameraInfo(isDefault: Boolean): CameraInfo = CameraInfo(
        id = id,
        name = "Camera $id (${facingLabel(facing)})",
        lensFacing = facing,
        sensorOrientation = orientation,
        transport = when (facing) {
            CameraLensFacing.EXTERNAL -> CameraTransport.USB
            CameraLensFacing.FRONT, CameraLensFacing.BACK -> CameraTransport.INTERNAL
            CameraLensFacing.UNKNOWN -> CameraTransport.UNKNOWN
        },
        isDefault = isDefault,
        devicePath = null,
        supportedFormats = formats,
    )

    private fun facingLabel(facing: CameraLensFacing): String = when (facing) {
        CameraLensFacing.FRONT -> "front"
        CameraLensFacing.BACK -> "back"
        CameraLensFacing.EXTERNAL -> "external"
        CameraLensFacing.UNKNOWN -> "unknown"
    }
}

private fun enumerateCamera2Devices(): List<Camera2Device> {
    val manager = ACameraManager_create()
        ?: throw CameraException("cannot create the Android NDK camera manager")
    return try {
        memScoped {
            val listPtr = alloc<CPointerVar<ACameraIdList>>()
            val status = ACameraManager_getCameraIdList(manager, listPtr.ptr)
            if (status != ACAMERA_OK) throw cameraStatusError(status, "enumerate cameras")
            val list = listPtr.value
            if (list == null) {
                emptyList()
            } else {
                try {
                    val ids = list.pointed.cameraIds
                    val count = list.pointed.numCameras
                    if (ids == null || count <= 0) {
                        emptyList()
                    } else {
                        val result = ArrayList<Camera2Device>(count)
                        for (index in 0 until count) {
                            val id = ids[index]?.toKString() ?: continue
                            result += readCamera2Device(manager, id) ?: continue
                        }
                        result
                    }
                } finally {
                    ACameraManager_deleteCameraIdList(list)
                }
            }
        }
    } finally {
        ACameraManager_delete(manager)
    }
}

private fun readCamera2Device(manager: CPointer<ACameraManager>, id: String): Camera2Device? {
    val metadata = memScoped {
        val out = alloc<CPointerVar<ACameraMetadata>>()
        if (ACameraManager_getCameraCharacteristics(manager, id, out.ptr) != ACAMERA_OK) return@memScoped null
        out.value
    } ?: return null
    try {
        val facing = when (readByteEntry(metadata, ACAMERA_LENS_FACING)) {
            ACAMERA_LENS_FACING_FRONT.toInt() -> CameraLensFacing.FRONT
            ACAMERA_LENS_FACING_BACK.toInt() -> CameraLensFacing.BACK
            ACAMERA_LENS_FACING_EXTERNAL.toInt() -> CameraLensFacing.EXTERNAL
            else -> CameraLensFacing.UNKNOWN
        }
        val orientation = readIntEntry(metadata, ACAMERA_SENSOR_ORIENTATION) ?: 0
        val formats = readStreamConfigurations(metadata)
        return Camera2Device(id, facing, orientation, formats)
    } finally {
        ACameraMetadata_free(metadata)
    }
}

/**
 * The YUV_420_888 stream configurations the camera supports, one entry per
 * resolution/frame rate pair.
 *
 * The rates come from the minimum frame durations of each stream configuration
 * when the device publishes them, otherwise from the upper bounds of the AE
 * target frame rate ranges. Only when the device publishes neither are the
 * common resolutions/rates of the NDK advertised.
 */
private fun readStreamConfigurations(metadata: CPointer<ACameraMetadata>): List<CameraFormat> {
    val configurations = readIntArrayEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS)
    val durations = readLongArrayEntry(metadata, ACAMERA_SCALER_AVAILABLE_MIN_FRAME_DURATIONS)
    val rates = readAdvertisedFrameRates(metadata)
    val yuvFormat = AIMAGE_FORMAT_YUV_420_888.toInt()

    if (configurations == null) {
        // No stream configuration metadata at all: advertise the NDK's fixed set.
        return FALLBACK_RESOLUTIONS.flatMap { (width, height) ->
            rates.map { fps -> CameraFormat(width, height, PixelFormat.I420, fps) }
        }
    }

    val formats = LinkedHashSet<CameraFormat>()
    var index = 0
    while (index + 3 < configurations.size) {
        val format = configurations[index]
        val width = configurations[index + 1]
        val height = configurations[index + 2]
        val input = configurations[index + 3]
        index += 4
        if (format != yuvFormat || input != 0 || width <= 0 || height <= 0) continue
        val maxFps = maxFrameRate(durations, format, width, height) ?: rates.last()
        val supported = rates.filter { it <= maxFps }.ifEmpty { listOf(maxFps) }
        for (fps in supported) {
            formats += CameraFormat(width, height, PixelFormat.I420, fps)
        }
    }
    return formats.toList()
}

/** Upper bounds of the AE target frame rate ranges, or the common rate when absent. */
private fun readAdvertisedFrameRates(metadata: CPointer<ACameraMetadata>): List<Int> {
    val ranges = readIntArrayEntry(metadata, ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    val rates = ranges?.let { values ->
        (0 until values.size / 2).mapNotNull { range ->
            values[range * 2 + 1].takeIf { it in 1..240 }
        }
    }.orEmpty()
    if (rates.isEmpty()) return listOf(FALLBACK_FRAME_RATE)
    return rates.distinct().sorted()
}

private fun maxFrameRate(
    durations: LongArray?,
    format: Int,
    width: Int,
    height: Int,
): Int? {
    if (durations == null) return null
    var index = 0
    while (index + 3 < durations.size) {
        val entryFormat = durations[index].toInt()
        val entryWidth = durations[index + 1].toInt()
        val entryHeight = durations[index + 2].toInt()
        val durationNanos = durations[index + 3]
        index += 4
        if (entryFormat != format || entryWidth != width || entryHeight != height) continue
        if (durationNanos <= 0L) return null
        return (1_000_000_000.0 / durationNanos.toDouble()).roundToInt().coerceIn(1, 240)
    }
    return null
}

private fun readByteEntry(metadata: CPointer<ACameraMetadata>, tag: UInt): Int? = memScoped {
    val entry = alloc<ACameraMetadata_const_entry>()
    if (ACameraMetadata_getConstEntry(metadata, tag, entry.ptr) != ACAMERA_OK) return@memScoped null
    entry.data.u8?.get(0)?.toInt()
}

private fun readIntEntry(metadata: CPointer<ACameraMetadata>, tag: UInt): Int? = memScoped {
    val entry = alloc<ACameraMetadata_const_entry>()
    if (ACameraMetadata_getConstEntry(metadata, tag, entry.ptr) != ACAMERA_OK) return@memScoped null
    entry.data.i32?.get(0)
}

private fun readIntArrayEntry(metadata: CPointer<ACameraMetadata>, tag: UInt): IntArray? = memScoped {
    val entry = alloc<ACameraMetadata_const_entry>()
    if (ACameraMetadata_getConstEntry(metadata, tag, entry.ptr) != ACAMERA_OK) return@memScoped null
    val pointer = entry.data.i32 ?: return@memScoped null
    val count = entry.count.toInt()
    if (count <= 0) return@memScoped null
    IntArray(count) { pointer[it] }
}

private fun readLongArrayEntry(metadata: CPointer<ACameraMetadata>, tag: UInt): LongArray? = memScoped {
    val entry = alloc<ACameraMetadata_const_entry>()
    if (ACameraMetadata_getConstEntry(metadata, tag, entry.ptr) != ACAMERA_OK) return@memScoped null
    val pointer = entry.data.i64 ?: return@memScoped null
    val count = entry.count.toInt()
    if (count <= 0) return@memScoped null
    LongArray(count) { pointer[it] }
}

private fun cameraStatusError(status: Int, action: String): CameraException = CameraException(
    when (status) {
        ACAMERA_ERROR_PERMISSION_DENIED -> "$action failed: the app has no camera permission"
        ACAMERA_ERROR_CAMERA_IN_USE -> "$action failed: the camera is in use by another client"
        ACAMERA_ERROR_MAX_CAMERA_IN_USE -> "$action failed: the system-wide camera limit is reached"
        ACAMERA_ERROR_CAMERA_DISABLED -> "$action failed: the camera is disabled by device policy"
        ACAMERA_ERROR_CAMERA_DISCONNECTED -> "$action failed: the camera was disconnected"
        ACAMERA_ERROR_NOT_ENOUGH_MEMORY -> "$action failed: not enough memory"
        ACAMERA_ERROR_STREAM_CONFIGURE_FAIL -> "$action failed: the camera rejected the stream configuration"
        ACAMERA_ERROR_CAMERA_DEVICE -> "$action failed: the camera device reported a fatal error"
        ACAMERA_ERROR_CAMERA_SERVICE -> "$action failed: the camera service reported a fatal error"
        ACAMERA_ERROR_UNSUPPORTED_OPERATION -> "$action failed: the operation is not supported"
        ACAMERA_ERROR_INVALID_PARAMETER -> "$action failed: invalid parameter"
        else -> "$action failed with status $status"
    },
)

private fun requireCameraStatus(status: Int, action: String) {
    if (status != ACAMERA_OK) throw cameraStatusError(status, action)
}

private fun requireMediaStatus(status: Int, action: String) {
    if (status != 0) throw CameraException("$action failed with status $status")
}

private fun camera2BaseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat =
    camera.bestFormat(
        config.format.width,
        config.format.height,
        PixelFormat.I420,
        config.format.frameRate,
    ) ?: throw CameraException("${camera.name} advertises no Camera2 YUV stream configuration")

/** The stream configurations to try, closest to the request first. */
private fun captureCandidates(camera: CameraInfo, config: CameraConfig): List<CameraFormat> {
    val targetPixels = config.format.width.toLong() * config.format.height
    val preferred = camera2BaseFormat(camera, config)
    val ordered = camera.supportedFormats
        .distinctBy { Triple(it.width, it.height, it.frameRate) }
        .sortedWith(compareBy({ abs(it.resolution.pixelCount - targetPixels) }, { it.resolution.pixelCount }))
    return (listOf(preferred) + ordered)
        .distinctBy { Triple(it.width, it.height, it.frameRate) }
        .take(MAX_CONFIGURATION_ATTEMPTS)
}

/**
 * Camera2 NDK capture through an [AImageReader] producing YUV_420_888.
 *
 * The manager, device, capture session and image reader are created in
 * [startCapture] (the NDK opens the device synchronously) and released in
 * [stopCapture]. A dedicated worker acquires the latest image, copies its three
 * planes honouring the row/pixel strides and publishes them as an I420 source
 * frame; libyuv handles planar and interleaved chroma in the shared processor.
 */
private class Camera2NdkSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, camera2BaseFormat(camera, config)) {

    private var manager: CPointer<ACameraManager>? = null
    private var device: CPointer<ACameraDevice>? = null
    private var session: CPointer<ACameraCaptureSession>? = null
    private var request: CPointer<ACaptureRequest>? = null
    private var target: CPointer<ACameraOutputTarget>? = null
    private var output: CPointer<ACaptureSessionOutput>? = null
    private var container: CPointer<ACaptureSessionOutputContainer>? = null
    private var reader: CPointer<AImageReader>? = null
    private var window: CPointer<ANativeWindow>? = null
    private var deviceCallbacks: ACameraDevice_StateCallbacks? = null
    private var sessionCallbacks: ACameraCaptureSession_stateCallbacks? = null
    private var stable: StableRef<Camera2NdkSession>? = null
    private var worker: Worker? = null
    private var future: Future<Unit>? = null
    private var started = false
    private var releasing = false

    override fun startCapture() {
        val manager = ACameraManager_create()
            ?: throw CameraException("cannot create the Android NDK camera manager")
        this.manager = manager
        releasing = false

        try {
            val stable = StableRef.create(this)
            this.stable = stable

            val deviceCallbacks = nativeHeap.alloc<ACameraDevice_StateCallbacks>()
            deviceCallbacks.context = stable.asCPointer()
            deviceCallbacks.onDisconnected = staticCFunction(::onNativeCameraDisconnected)
            deviceCallbacks.onError = staticCFunction(::onNativeCameraError)
            this.deviceCallbacks = deviceCallbacks

            val sessionCallbacks = nativeHeap.alloc<ACameraCaptureSession_stateCallbacks>()
            sessionCallbacks.context = stable.asCPointer()
            sessionCallbacks.onClosed = staticCFunction(::onNativeSessionClosed)
            sessionCallbacks.onReady = staticCFunction(::onNativeSessionReady)
            sessionCallbacks.onActive = staticCFunction(::onNativeSessionActive)
            this.sessionCallbacks = sessionCallbacks

            val opened = memScoped {
                val devicePtr = alloc<CPointerVar<ACameraDevice>>()
                requireCameraStatus(
                    ACameraManager_openCamera(manager, camera.id, deviceCallbacks.ptr, devicePtr.ptr),
                    "open camera ${camera.id}",
                )
                devicePtr.value ?: throw CameraException("the NDK returned no device for ${camera.id}")
            }
            device = opened

            var failure: String? = null
            for (candidate in captureCandidates(camera, config)) {
                val pending = try {
                    configure(opened, candidate, sessionCallbacks)
                } catch (error: CameraException) {
                    failure = error.message
                    continue
                }
                adopt(pending)
                updateNegotiatedFormat(candidate)
                started = true
                break
            }
            if (!started) {
                throw CameraException(
                    "cannot configure a Camera2 stream on ${camera.name}: ${failure ?: "no advertised mode was usable"}",
                )
            }

            val worker = Worker.start()
            this.worker = worker
            this.future = worker.execute(TransferMode.SAFE, { this }) { session -> session.captureLoop() }
        } catch (error: Throwable) {
            started = false
            // A worker only exists here when it never started its job, so no frame
            // reader is running against the resources released below.
            future = null
            worker?.requestTermination()
            worker = null
            releaseNative()
            throw error as? CameraException
                ?: CameraException("cannot start ${camera.name}: ${error.message}", error)
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

    /** Opens a reader and capture session for [candidate]; releases everything if it fails. */
    private fun configure(
        device: CPointer<ACameraDevice>,
        candidate: CameraFormat,
        sessionCallbacks: ACameraCaptureSession_stateCallbacks,
    ): PendingSetup {
        val pending = PendingSetup()
        try {
            memScoped {
                val readerPtr = alloc<CPointerVar<AImageReader>>()
                requireMediaStatus(
                    AImageReader_new(
                        candidate.width,
                        candidate.height,
                        AIMAGE_FORMAT_YUV_420_888.toInt(),
                        IMAGE_BUFFER_COUNT,
                        readerPtr.ptr,
                    ),
                    "AImageReader_new",
                )
                val reader = readerPtr.value
                    ?: throw CameraException("AImageReader_new returned no reader")
                pending.reader = reader

                val windowPtr = alloc<CPointerVar<ANativeWindow>>()
                requireMediaStatus(AImageReader_getWindow(reader, windowPtr.ptr), "AImageReader_getWindow")
                val window = windowPtr.value
                    ?: throw CameraException("AImageReader_getWindow returned no window")
                pending.window = window

                val outputPtr = alloc<CPointerVar<ACaptureSessionOutput>>()
                requireCameraStatus(ACaptureSessionOutput_create(window, outputPtr.ptr), "ACaptureSessionOutput_create")
                val output = outputPtr.value
                    ?: throw CameraException("ACaptureSessionOutput_create returned no output")
                pending.output = output

                val containerPtr = alloc<CPointerVar<ACaptureSessionOutputContainer>>()
                requireCameraStatus(
                    ACaptureSessionOutputContainer_create(containerPtr.ptr),
                    "ACaptureSessionOutputContainer_create",
                )
                val container = containerPtr.value
                    ?: throw CameraException("ACaptureSessionOutputContainer_create returned no container")
                pending.container = container
                requireCameraStatus(
                    ACaptureSessionOutputContainer_add(container, output),
                    "ACaptureSessionOutputContainer_add",
                )

                val sessionPtr = alloc<CPointerVar<ACameraCaptureSession>>()
                requireCameraStatus(
                    ACameraDevice_createCaptureSession(device, container, sessionCallbacks.ptr, sessionPtr.ptr),
                    "ACameraDevice_createCaptureSession",
                )
                val session = sessionPtr.value
                    ?: throw CameraException("ACameraDevice_createCaptureSession returned no session")
                pending.session = session

                val requestPtr = alloc<CPointerVar<ACaptureRequest>>()
                requireCameraStatus(
                    ACameraDevice_createCaptureRequest(device, TEMPLATE_RECORD, requestPtr.ptr),
                    "ACameraDevice_createCaptureRequest",
                )
                val request = requestPtr.value
                    ?: throw CameraException("ACameraDevice_createCaptureRequest returned no request")
                pending.request = request

                val targetPtr = alloc<CPointerVar<ACameraOutputTarget>>()
                requireCameraStatus(ACameraOutputTarget_create(window, targetPtr.ptr), "ACameraOutputTarget_create")
                val target = targetPtr.value
                    ?: throw CameraException("ACameraOutputTarget_create returned no target")
                pending.target = target

                requireCameraStatus(ACaptureRequest_addTarget(request, target), "ACaptureRequest_addTarget")

                val requests = alloc<CPointerVar<ACaptureRequest>>()
                requests.value = request
                requireCameraStatus(
                    ACameraCaptureSession_setRepeatingRequest(session, null, 1, requests.ptr, null),
                    "ACameraCaptureSession_setRepeatingRequest",
                )
            }
            return pending
        } catch (error: Throwable) {
            pending.release()
            throw error as? CameraException
                ?: CameraException("cannot configure ${candidate.width}x${candidate.height}: ${error.message}", error)
        }
    }

    private fun adopt(pending: PendingSetup) {
        reader = pending.reader
        window = pending.window
        output = pending.output
        container = pending.container
        session = pending.session
        request = pending.request
        target = pending.target
        pending.clear()
    }

    private fun releaseNative() {
        releasing = true
        session?.let { runCatching { ACameraCaptureSession_close(it) } }
        session = null
        request?.let { runCatching { ACaptureRequest_free(it) } }
        request = null
        target?.let { runCatching { ACameraOutputTarget_free(it) } }
        target = null
        output?.let { runCatching { ACaptureSessionOutput_free(it) } }
        output = null
        container?.let { runCatching { ACaptureSessionOutputContainer_free(it) } }
        container = null
        device?.let { runCatching { ACameraDevice_close(it) } }
        device = null
        reader?.let { runCatching { AImageReader_delete(it) } }
        reader = null
        window = null
        manager?.let { runCatching { ACameraManager_delete(it) } }
        manager = null
        sessionCallbacks?.let { nativeHeap.free(it.rawPtr) }
        sessionCallbacks = null
        deviceCallbacks?.let { nativeHeap.free(it.rawPtr) }
        deviceCallbacks = null
        stable?.dispose()
        stable = null
    }

    /** Worker body: copies the newest image and publishes it while capture is active. */
    private fun captureLoop() {
        while (isCaptureActive()) {
            val reader = reader ?: return
            val image = acquireImage(reader)
            if (image == null) {
                usleep(IMAGE_POLL_INTERVAL_MICROS)
                continue
            }
            try {
                publishImage(image)
            } catch (ignored: Throwable) {
                // A frame that cannot be copied is dropped; the next one is tried immediately.
            } finally {
                AImage_delete(image)
            }
        }
    }

    private fun acquireImage(reader: CPointer<AImageReader>): CPointer<AImage>? = memScoped {
        val imagePtr = alloc<CPointerVar<AImage>>()
        if (AImageReader_acquireLatestImage(reader, imagePtr.ptr) != 0) null else imagePtr.value
    }

    private fun publishImage(image: CPointer<AImage>) {
        val width = readImageValue { AImage_getWidth(image, it.ptr) }
        val height = readImageValue { AImage_getHeight(image, it.ptr) }
        if (width <= 0 || height <= 0) return
        if (readImageValue { AImage_getNumberOfPlanes(image, it.ptr) } < 3) return

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val y = copyImagePlane(image, 0, width, height) ?: return
        val u = copyImagePlane(image, 1, chromaWidth, chromaHeight) ?: return
        val v = copyImagePlane(image, 2, chromaWidth, chromaHeight) ?: return

        publish(
            SourceFrame(
                width = width,
                height = height,
                pixelFormat = PixelFormat.I420,
                planes = listOf(
                    SourcePlane(y.bytes, 0, y.rowStride, y.pixelStride, width, height),
                    SourcePlane(u.bytes, 0, u.rowStride, u.pixelStride, chromaWidth, chromaHeight),
                    SourcePlane(v.bytes, 0, v.rowStride, v.pixelStride, chromaWidth, chromaHeight),
                ),
                timestampNanos = imageTimestamp(image),
            ),
        )
    }

    /**
     * Copies one plane row by row.
     *
     * The luma plane and planar chroma are repacked to their sample width;
     * interleaved chroma keeps the native row stride so that the shared
     * processor can hand the pixel stride to libyuv.
     */
    private fun copyImagePlane(
        image: CPointer<AImage>,
        index: Int,
        samples: Int,
        rows: Int,
    ): CopiedPlane? = memScoped {
        val dataPtr = alloc<CPointerVar<UByteVar>>()
        val lengthVar = alloc<IntVar>()
        if (AImage_getPlaneData(image, index, dataPtr.ptr, lengthVar.ptr) != 0) return@memScoped null
        val data = dataPtr.value ?: return@memScoped null
        val length = lengthVar.value
        val rowStride = readImageValue { AImage_getPlaneRowStride(image, index, it.ptr) }
        val pixelStride = readImageValue { AImage_getPlanePixelStride(image, index, it.ptr) }
        if (length <= 0 || rowStride <= 0 || pixelStride <= 0 || samples <= 0 || rows <= 0) return@memScoped null

        val packed = index == 0 || pixelStride == 1
        val destinationStride = if (packed) samples * pixelStride else rowStride
        val rowBytes = minOf(samples * pixelStride, rowStride)
        val bytes = ByteArray(destinationStride * rows)
        val source = data.reinterpret<ByteVar>()
        bytes.usePinned { pinned ->
            val destination = pinned.addressOf(0)
            for (row in 0 until rows) {
                val sourceOffset = row * rowStride
                if (sourceOffset >= length) break
                val count = minOf(rowBytes, length - sourceOffset)
                if (count <= 0) break
                memcpy(destination + row * destinationStride, source + sourceOffset, count.convert())
            }
        }
        CopiedPlane(bytes, destinationStride, pixelStride)
    }

    private fun imageTimestamp(image: CPointer<AImage>): Long = memScoped {
        val timestamp = alloc<LongVar>()
        if (AImage_getTimestamp(image, timestamp.ptr) == 0 && timestamp.value > 0L) {
            timestamp.value
        } else {
            monotonicNanos()
        }
    }

    private inline fun readImageValue(block: (IntVar) -> Int): Int = memScoped {
        val value = alloc<IntVar>()
        if (block(value) != 0) 0 else value.value
    }

    /** Error from the NDK camera thread; safe to call after the session was released. */
    fun onCameraDisconnected() {
        if (releasing) return
        reportError(CameraException("Camera2 camera ${camera.id} was disconnected"))
    }

    fun onCameraError(code: Int) {
        if (releasing) return
        reportError(CameraException("Camera2 camera ${camera.id} reported error $code"))
    }
}

/** Resources of one attempted stream configuration. */
private class PendingSetup {
    var reader: CPointer<AImageReader>? = null
    var window: CPointer<ANativeWindow>? = null
    var output: CPointer<ACaptureSessionOutput>? = null
    var container: CPointer<ACaptureSessionOutputContainer>? = null
    var session: CPointer<ACameraCaptureSession>? = null
    var request: CPointer<ACaptureRequest>? = null
    var target: CPointer<ACameraOutputTarget>? = null

    fun clear() {
        reader = null
        window = null
        output = null
        container = null
        session = null
        request = null
        target = null
    }

    fun release() {
        session?.let { runCatching { ACameraCaptureSession_close(it) } }
        request?.let { runCatching { ACaptureRequest_free(it) } }
        target?.let { runCatching { ACameraOutputTarget_free(it) } }
        output?.let { runCatching { ACaptureSessionOutput_free(it) } }
        container?.let { runCatching { ACaptureSessionOutputContainer_free(it) } }
        reader?.let { runCatching { AImageReader_delete(it) } }
        clear()
    }
}

private class CopiedPlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

private fun camera2Session(context: COpaquePointer?): Camera2NdkSession? =
    context?.let { runCatching { it.asStableRef<Camera2NdkSession>().get() }.getOrNull() }

private fun onNativeCameraDisconnected(context: COpaquePointer?, device: CPointer<ACameraDevice>?) {
    camera2Session(context)?.onCameraDisconnected()
}

private fun onNativeCameraError(context: COpaquePointer?, device: CPointer<ACameraDevice>?, error: Int) {
    camera2Session(context)?.onCameraError(error)
}

private fun onNativeSessionClosed(context: COpaquePointer?, session: CPointer<ACameraCaptureSession>?) = Unit

private fun onNativeSessionReady(context: COpaquePointer?, session: CPointer<ACameraCaptureSession>?) = Unit

private fun onNativeSessionActive(context: COpaquePointer?, session: CPointer<ACameraCaptureSession>?) = Unit
