package cn.enaium.camera

/**
 * JavaScript side of the browser backend.
 *
 * `camera-kmp-web.js` owns the `getUserMedia` stream, the hidden video element
 * and the canvas that turns the current picture into RGBA. The functions here
 * only marshal primitives and one frame buffer across, so Kotlin/JS and
 * Kotlin/Wasm share the same backend code.
 */
internal expect object WebBridge {

    /** `true` when `navigator.mediaDevices.getUserMedia` exists. */
    fun supported(): Boolean

    /** Starts an asynchronous `enumerateDevices()`; the device accessors follow when it lands. */
    fun refreshDevices()

    fun deviceCount(): Int

    fun deviceId(index: Int): String

    fun deviceLabel(index: Int): String

    /** `0` front, `1` back, `2` external, `3` unknown. */
    fun deviceFacing(index: Int): Int

    /** Maximum width the device advertised, `0` when the browser hides it before permission. */
    fun deviceMaxWidth(index: Int): Int

    fun deviceMaxHeight(index: Int): Int

    fun deviceMaxFrameRate(index: Int): Int

    /** Opens a stream; the handle is valid immediately, [status] reports when permission resolved. */
    fun open(deviceId: String?, width: Int, height: Int, frameRate: Int): Int

    /** `0` pending, `1` ready, `-1` failed. */
    fun status(handle: Int): Int

    fun error(handle: Int): String

    fun width(handle: Int): Int

    fun height(handle: Int): Int

    fun frameRate(handle: Int): Int

    /** `0` front, `1` back, `2` external, `3` unknown. */
    fun facing(handle: Int): Int

    fun maxWidth(handle: Int): Int

    fun maxHeight(handle: Int): Int

    fun maxFrameRate(handle: Int): Int

    /**
     * Copies the current RGBA frame into [destination] and returns the number
     * of bytes written, or `0` when the video has no picture yet.
     */
    fun frame(handle: Int, destination: ByteArray): Int

    fun close(handle: Int)
}
