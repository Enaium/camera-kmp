@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.camera.example.codec.browser

import kotlin.JsFun
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement

@JsFun("(canvas, data, width, height) => globalThis.cameraKmpDrawRGBA(canvas, data, width, height)")
private external fun drawRgbaWeb(
    canvas: HTMLCanvasElement,
    data: Uint8Array,
    width: Int,
    height: Int,
)

internal actual fun drawRgbaToCanvas(
    canvas: HTMLCanvasElement,
    data: Uint8Array,
    width: Int,
    height: Int,
) = drawRgbaWeb(canvas, data, width, height)
