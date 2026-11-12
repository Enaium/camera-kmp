package cn.enaium.camera

/**
 * Small platform primitives the common code needs but the standard library
 * does not expose for every target.
 */

/** A mutual-exclusion lock. On the web the implementation is a no-op (single thread). */
internal expect class PlatformLock() {
    fun lock()
    fun unlock()

}

/** Suspends the calling thread for [millis] milliseconds. */
internal expect fun platformSleepMillis(millis: Long)

internal inline fun <T> PlatformLock.guard(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
