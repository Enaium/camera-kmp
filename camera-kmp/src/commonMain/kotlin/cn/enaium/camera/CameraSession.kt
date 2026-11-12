package cn.enaium.camera

/** Receives captured frames. */
fun interface CameraFrameListener {
    fun onFrame(frame: CameraFrame)
}

/**
 * An open camera.
 *
 * Frames are delivered either through [start] (callback mode, the only mode on
 * the web) or through [readFrame] (blocking pull mode for desktop, native and
 * Android). The two modes share one queue: a session that has a listener
 * installed should not also call [readFrame].
 *
 * Sessions are not thread-safe for configuration changes; call [setRotation],
 * [setMirror] and [stop] from one thread at a time. Frames themselves are
 * immutable and may be handed to other threads.
 */
interface CameraSession : AutoCloseable {
    /** The camera this session was opened on. */
    val camera: CameraInfo

    /** The configuration the session was opened with. */
    val config: CameraConfig

    /**
     * The format frames actually have: the requested [CameraConfig.format] with
     * the backend's accepted resolution/frame rate and the rotation applied.
     */
    val format: CameraFormat

    /** `true` between [start] and [stop]. */
    val isRunning: Boolean

    /**
     * Installs [listener] and starts the capture stream.
     *
     * Calling it again while running replaces the listener. Errors after the
     * session was opened are delivered to [onError]; a backend that cannot
     * recover stops the session first.
     */
    fun start(listener: CameraFrameListener, onError: (CameraException) -> Unit = {})

    /** Stops delivering frames. The camera stays open. */
    fun stop()

    /**
     * Blocks until the next frame is available, or returns `null` when
     * [timeoutMillis] elapsed or the session was closed.
     *
     * Unsupported on the web targets, where the browser only runs callbacks;
     * use [start] there.
     */
    fun readFrame(timeoutMillis: Long = 1000L): CameraFrame?

    /** Sets the clockwise rotation applied to subsequent frames (0/90/180/270). */
    fun setRotation(degrees: Int)

    /** Sets whether subsequent frames are mirrored horizontally. */
    fun setMirror(enabled: Boolean)

    /** Stops the stream and releases the camera. Idempotent. */
    override fun close()
}
