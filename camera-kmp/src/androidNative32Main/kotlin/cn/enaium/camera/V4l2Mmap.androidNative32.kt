@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.convert
import platform.posix.MAP_SHARED
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.mmap
import platform.posix.munmap

// 32-bit bionic takes a 32-bit off_t for mmap; V4L2 MMAP offsets are small.
internal fun v4l2Mmap(length: ULong, fd: Int, offset: Long): COpaquePointer? = mmap(
    null,
    length.convert(),
    PROT_READ or PROT_WRITE,
    MAP_SHARED,
    fd,
    offset.toInt(),
)

internal fun v4l2Munmap(pointer: COpaquePointer, length: Int) {
    munmap(pointer, length.convert())
}
