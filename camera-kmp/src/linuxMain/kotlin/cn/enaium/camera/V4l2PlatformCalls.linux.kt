@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.convert
import platform.posix.MAP_SHARED
import platform.posix.POLLERR
import platform.posix.POLLIN
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.ioctl
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.poll
import platform.posix.pollfd

internal fun v4l2Ioctl(fd: Int, request: ULong, pointer: kotlinx.cinterop.CPointer<*>?): Int =
    ioctl(fd, request, pointer)

internal fun v4l2Poll(fd: Int, events: Short, timeoutMillis: Int): Boolean = memScoped {
    val descriptor = alloc<pollfd>()
    descriptor.fd = fd
    descriptor.events = events
    val result = poll(descriptor.ptr, 1uL, timeoutMillis)
    result > 0 && (descriptor.revents.toInt() and (POLLIN or POLLERR)) != 0
}

internal fun v4l2Mmap(length: ULong, fd: Int, offset: Long): COpaquePointer? = mmap(
    null,
    length,
    PROT_READ or PROT_WRITE,
    MAP_SHARED,
    fd,
    offset,
)

internal fun v4l2Munmap(pointer: COpaquePointer, length: Int) {
    munmap(pointer, length.convert())
}
