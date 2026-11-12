package cn.enaium.camera.example.codec.browser

import cn.enaium.camera.I420Frame
import cn.enaium.camera.example.codec.CameraSource
import cn.enaium.camera.example.codec.CodecKind
import cn.enaium.camera.example.codec.PipelineController
import cn.enaium.libyuv.LibYuv
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLScriptElement
import org.w3c.dom.HTMLSelectElement
import kotlin.time.TimeSource

/**
 * Browser counterpart of the two-panel codec example.
 *
 * Unlike the native/JVM entry point this does not use SDL: the two pictures are
 * drawn into 2D canvases and the resolution/codec pickers are ordinary HTML
 * `<select>` elements. The getUserMedia glue (`camera-kmp-web.js`) is loaded
 * dynamically, so the generated Kotlin/JS or Kotlin/Wasm page needs no custom
 * HTML.
 */
private class BrowserDemo(private val root: HTMLDivElement) {
    private val directCanvas = canvas()
    private val decodedCanvas = canvas()
    private val statsLabel = label()
    private val errorLabel = label().apply {
        setAttribute("style", "color:#ff8080;min-height:1.4em;white-space:pre-wrap;")
    }

    private var codec = CodecKind.H264
    private var width = 640
    private var height = 480
    private var camera: CameraSource? = null
    private var pipeline: PipelineController? = null
    private var latest: I420Frame? = null
    private var running = true
    private var encodedFrames = 0L
    private var decodedFrames = 0L
    private var encodedBytes = 0L
    private val started = TimeSource.Monotonic.markNow()

    fun start() {
        buildUi()
        restart()
        window.requestAnimationFrame { loop(it) }
    }

    private fun buildUi() {
        document.body?.setAttribute("style", "margin:0;background:#14141a;color:#e6e6e6;font-family:system-ui,sans-serif;")
        root.setAttribute("style", "padding:16px;display:flex;flex-direction:column;gap:12px;")

        val title = document.createElement("h1") as HTMLElement
        title.textContent = "camera-kmp browser codec loop"
        title.setAttribute("style", "font-size:20px;margin:0;")
        root.appendChild(title)

        val controls = document.createElement("div") as HTMLDivElement
        controls.setAttribute("style", "display:flex;gap:20px;align-items:center;flex-wrap:wrap;")

        val resolutionSelect = document.createElement("select") as HTMLSelectElement
        RESOLUTIONS.forEach { (w, h) ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = "${w}x$h"
            option.text = "${w}x$h"
            resolutionSelect.appendChild(option)
        }
        resolutionSelect.value = "${width}x$height"
        resolutionSelect.onchange = {
            val parts = resolutionSelect.value.split('x')
            width = parts[0].toIntOrNull() ?: width
            height = parts.getOrNull(1)?.toIntOrNull() ?: height
            restart()
        }

        val codecSelect = document.createElement("select") as HTMLSelectElement
        CodecKind.entries.forEach { kind ->
            val option = document.createElement("option") as HTMLOptionElement
            option.value = kind.shortName
            option.text = kind.displayName
            codecSelect.appendChild(option)
        }
        codecSelect.value = codec.shortName
        codecSelect.onchange = {
            codec = CodecKind.parse(codecSelect.value)
            restart()
        }

        controls.appendChild(labeled("Resolution", resolutionSelect))
        controls.appendChild(labeled("Codec", codecSelect))
        root.appendChild(controls)

        val panels = document.createElement("div") as HTMLDivElement
        panels.setAttribute("style", "display:flex;gap:12px;flex-wrap:wrap;")
        panels.appendChild(panel("Camera", directCanvas))
        panels.appendChild(panel("Encoded / decoded", decodedCanvas))
        root.appendChild(panels)

        statsLabel.setAttribute("style", "font-variant-numeric:tabular-nums;color:#b8b8c0;")
        root.appendChild(statsLabel)
        root.appendChild(errorLabel)
    }

