package cn.enaium.camera.example.codec.browser

import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement

/** Paints the RGBA bytes into [canvas] through browser-camera-web.js. */
internal expect fun drawRgbaToCanvas(
    canvas: HTMLCanvasElement,
    data: Uint8Array,
    width: Int,
    height: Int,
)
