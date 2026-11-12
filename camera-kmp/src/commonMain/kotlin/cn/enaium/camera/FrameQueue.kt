package cn.enaium.camera

/**
 * Bounded frame queue between the capture thread and [CameraSession.readFrame].
 *
 * When the consumer falls behind, the oldest frame is dropped: a camera that
 * shows the newest picture is more useful than one that grows without bound.
 */
internal class FrameQueue(private val capacity: Int) {
    private val lock = PlatformLock()
    private val frames = ArrayDeque<CameraFrame>()
    private var closed = false

    fun offer(frame: CameraFrame) {
        lock.guard {
            if (closed) return
            if (frames.size >= capacity) frames.removeFirst()
            frames.addLast(frame)
        }
    }

    fun poll(timeoutMillis: Long): CameraFrame? {
        val deadline = monotonicNanos() + timeoutMillis.coerceAtLeast(0) * 1_000_000
        while (true) {
            val result = lock.guard {
                when {
                    frames.isNotEmpty() -> frames.removeFirst()
                    closed -> null
                    timeoutMillis <= 0 -> null
                    else -> Unit
                }
            }
            if (result !== Unit) return result as CameraFrame?
            if (monotonicNanos() >= deadline) return null
            platformSleepMillis(2)
        }
    }

    fun clear() {
        lock.guard { frames.clear() }
    }

    fun close() {
        lock.guard {
            closed = true
            frames.clear()
        }
    }
}
