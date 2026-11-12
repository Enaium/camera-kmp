package cn.enaium.camera

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

/**
 * Global `cameraKmp_*` functions installed by `camera-kmp-web.js`.
 *
 * The JavaScript glue owns the DOM objects (MediaStream, video element,
 * canvas) and keeps the frame buffer in a typed array; this file only copies
 * the bytes into a Kotlin array.
 */
private external fun cameraKmp_supported(): Int
private external fun cameraKmp_refreshDevices()
private external fun cameraKmp_deviceCount(): Int
private external fun cameraKmp_deviceId(index: Int): String
private external fun cameraKmp_deviceLabel(index: Int): String
private external fun cameraKmp_deviceFacing(index: Int): Int
private external fun cameraKmp_deviceMaxWidth(index: Int): Int
private external fun cameraKmp_deviceMaxHeight(index: Int): Int
private external fun cameraKmp_deviceMaxFrameRate(index: Int): Int
private external fun cameraKmp_open(deviceId: String?, width: Int, height: Int, frameRate: Int): Int
private external fun cameraKmp_status(handle: Int): Int
private external fun cameraKmp_error(handle: Int): String
private external fun cameraKmp_width(handle: Int): Int
private external fun cameraKmp_height(handle: Int): Int
private external fun cameraKmp_frameRate(handle: Int): Int
private external fun cameraKmp_facing(handle: Int): Int
private external fun cameraKmp_maxWidth(handle: Int): Int
private external fun cameraKmp_maxHeight(handle: Int): Int
private external fun cameraKmp_maxFrameRate(handle: Int): Int
private external fun cameraKmp_frame(handle: Int, destination: org.khronos.webgl.Uint8Array): Int
private external fun cameraKmp_close(handle: Int)

internal actual object WebBridge {

    actual fun supported(): Boolean =
        // The glue script may not be loaded yet (or the module may run under
        // Node); the absent globals must read as "unsupported", not crash.
        runCatching { cameraKmp_supported() != 0 }.getOrDefault(false)

    actual fun refreshDevices() {
        cameraKmp_refreshDevices()
    }

    actual fun deviceCount(): Int = cameraKmp_deviceCount()

    actual fun deviceId(index: Int): String = cameraKmp_deviceId(index)

    actual fun deviceLabel(index: Int): String = cameraKmp_deviceLabel(index)

    actual fun deviceFacing(index: Int): Int = cameraKmp_deviceFacing(index)

    actual fun deviceMaxWidth(index: Int): Int = cameraKmp_deviceMaxWidth(index)

    actual fun deviceMaxHeight(index: Int): Int = cameraKmp_deviceMaxHeight(index)

    actual fun deviceMaxFrameRate(index: Int): Int = cameraKmp_deviceMaxFrameRate(index)

    actual fun open(deviceId: String?, width: Int, height: Int, frameRate: Int): Int =
        cameraKmp_open(deviceId, width, height, frameRate)

    actual fun status(handle: Int): Int = cameraKmp_status(handle)

    actual fun error(handle: Int): String = cameraKmp_error(handle)

    actual fun width(handle: Int): Int = cameraKmp_width(handle)

    actual fun height(handle: Int): Int = cameraKmp_height(handle)

    actual fun frameRate(handle: Int): Int = cameraKmp_frameRate(handle)

    actual fun facing(handle: Int): Int = cameraKmp_facing(handle)

    actual fun maxWidth(handle: Int): Int = cameraKmp_maxWidth(handle)

    actual fun maxHeight(handle: Int): Int = cameraKmp_maxHeight(handle)

    actual fun maxFrameRate(handle: Int): Int = cameraKmp_maxFrameRate(handle)

    private var scratch: Uint8Array? = null

    actual fun frame(handle: Int, destination: ByteArray): Int {
        val scratch = scratch?.takeIf { it.length == destination.size }
            ?: Uint8Array(destination.size).also { scratch = it }
        val written = cameraKmp_frame(handle, scratch)
        if (written <= 0) return written
        val count = minOf(written, destination.size)
        for (index in 0 until count) {
            destination[index] = scratch.get(index).toByte()
        }
        return count
    }

    actual fun close(handle: Int) {
        cameraKmp_close(handle)
    }
}
