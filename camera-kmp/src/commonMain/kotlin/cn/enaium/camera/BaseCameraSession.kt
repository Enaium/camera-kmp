package cn.enaium.camera

/**
 * Shared session plumbing: listener/queue delivery, the rotation/mirror state
 * and the conversion pipeline. Backends only implement opening, the native
 * capture loop and teardown.
 */
internal abstract class BaseCameraSession(
    final override val camera: CameraInfo,
    final override val config: CameraConfig,
    negotiated: CameraFormat,
) : CameraSession {
    private val stateLock = PlatformLock()
    private val queue = FrameQueue(config.bufferCount)

    private var negotiatedFormat: CameraFormat = negotiated
    private var listener: CameraFrameListener? = null
    private var errorListener: ((CameraException) -> Unit)? = null
    private var rotation: Int = config.rotationDegrees
    private var mirrored: Boolean = config.mirrorHorizontally
    private var running: Boolean = false
    private var closed: Boolean = false

    override val format: CameraFormat
        get() = stateLock.guard { effectiveFormat(negotiatedFormat, rotation) }

    override val isRunning: Boolean
        get() = stateLock.guard { running }

    override fun start(listener: CameraFrameListener, onError: (CameraException) -> Unit) {
        val shouldStart = stateLock.guard {
            check(!closed) { "session is closed" }
            this.listener = listener
            this.errorListener = onError
            if (running) {
                false
            } else {
                running = true
                true
            }
        }
        if (shouldStart) {
            try {
                startCapture()
            } catch (error: Throwable) {
                stateLock.guard { running = false }
                val exception = error as? CameraException
                    ?: CameraException("failed to start ${camera.name}: ${error.message}", error)
                reportError(exception)
                throw exception
            }
        }
    }

    override fun stop() {
        val wasRunning = stateLock.guard {
            val was = running
            running = false
            listener = null
            was
        }
        if (wasRunning) {
            try {
                stopCapture()
            } finally {
                queue.clear()
            }
        }
    }

    override fun readFrame(timeoutMillis: Long): CameraFrame? = queue.poll(timeoutMillis)

    override fun setRotation(degrees: Int) {
        require(degrees % 90 == 0) { "rotation must be a multiple of 90: $degrees" }
        stateLock.guard { rotation = ((degrees % 360) + 360) % 360 }
    }

    override fun setMirror(enabled: Boolean) {
        stateLock.guard { mirrored = enabled }
    }

    override fun close() {
        val first = stateLock.guard {
            if (closed) return
            closed = true
            running = false
            listener = null
            true
        }
        if (first) {
            try {
                stopCapture()
            } finally {
                queue.close()
                release()
            }
        }
    }

    /** Converts [source] with the current transforms and delivers it. */
    protected fun publish(source: SourceFrame) {
        publishInternal(source)
    }

    /** Delivers an already converted frame (backends that cannot use [SourceFrame]). */
    protected fun publish(frame: CameraFrame) {
        val shouldDeliver = stateLock.guard { running && !closed }
        if (!shouldDeliver) return
        val target = stateLock.guard { listener }
        if (target != null) {
            target.onFrame(frame)
        } else {
            queue.offer(frame)
        }
    }

    private fun publishInternal(source: SourceFrame) {
        val snapshot = stateLock.guard {
            if (!running || closed) null else Triple(rotation, mirrored, negotiatedFormat)
        } ?: return
        val (rotation, mirrored, negotiated) = snapshot
        val frame = try {
            FrameProcessor.process(
                source,
                config.format.pixelFormat,
                rotation,
                mirrored,
                negotiated.frameRate,
            )
        } catch (error: Throwable) {
            reportError(CameraException("failed to convert a frame from ${camera.name}", error))
            return
        }
        publish(frame)
    }

    /** Replaces the negotiated mode after the backend started (for example getUserMedia). */
    protected fun updateNegotiatedFormat(format: CameraFormat) {
        stateLock.guard { negotiatedFormat = format }
    }

    /** `true` while frames should still be captured; used by backend loop threads. */
    protected fun isCaptureActive(): Boolean = stateLock.guard { running && !closed }

    /** Reports a recoverable error to the listener installed by [start]. */
    protected fun reportError(error: CameraException) {
        val callback = stateLock.guard { errorListener }
        if (callback != null) {
            callback(error)
        } else {
            // No listener yet: surface the failure on the next read.
            queue.close()
        }
    }

    /** Starts the native capture loop. Called once from [start]. */
    protected abstract fun startCapture()

    /** Stops the native capture loop. Must be idempotent and wait for the capture thread. */
    protected abstract fun stopCapture()

    /** Releases native resources after [stopCapture]. Called once from [close]. */
    protected open fun release() {}

    private fun effectiveFormat(format: CameraFormat, rotation: Int): CameraFormat {
        val output = format.copy(pixelFormat = config.format.pixelFormat)
        return if (rotation % 180 == 0) output else output.copy(width = output.height, height = output.width)
    }
}
