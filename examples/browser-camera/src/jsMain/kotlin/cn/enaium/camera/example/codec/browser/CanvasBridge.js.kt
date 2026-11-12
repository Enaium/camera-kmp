package cn.enaium.camera.example.codec.browser

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.js

internal actual fun drawRgbaToCanvas(
    canvas: HTMLCanvasElement,
    data: Uint8Array,
    width: Int,
    height: Int,
) {
    js("(c, d, w, h) => globalThis.cameraKmpDrawRGBA(c, d, w, h)")(canvas, data, width, height)
}