    private fun restart() {
        camera?.close()
        pipeline?.close()
        camera = null
        pipeline = null
        latest = null
        encodedFrames = 0
        decodedFrames = 0
        encodedBytes = 0
        errorLabel.textContent = ""

        val activePipeline = PipelineController(codec, FRAME_RATE)
        pipeline = activePipeline
        val source = CameraSource(width, height, FRAME_RATE)
        camera = source
        val opened = source.start(
            onFrame = { latest = it },
            onError = { errorLabel.textContent = it },
            onFailure = { errorLabel.textContent = it.message ?: it.toString() },
        )
        if (!opened) {
            errorLabel.textContent =
                "cannot open a camera; serve the page over HTTPS/localhost and allow camera access"
        }
        updateStats()
    }

    private fun loop(timestamp: Double) {
        val frame = latest
        if (frame != null) {
            latest = null
            draw(directCanvas, frame)
            try {
                val pictures = pipeline?.process(frame).orEmpty()
                pictures.lastOrNull()?.let { draw(decodedCanvas, it) }
                val current = pipeline?.current
                if (current != null) {
                    encodedFrames = current.encodedFrames
                    decodedFrames = current.decodedFrames
                    encodedBytes = current.encodedBytes
                }
            } catch (error: Throwable) {
                errorLabel.textContent = "codec failed: ${error.message ?: error}"
            }
        }
        updateStats()
        if (running) window.requestAnimationFrame { loop(it) }
    }

    private fun updateStats() {
        val seconds = (started.elapsedNow().inWholeMilliseconds / 1000.0).coerceAtLeast(0.001)
        val fps = ((encodedFrames / seconds) * 10).toInt() / 10.0
        val average = if (encodedFrames == 0L) 0 else encodedBytes / encodedFrames
        statsLabel.textContent =
            "${codec.displayName}  ${width}x$height  encoded=$encodedFrames decoded=$decodedFrames " +
                "${average}B/frame ${fps}fps"
    }

    private fun draw(canvas: HTMLCanvasElement, frame: I420Frame) {
        if (canvas.width != frame.width) canvas.width = frame.width
        if (canvas.height != frame.height) canvas.height = frame.height
        val rgba = ByteArray(frame.width * frame.height * 4)
        LibYuv.i420ToAbgr(
            frame.y, frame.u, frame.v, rgba,
            frame.width, frame.height,
            dstStrideAbgr = frame.width * 4,
        )
        val data = Uint8Array(rgba.size)
        for (index in rgba.indices) data[index] = rgba[index]
        drawRgbaToCanvas(canvas, data, frame.width, frame.height)
    }

    private fun panel(title: String, canvas: HTMLCanvasElement): HTMLDivElement {
        val panel = document.createElement("div") as HTMLDivElement
        panel.setAttribute("style", "flex:1 1 420px;min-width:280px;")
        val heading = document.createElement("h2") as HTMLElement
        heading.textContent = title
        heading.setAttribute("style", "font-size:14px;margin:0 0 6px 0;color:#a0a0aa;font-weight:500;")
        canvas.setAttribute("style", "width:100%;max-width:640px;background:#000;border-radius:6px;display:block;")
        panel.appendChild(heading)
        panel.appendChild(canvas)
        return panel
    }

    private fun labeled(text: String, control: HTMLElement): HTMLDivElement {
        val wrapper = document.createElement("div") as HTMLDivElement
        wrapper.setAttribute("style", "display:flex;gap:8px;align-items:center;")
        val label = document.createElement("span") as HTMLElement
        label.textContent = text
        wrapper.appendChild(label)
        wrapper.appendChild(control)
        return wrapper
    }

    private fun label(): HTMLDivElement = document.createElement("div") as HTMLDivElement

    private fun canvas(): HTMLCanvasElement = document.createElement("canvas") as HTMLCanvasElement

    private companion object {
        const val FRAME_RATE = 30
        val RESOLUTIONS = listOf(320 to 240, 640 to 480, 1280 to 720, 1920 to 1080)
    }
}

/** Entry point of both browser targets. */
fun startBrowserDemo() {
    document.title = "camera-kmp browser codec example"
    val root = document.createElement("div") as HTMLDivElement
    document.body?.appendChild(root)

    val glue = document.createElement("script") as HTMLScriptElement
    glue.src = "camera-kmp-web.js"
    glue.onload = { BrowserDemo(root).start() }
    glue.onerror = { _, _, _, _, _ ->
        root.textContent =
            "camera-kmp-web.js could not be loaded; place the @enaium/camera-kmp glue next to the page"
        null
    }
    document.head?.appendChild(glue)
}
