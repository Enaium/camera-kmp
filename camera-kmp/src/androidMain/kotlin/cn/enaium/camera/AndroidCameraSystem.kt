package cn.enaium.camera

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Android Camera2 backend.
 *
 * Enumeration reads the real camera list and the real YUV_420_888 output sizes
 * from [CameraCharacteristics]; frame rates come from the AE target ranges
 * capped by each size's minimum frame duration. Capture opens the device
 * asynchronously on one [HandlerThread] per session, and every
 * [ImageReader] frame is copied plane by plane and published as I420.
 */
internal class AndroidCameraSystem(
    private val context: Context,
    private val backendKind: CameraBackend = CameraBackend.CAMERA2,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "Camera2"

    override fun cameras(): List<CameraInfo> {
        val manager = cameraManager(context)
        val ids = try {
            manager.cameraIdList
        } catch (error: CameraAccessException) {
            throw CameraException("Camera2 cannot enumerate cameras: ${cameraError(error)}", error)
        }
        val infos = ArrayList<CameraInfo>(ids.size)
        for (id in ids) {
            val info = try {
                cameraInfo(manager, id)
            } catch (_: CameraAccessException) {
                // The camera disappeared while enumerating; skip it.
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (info != null) infos += info
        }
        val visible = if (backendKind == CameraBackend.UVC) {
            infos.filter { it.lensFacing == CameraLensFacing.EXTERNAL }
        } else {
            infos
        }
        val preferredId = visible.firstOrNull { it.lensFacing == CameraLensFacing.BACK }?.id
            ?: visible.firstOrNull { it.lensFacing == CameraLensFacing.FRONT }?.id
            ?: visible.firstOrNull()?.id
        return visible.map { it.copy(isDefault = it.id == preferredId) }
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
                if (backendKind == CameraBackend.UVC) {
                    "no external USB (UVC) camera is attached"
                } else {
                    "no Camera2 camera is available on this device"
                },
            )
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession {
        val manager = cameraManager(context)
        val attached = try {
            manager.cameraIdList.any { it == camera.id }
        } catch (error: CameraAccessException) {
            throw CameraException("Camera2 cannot enumerate cameras: ${cameraError(error)}", error)
        }
        if (!attached) {
            throw CameraException("Camera2 camera ${camera.id} is no longer attached")
        }
        return AndroidCameraSession(context, camera, config)
    }

    override fun close() = Unit

    private fun cameraInfo(manager: CameraManager, id: String): CameraInfo {
        val characteristics = manager.getCameraCharacteristics(id)
        val lensFacing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
            CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
            else -> CameraLensFacing.UNKNOWN
        }
        return CameraInfo(
            id = id,
            // Camera2 does not expose a user-facing device name; the id is the
            // only stable, public identifier available before API 29.
            name = "Camera $id",
            lensFacing = lensFacing,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            transport = if (lensFacing == CameraLensFacing.EXTERNAL) {
                CameraTransport.USB
            } else {
                CameraTransport.INTERNAL
            },
            isDefault = false,
            supportedFormats = androidFormats(characteristics),
        )
    }
}

