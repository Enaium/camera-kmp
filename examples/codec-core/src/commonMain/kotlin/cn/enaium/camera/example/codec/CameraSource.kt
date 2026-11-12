package cn.enaium.camera.example.codec

import cn.enaium.camera.CameraConfig
import cn.enaium.camera.CameraFormat
import cn.enaium.camera.CameraFrame
import cn.enaium.camera.CameraLensFacing
import cn.enaium.camera.CameraSession
import cn.enaium.camera.CameraSystem
import cn.enaium.camera.I420Frame
import cn.enaium.camera.PixelFormat
import cn.enaium.camera.cameraSystem

/**
 * Opens the platform camera and converts its frames to I420.
 *
 * The example requests I420 directly; the library still converts from whatever
 * the backend delivers natively (NV12 on Apple, YUV420_888 on Android, YUYV on
 * V4L2, ...), so the codec pipeline only ever sees one layout.
 */
class CameraSource(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
) : AutoCloseable {
    private var system: CameraSystem? = null
    private var session: CameraSession? = null

    /** The camera the backend selected, or `null` before [start] succeeded. */
    var cameraName: String? = null
        private set

    /** The format the camera actually delivers, available after the first frame. */
    var actualFormat: CameraFormat? = null
        private set

    /**
     * Starts streaming. [onFrame] is called on the backend's capture thread.
     * Returns `false` when no backend or camera is available; the caller can
     * then use [TestPattern] instead.
     */
    fun start(
        onFrame: (I420Frame) -> Unit,
        onError: (String) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        return try {
            val system = cameraSystem()
            val session = system.open(
                CameraConfig(
                    format = CameraFormat(width, height, PixelFormat.I420, frameRate),
                    lensFacing = CameraLensFacing.UNKNOWN,
                    bufferCount = 4,
                ),
            )
            this.system = system
            this.session = session
            this.cameraName = session.camera.name
            session.start({ frame: CameraFrame ->
                if (actualFormat == null) actualFormat = frame.format
                onFrame(frame.toI420())
            }, { error ->
                onError(error.message ?: error.toString())
            })
            true
        } catch (error: Throwable) {
            onFailure(error)
            close()
            false
        }
    }

    override fun close() {
        runCatching { session?.close() }
        runCatching { system?.close() }
        session = null
        system = null
    }
}
