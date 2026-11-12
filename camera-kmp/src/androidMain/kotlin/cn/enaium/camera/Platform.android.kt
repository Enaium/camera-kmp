package cn.enaium.camera

import java.util.concurrent.locks.ReentrantLock

internal actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() = lock.lock()

    actual fun unlock() = lock.unlock()

}

internal actual fun platformSleepMillis(millis: Long) {
    if (millis > 0) Thread.sleep(millis)
}
