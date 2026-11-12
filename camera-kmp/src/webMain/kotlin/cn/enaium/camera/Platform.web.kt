package cn.enaium.camera

internal actual class PlatformLock actual constructor() {
    actual fun lock() = Unit

    actual fun unlock() = Unit

}

internal actual fun platformSleepMillis(millis: Long) {
    // The browser only delivers frames through callbacks; readFrame is not
    // supported there and this function is never used in a blocking loop.
}
