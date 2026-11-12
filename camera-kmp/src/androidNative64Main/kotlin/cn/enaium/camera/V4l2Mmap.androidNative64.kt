@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.convert
import platform.posix.MAP_SHARED
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.mmap
import platform.posix.munmap

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
