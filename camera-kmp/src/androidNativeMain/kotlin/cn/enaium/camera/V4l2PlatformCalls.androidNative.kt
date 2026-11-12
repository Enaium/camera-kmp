@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION_ERROR")

package cn.enaium.camera

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import platform.posix.dlsym
import platform.posix.POLLERR
import platform.posix.POLLIN
import platform.posix.poll
import platform.posix.pollfd

// Calling the variadic platform.posix.ioctl() from a Kotlin/Native shared
// library leaves an unresolved `kfun:platform.posix#ioctl` symbol in the
// Android .so. Resolve libc's entry point at runtime instead; every V4L2
// request passes exactly one pointer argument.
private val rawIoctl: CPointer<CFunction<(Int, Int, COpaquePointer?) -> Int>> by lazy {
    dlsym(null, "ioctl")?.reinterpret()
        ?: error("libc ioctl is not resolvable")
}

// Android's nfds_t and ioctl request parameter are 32-bit; the Linux target
// uses 64-bit unsigned request numbers and nfds_t.
internal fun v4l2Ioctl(fd: Int, request: ULong, pointer: kotlinx.cinterop.CPointer<*>?): Int =
    rawIoctl(fd, request.toInt(), pointer?.reinterpret())

internal fun v4l2Poll(fd: Int, events: Short, timeoutMillis: Int): Boolean = memScoped {
    val descriptor = alloc<pollfd>()
    descriptor.fd = fd
    descriptor.events = events
    val result = poll(descriptor.ptr, 1u, timeoutMillis)
    result > 0 && (descriptor.revents.toInt() and (POLLIN or POLLERR)) != 0
}