private class AndroidCameraSession(
    private val context: Context,
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, baseFormat(camera, config)) {

    private val lock = Any()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var selectedMode: NativeMode? = null
    private var fpsRanges: List<Range<Int>> = emptyList()
    private var openLatch: CountDownLatch? = null

    /** `true` while the device/session/reader of a capture run are owned by this session. */
    private var active = false

    override fun startCapture() {
        val manager = cameraManager(context)
        val characteristics = try {
            manager.getCameraCharacteristics(camera.id)
        } catch (error: CameraAccessException) {
            throw CameraException(
                "Camera2 cannot read the characteristics of ${camera.name}: ${cameraError(error)}",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw CameraException("Camera2 camera ${camera.id} is no longer attached", error)
        }
        val mode = selectMode(characteristics, config.format)
            ?: throw CameraException("${camera.name} advertises no YUV_420_888 capture size")
        val ranges = aeRanges(characteristics)

        val thread = HandlerThread("cn.enaium.camera.camera2.${camera.id}")
        thread.start()
        val handler = Handler(thread.looper)
        val reader = try {
            ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, IMAGE_BUFFER_COUNT)
        } catch (error: RuntimeException) {
            thread.quitSafely()
            awaitThread(thread)
            throw CameraException(
                "Camera2 cannot create an ImageReader for ${mode.width}x${mode.height}: ${error.message}",
                error,
            )
        }

        val latch = CountDownLatch(1)
        synchronized(lock) {
            handlerThread = thread
            this.handler = handler
            this.reader = reader
            selectedMode = mode
            fpsRanges = ranges
            openLatch = latch
            active = true
        }
        reader.setOnImageAvailableListener({ source -> handleImages(source) }, handler)

        try {
            manager.openCamera(camera.id, deviceCallback, handler)
        } catch (error: SecurityException) {
            openCompleted()
            stopCapture()
            throw CameraException(
                "the camera permission is not granted; request android.permission.CAMERA before " +
                    "opening ${camera.name}",
                error,
            )
        } catch (error: CameraAccessException) {
            openCompleted()
            stopCapture()
            throw CameraException("Camera2 cannot open ${camera.name}: ${cameraError(error)}", error)
        } catch (error: RuntimeException) {
            openCompleted()
            stopCapture()
            throw CameraException("Camera2 cannot open ${camera.name}: ${error.message}", error)
        }
        updateNegotiatedFormat(CameraFormat(mode.width, mode.height, PixelFormat.I420, mode.frameRate))
    }

    override fun stopCapture() {
        val resources = synchronized(lock) {
            active = false
            val snapshot = SessionResources(handlerThread, handler, reader, device, captureSession, openLatch)
            handlerThread = null
            handler = null
            reader = null
            device = null
            captureSession = null
            selectedMode = null
            fpsRanges = emptyList()
            openLatch = null
            snapshot
        }

        // A camera open still in flight delivers its device only through the
        // callback. Wait briefly so the device can be closed instead of being
        // leaked when stop follows start immediately.
        if (resources.device == null && resources.openLatch != null &&
            Thread.currentThread() !== resources.thread
        ) {
            try {
                resources.openLatch.await(OPEN_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        resources.session?.let { session ->
            runCatching { session.stopRepeating() }
            runCatching { session.close() }
        }
        resources.device?.let { runCatching { it.close() } }
        resources.reader?.let { source ->
            runCatching { source.setOnImageAvailableListener(null, null) }
            runCatching { source.close() }
        }
        resources.handler?.removeCallbacksAndMessages(null)
        resources.thread?.quitSafely()
        awaitThread(resources.thread)
    }

    override fun release() {
        stopCapture()
    }

    private fun createCaptureSession() {
        val setup = synchronized(lock) {
            if (!active) {
                null
            } else {
                val currentDevice = device
                val currentReader = reader
                val currentHandler = handler
                val currentMode = selectedMode
                if (currentDevice == null || currentReader == null ||
                    currentHandler == null || currentMode == null
                ) {
                    null
                } else {
                    CaptureSetup(currentDevice, currentReader, currentHandler, currentMode, fpsRanges)
                }
            }
        } ?: return

        try {
            @Suppress("DEPRECATION")
            setup.device.createCaptureSession(listOf(setup.reader.surface), sessionCallback, setup.handler)
        } catch (error: CameraAccessException) {
            fail(CameraException("Camera2 cannot create a capture session for ${camera.name}: ${cameraError(error)}", error))
        } catch (error: IllegalStateException) {
            fail(CameraException("Camera2 cannot create a capture session for ${camera.name}", error))
        } catch (error: IllegalArgumentException) {
            fail(CameraException("Camera2 rejected the YUV output surface of ${camera.name}", error))
        }
    }

    private fun startRepeating(session: CameraCaptureSession) {
        val setup = synchronized(lock) {
            if (!active) {
                null
            } else {
                val currentDevice = device
                val currentReader = reader
                val currentHandler = handler
                val currentMode = selectedMode
                if (currentDevice == null || currentReader == null ||
                    currentHandler == null || currentMode == null
                ) {
                    null
                } else {
                    CaptureSetup(currentDevice, currentReader, currentHandler, currentMode, fpsRanges)
                }
            }
        } ?: return

        try {
            val request = setup.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.addTarget(setup.reader.surface)
            chooseFpsRange(setup.fpsRanges, setup.mode.frameRate)?.let { range ->
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
            session.setRepeatingRequest(request.build(), null, setup.handler)
        } catch (error: CameraAccessException) {
            fail(CameraException("Camera2 cannot start the stream of ${camera.name}: ${cameraError(error)}", error))
        } catch (error: IllegalStateException) {
            fail(CameraException("Camera2 cannot start the stream of ${camera.name}", error))
        } catch (error: IllegalArgumentException) {
            fail(CameraException("Camera2 rejected the capture request of ${camera.name}: ${error.message}", error))
        }
    }

    private fun handleImages(source: ImageReader) {
        if (!synchronized(lock) { active }) return
        val image = try {
            source.acquireLatestImage()
        } catch (error: IllegalStateException) {
            if (synchronized(lock) { active }) {
                reportError(CameraException("Camera2 lost the image queue of ${camera.name}", error))
            }
            return
        } catch (error: UnsupportedOperationException) {
            if (synchronized(lock) { active }) {
                reportError(CameraException("Camera2 cannot acquire images from ${camera.name}", error))
            }
            return
        } ?: return

        try {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return
            val planes = image.planes
            if (planes.size < 3) {
                reportError(
                    CameraException("Camera2 returned ${planes.size} planes instead of 3 for ${camera.name}"),
                )
                return
            }
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            // Plane byte counts come from the image row stride scaled by the
            // plane's own height, never from the image width.
            val yBytes = copyPlane(planes[0], height)
            val uBytes = copyPlane(planes[1], chromaHeight)
            val vBytes = copyPlane(planes[2], chromaHeight)
            val timestamp = image.timestamp
            publish(
                SourceFrame(
                    width = width,
                    height = height,
                    pixelFormat = PixelFormat.I420,
                    planes = listOf(
                        SourcePlane(yBytes, 0, planes[0].rowStride, planes[0].pixelStride, width, height),
                        SourcePlane(uBytes, 0, planes[1].rowStride, planes[1].pixelStride, chromaWidth, chromaHeight),
                        SourcePlane(vBytes, 0, planes[2].rowStride, planes[2].pixelStride, chromaWidth, chromaHeight),
                    ),
                    timestampNanos = if (timestamp > 0L) timestamp else monotonicNanos(),
                ),
            )
        } catch (error: Throwable) {
            reportError(CameraException("Camera2 cannot read a frame from ${camera.name}", error))
        } finally {
            image.close()
        }
    }

    private fun copyPlane(plane: Image.Plane, rows: Int): ByteArray {
        val rowStride = plane.rowStride
        val bytes = ByteArray(rowStride * rows)
        val source = plane.buffer.duplicate()
        // Do not rewind: for an interleaved V plane the buffer position is the
        // first V sample, not byte 0 of the shared U/V allocation.
        val available = source.remaining()
        if (available > 0) {
            source.get(bytes, 0, minOf(bytes.size, available))
        }
        return bytes
    }

    private fun fail(error: CameraException) {
        val report = synchronized(lock) { active }
        if (report) reportError(error)
        close()
    }

    private fun openCompleted() {
        val latch = synchronized(lock) {
            val pending = openLatch
            openLatch = null
            pending
        }
        latch?.countDown()
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(openedDevice: CameraDevice) {
            val keep = synchronized(lock) {
                if (active) {
                    device = openedDevice
                    true
                } else {
                    false
                }
            }
            if (!keep) {
                runCatching { openedDevice.close() }
                openCompleted()
                return
            }
            openCompleted()
            createCaptureSession()
        }

        override fun onDisconnected(disconnected: CameraDevice) {
            synchronized(lock) {
                if (device === disconnected) device = null
            }
            runCatching { disconnected.close() }
            openCompleted()
            fail(CameraException("Camera2 camera ${camera.name} was disconnected"))
        }

        override fun onError(failed: CameraDevice, error: Int) {
            synchronized(lock) {
                if (device === failed) device = null
            }
            runCatching { failed.close() }
            openCompleted()
            fail(CameraException("Camera2 reported ${stateErrorMessage(error)} for ${camera.name}"))
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val keep = synchronized(lock) {
                if (active) {
                    captureSession = session
                    true
                } else {
                    false
                }
            }
            if (!keep) {
                runCatching { session.close() }
                return
            }
            startRepeating(session)
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            runCatching { session.close() }
            fail(CameraException("Camera2 cannot configure a YUV capture session for ${camera.name}"))
        }
    }

    private companion object {
        const val IMAGE_BUFFER_COUNT = 3
        const val OPEN_CALLBACK_TIMEOUT_MILLIS = 1000L
    }
}

private class NativeMode(val width: Int, val height: Int, val frameRate: Int)

private class CaptureSetup(
    val device: CameraDevice,
    val reader: ImageReader,
    val handler: Handler,
    val mode: NativeMode,
    val fpsRanges: List<Range<Int>>,
)

private class SessionResources(
    val thread: HandlerThread?,
    val handler: Handler?,
    val reader: ImageReader?,
    val device: CameraDevice?,
    val session: CameraCaptureSession?,
    val openLatch: CountDownLatch?,
)

private fun cameraManager(context: Context): CameraManager =
    context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: throw CameraException("the Android camera service is not available on this device")

private fun awaitThread(thread: HandlerThread?) {
    if (thread == null || Thread.currentThread() === thread) return
    try {
        thread.join()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun cameraError(error: CameraAccessException): String {
    val reason = when (error.reason) {
        CameraAccessException.CAMERA_DISABLED -> "the camera is disabled"
        CameraAccessException.CAMERA_DISCONNECTED -> "the camera is disconnected"
        CameraAccessException.CAMERA_ERROR -> "a camera error occurred"
        CameraAccessException.CAMERA_IN_USE -> "the camera is already in use"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras are already open"
        else -> "camera access error ${error.reason}"
    }
    val detail = error.message
    return if (detail.isNullOrBlank()) reason else "$reason ($detail)"
}

private fun stateErrorMessage(error: Int): String = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "the camera is already in use"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras are already open"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "the camera is disabled by device policy"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "a fatal camera device error"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "the camera service is unavailable"
    else -> "camera device error $error"
}

private fun aeRanges(characteristics: CameraCharacteristics): List<Range<Int>> {
    val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    return ranges.orEmpty().filter { it.lower > 0 && it.upper >= it.lower }
}

private fun androidFormats(characteristics: CameraCharacteristics): List<CameraFormat> {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return emptyList()
    val ranges = aeRanges(characteristics)
    val modes = LinkedHashSet<CameraFormat>()
    for (size in sizes) {
        if (size.width <= 0 || size.height <= 0) continue
        for (frameRate in frameRatesFor(map, ranges, size)) {
            modes += CameraFormat(size.width, size.height, PixelFormat.I420, frameRate)
        }
    }
    return modes.toList()
}

/**
 * Frame rates a YUV output [size] can deliver: the common rates and the AE
 * range bounds that fit both the AE ranges and the size's minimum frame
 * duration.
 */
private fun frameRatesFor(
    map: StreamConfigurationMap,
    ranges: List<Range<Int>>,
    size: Size,
): List<Int> {
    val minDuration = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
    val maxFrameRate = if (minDuration > 0L) (1_000_000_000L / minDuration).toInt().coerceAtLeast(1) else -1
    val rates = sortedSetOf<Int>()
    for (range in ranges) {
        for (rate in COMMON_FRAME_RATES) {
            if (rate >= range.lower && rate <= range.upper && (maxFrameRate < 0 || rate <= maxFrameRate)) {
                rates += rate
            }
        }
        if (maxFrameRate < 0 || range.lower <= maxFrameRate) rates += range.lower
        if (maxFrameRate < 0 || range.upper <= maxFrameRate) rates += range.upper
    }
    if (rates.isEmpty()) {
        if (maxFrameRate > 0) {
            for (rate in COMMON_FRAME_RATES) {
                if (rate <= maxFrameRate) rates += rate
            }
            rates += maxFrameRate
        } else {
            rates += CameraFormat.DEFAULT_FRAME_RATE
        }
    }
    return rates.toList()
}

private fun selectMode(characteristics: CameraCharacteristics, requested: CameraFormat): NativeMode? {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
    val size = closestSize(sizes, requested.width, requested.height) ?: return null
    val frameRate = closestFrameRate(
        frameRatesFor(map, aeRanges(characteristics), size),
        requested.frameRate,
    )
    return NativeMode(size.width, size.height, frameRate)
}

private fun closestSize(sizes: Array<Size>, width: Int, height: Int): Size? {
    val valid = sizes.filter { it.width > 0 && it.height > 0 }
    if (valid.isEmpty()) return null
    val targetPixels = width.toLong() * height
    val targetAspect = width.toDouble() / height
    val above = valid.filter { it.width.toLong() * it.height >= targetPixels }
    val pool = above.ifEmpty { valid }
    return pool.minByOrNull { size ->
        val aspectPenalty = abs(size.width.toDouble() / size.height - targetAspect) * 1_000_000.0
        val sizePenalty = abs(size.width.toLong() * size.height - targetPixels).toDouble()
        aspectPenalty + sizePenalty
    }
}

private fun closestFrameRate(rates: List<Int>, requested: Int): Int {
    if (rates.isEmpty()) return CameraFormat.DEFAULT_FRAME_RATE
    return rates.minByOrNull { rate ->
        if (rate >= requested) rate - requested else (requested - rate) * 2
    } ?: CameraFormat.DEFAULT_FRAME_RATE
}

private fun chooseFpsRange(ranges: List<Range<Int>>, frameRate: Int): Range<Int>? =
    ranges.filter { frameRate >= it.lower && frameRate <= it.upper }
        .minByOrNull { it.upper - it.lower }

private fun baseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val selected = camera.bestFormat(
        config.format.width,
        config.format.height,
        PixelFormat.I420,
        config.format.frameRate,
    ) ?: CameraFormat(config.format.width, config.format.height, PixelFormat.I420, config.format.frameRate)
    return CameraFormat(selected.width, selected.height, PixelFormat.I420, selected.frameRate)
}

private val COMMON_FRAME_RATES = intArrayOf(15, 20, 24, 25, 30, 50, 60, 120)

/**
 * Application context used by [cameraSystem].
 *
 * The common entry point takes no arguments, so the library registers the
 * application context through [CameraContextProvider], a manifest-declared
 * [ContentProvider] that runs before any activity. Apps that remove the
 * provider from the merged manifest can call [installAndroidCameraContext].
 */
internal object AndroidCameraContext {
    @Volatile
    private var application: Context? = null

    fun install(context: Context) {
        application = context.applicationContext ?: context
    }

    fun require(): Context = application ?: throw CameraException(
        "the Android application context is not registered; keep the camera-kmp " +
            "CameraContextProvider in the merged manifest or call installAndroidCameraContext(context)",
    )
}

/** Registers the application context the Android Camera2 backend needs. */
fun installAndroidCameraContext(context: Context) {
    AndroidCameraContext.install(context)
}

/**
 * Captures the application context before the first activity starts.
 *
 * Declared by the library manifest; it performs no data access.
 */
class CameraContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        AndroidCameraContext.install(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

actual fun availableCameraBackends(): List<CameraBackend> = listOf(
    CameraBackend.AUTO,
    CameraBackend.CAMERA2,
    CameraBackend.UVC,
)

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO, CameraBackend.CAMERA2 ->
        AndroidCameraSystem(AndroidCameraContext.require(), CameraBackend.CAMERA2)

    CameraBackend.UVC ->
        AndroidCameraSystem(AndroidCameraContext.require(), CameraBackend.UVC)

    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available on Android; use Camera2 or UVC",
    )
}
