@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawPtr
import platform.windows.CRITICAL_SECTION
import platform.windows.DeleteCriticalSection
import platform.windows.EnterCriticalSection
import platform.windows.InitializeCriticalSection
import platform.windows.LeaveCriticalSection
import platform.windows.Sleep

internal actual class PlatformLock actual constructor() {
    private val section = nativeHeap.alloc<CRITICAL_SECTION>()

    init {
        InitializeCriticalSection(section.ptr)
    }

    actual fun lock() {
        EnterCriticalSection(section.ptr)
    }

    actual fun unlock() {
        LeaveCriticalSection(section.ptr)
    }

}

internal actual fun platformSleepMillis(millis: Long) {
    if (millis > 0) Sleep(millis.toUInt())
}
