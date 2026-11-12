@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package cn.enaium.camera

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import kotlin.math.abs
import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * AVFoundation capture for macOS and iOS.
 *
 * The session is configured synchronously (about a tenth of a second on Apple
 * hardware), and the sample-buffer delegate copies each NV12 pixel buffer into
 * Kotlin arrays on AVFoundation's own dispatch queue. Rotation and mirroring
 * are applied by the shared frame processor; the camera connection itself is
 * left unrotated so the same code works for macOS, iOS and the JVM backend.
 */
internal class AppleCameraSystem(
    private val backendKind: CameraBackend = CameraBackend.AVFOUNDATION,
) : CameraSystem {
    override val backend: CameraBackend = backendKind
    override val name: String = "AVFoundation"

    override fun cameras(): List<CameraInfo> {
        val devices = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo)
            ?.filterIsInstance<AVCaptureDevice>()
            .orEmpty()
        val defaultId = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.uniqueID
        return devices.map { device ->
            val facing = appleCameraFacing(device)
            CameraInfo(
                id = device.uniqueID,
                name = device.localizedName,
                lensFacing = facing,
                sensorOrientation = sensorOrientation(facing),
                transport = appleTransportType(device),
                isDefault = device.uniqueID == defaultId,
                supportedFormats = appleFormats(device),
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
        return open(info, config)
    }

    override fun open(camera: CameraInfo, config: CameraConfig): CameraSession =
        AppleCameraSession(camera, config)

    override fun close() = Unit

    private fun sensorOrientation(facing: CameraLensFacing): Int {
        // AVFoundation does not expose the sensor mounting angle. The platform
        // convention is 90 degrees for the back camera and 270 for the front
        // one on iPhone/iPad; Macs are landscape by default.
        return if (Platform.osFamily == OsFamily.IOS) {
            if (facing == CameraLensFacing.FRONT) 270 else 90
        } else {
            0
        }
    }

    private fun appleFormats(device: AVCaptureDevice): List<CameraFormat> {
        val modes = LinkedHashSet<CameraFormat>()
        for (format in device.formats.orEmpty().filterIsInstance<AVCaptureDeviceFormat>()) {
            val description = format.formatDescription ?: continue
            val dimensions = CMVideoFormatDescriptionGetDimensions(description)
            val width = dimensions.useContents { width.toInt() }
            val height = dimensions.useContents { height.toInt() }
            if (width <= 0 || height <= 0) continue
            val ranges = format.videoSupportedFrameRateRanges.filterIsInstance<AVFrameRateRange>()
            val rates = LinkedHashSet<Int>()
            for (range in ranges) {
                val min = range.minFrameRate.toInt().coerceAtLeast(1)
                val max = range.maxFrameRate.toInt().coerceAtLeast(min)
                for (candidate in COMMON_FRAME_RATES) {
                    if (candidate in min..max) rates += candidate
                }
                rates += max
                if (rates.isEmpty()) rates += min
            }
            if (rates.isEmpty()) rates += CameraFormat.DEFAULT_FRAME_RATE
            for (rate in rates) {
                modes += CameraFormat(width, height, PixelFormat.NV12, rate)
            }
        }
        return modes.toList()
    }

    private companion object {
        val COMMON_FRAME_RATES = intArrayOf(15, 24, 25, 30, 60, 120, 240)
    }
}

private class AppleCameraSession(
    camera: CameraInfo,
    config: CameraConfig,
) : BaseCameraSession(camera, config, baseFormat(camera, config)) {

    private var session: AVCaptureSession? = null
    private var output: AVCaptureVideoDataOutput? = null
    private var delegate: SampleBufferDelegate? = null
    private var queue: dispatch_queue_t? = null
    private var started = false

    override fun startCapture() {
        val device = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo)
            ?.filterIsInstance<AVCaptureDevice>()
            ?.firstOrNull { it.uniqueID == camera.id }
            ?: throw CameraException("AVFoundation camera ${camera.id} is no longer attached")

        val selected = selectFormat(device, config.format.width, config.format.height)
        val session = AVCaptureSession()
        val input = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
                ?: throw CameraException(
                    "AVFoundation rejected ${device.localizedName}: ${error.value?.localizedDescription}",
                )
        }
        session.beginConfiguration()
        try {
            if (!session.canAddInput(input)) {
                throw CameraException("AVFoundation cannot add the input of ${device.localizedName}")
            }
            session.addInput(input)

            val delegate = SampleBufferDelegate(::handleSample)
            val queue = dispatch_queue_create("cn.enaium.camera.avfoundation", null)
            val output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            // The CFString constant does not bridge to an NSString key through
            // a Kotlin Map; AVFoundation recognizes the plain string.
            val settings = mutableMapOf<Any?, Any?>(
                "PixelFormatType" to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            )
            if (Platform.osFamily == OsFamily.MACOSX) {
                // macOS has no AVCaptureSessionPresetInputPriority; the data
                // output width/height keys are what make it honor the selected
                // device format instead of the session preset's default.
                val (width, height) = appleFormatResolution(selected)
                settings["Width"] = if (width > 0) width else config.format.width
                settings["Height"] = if (height > 0) height else config.format.height
            }
            output.videoSettings = settings
            output.setSampleBufferDelegate(delegate, queue)
            if (!session.canAddOutput(output)) {
                throw CameraException("AVFoundation cannot add a video data output")
            }
            session.addOutput(output)

            // InputPriority is iOS-only; it must be selected before the device
            // format is applied, or it resets the active format to the
            // preset's high-resolution default.
            if (Platform.osFamily == OsFamily.IOS) {
                val inputPriority = "AVCaptureSessionPresetInputPriority"
                if (session.canSetSessionPreset(inputPriority)) {
                    session.sessionPreset = inputPriority
                }
            }
            configureDevice(device, selected, config.format.frameRate)
            session.commitConfiguration()

            this.session = session
            this.output = output
            this.delegate = delegate
            this.queue = queue
            session.startRunning()
            started = true
        } catch (error: Throwable) {
            session.commitConfiguration()
            throw error
        }
    }

    override fun stopCapture() {
        if (!started) return
        started = false
        output?.setSampleBufferDelegate(null, queue)
        session?.stopRunning()
        output = null
        delegate = null
        queue = null
        session = null
    }

    override fun release() {
        stopCapture()
    }

    private fun configureDevice(
        device: AVCaptureDevice,
        selected: AVCaptureDeviceFormat?,
        requestedFrameRate: Int,
    ) {
        if (selected == null) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!device.lockForConfiguration(error.ptr)) {
                throw CameraException(
                    "AVFoundation cannot configure ${device.localizedName}: ${error.value?.localizedDescription}",
                )
            }
            try {
                device.activeFormat = selected
                val ranges = selected.videoSupportedFrameRateRanges.filterIsInstance<AVFrameRateRange>()
                val range = ranges.minByOrNull {
                    val min = it.minFrameRate.toInt()
                    val max = it.maxFrameRate.toInt()
                    when {
                        requestedFrameRate < min -> min - requestedFrameRate
                        requestedFrameRate > max -> requestedFrameRate - max
                        else -> 0
                    }
                }
                if (range != null) {
                    val fps = requestedFrameRate.coerceIn(
                        range.minFrameRate.toInt().coerceAtLeast(1),
                        range.maxFrameRate.toInt().coerceAtLeast(1),
                    )
                    device.activeVideoMinFrameDuration = CMTimeMake(1L, fps)
                    device.activeVideoMaxFrameDuration = CMTimeMake(1L, fps)
                }
            } finally {
                device.unlockForConfiguration()
            }
        }
    }

    private fun selectFormat(
        device: AVCaptureDevice,
        width: Int,
        height: Int,
    ): AVCaptureDeviceFormat? {
        val formats = device.formats.orEmpty().filterIsInstance<AVCaptureDeviceFormat>()
        if (formats.isEmpty()) return null
        val targetPixels = width.toLong() * height
        val targetAspect = width.toDouble() / height
        return formats.minByOrNull { format ->
            val description = format.formatDescription
            if (description == null) {
                Long.MAX_VALUE
            } else {
                val dimensions = CMVideoFormatDescriptionGetDimensions(description)
                val w = dimensions.useContents { this.width.toInt() }
                val h = dimensions.useContents { this.height.toInt() }
                if (w <= 0 || h <= 0) {
                    Long.MAX_VALUE
                } else {
                    val aspect = abs(w.toDouble() / h - targetAspect)
                    val size = abs(w.toLong() * h - targetPixels)
                    (aspect * 1_000_000).toLong() + size
                }
            }
        }
    }

    private fun handleSample(sampleBuffer: CMSampleBufferRef?) {
        if (sampleBuffer == null) return
        val image = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return
        CVPixelBufferLockBaseAddress(image, 0uL)
        try {
            val width = CVPixelBufferGetWidth(image).toInt()
            val height = CVPixelBufferGetHeight(image).toInt()
            if (width <= 0 || height <= 0) return
            val yBase = CVPixelBufferGetBaseAddressOfPlane(image, 0uL) ?: return
            val uvBase = CVPixelBufferGetBaseAddressOfPlane(image, 1uL) ?: return
            val yStride = CVPixelBufferGetBytesPerRowOfPlane(image, 0uL).toInt()
            val uvStride = CVPixelBufferGetBytesPerRowOfPlane(image, 1uL).toInt()
            val chromaHeight = CVPixelBufferGetHeightOfPlane(image, 1uL).toInt()
            val yBytes = yBase.reinterpret<ByteVar>().readBytes(yStride * height)
            val uvBytes = uvBase.reinterpret<ByteVar>().readBytes(uvStride * chromaHeight)
            val pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            val seconds = CMTimeGetSeconds(pts)
            val timestamp = if (seconds.isFinite() && seconds >= 0.0) {
                (seconds * 1_000_000_000.0).toLong()
            } else {
                monotonicNanos()
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
            CVPixelBufferUnlockBaseAddress(image, 0uL)
        }
    }
}

private class SampleBufferDelegate(
    private val onSample: (CMSampleBufferRef?) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        onSample(didOutputSampleBuffer)
    }
}

private fun appleFormatResolution(format: AVCaptureDeviceFormat?): Pair<Int, Int> {
    val description = format?.formatDescription ?: return 0 to 0
    val dimensions = CMVideoFormatDescriptionGetDimensions(description)
    return dimensions.useContents { width.toInt() to height.toInt() }
}

private fun baseFormat(camera: CameraInfo, config: CameraConfig): CameraFormat {
    val resolution = camera.bestFormat(
        config.format.width,
        config.format.height,
        PixelFormat.NV12,
        config.format.frameRate,
    ) ?: CameraFormat(config.format.width, config.format.height, PixelFormat.NV12, config.format.frameRate)
    return CameraFormat(resolution.width, resolution.height, PixelFormat.NV12, resolution.frameRate)
}

actual fun availableCameraBackends(): List<CameraBackend> = listOf(
    CameraBackend.AUTO,
    CameraBackend.AVFOUNDATION,
    CameraBackend.UVC,
)

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO, CameraBackend.AVFOUNDATION -> AppleCameraSystem(CameraBackend.AVFOUNDATION)
    CameraBackend.UVC -> AppleCameraSystem(CameraBackend.UVC)
    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available here; use AVFoundation or UVC",
    )
}
