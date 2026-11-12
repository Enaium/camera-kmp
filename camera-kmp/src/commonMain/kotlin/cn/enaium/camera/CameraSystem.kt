package cn.enaium.camera

/**
 * Entry point of a camera backend: enumeration plus session creation.
 *
 * One instance owns one backend. Open it with [cameraSystem], enumerate once,
 * then open a session. Every session must be closed before the system.
 *
 * ```kotlin
 * cameraSystem().use { system ->
 *     system.cameras().forEach { println("${it.name}: ${it.resolutions}") }
 *     system.open(CameraConfig(format = CameraFormat(1280, 720))).use { session ->
 *         session.start({ frame -> render(frame) })
 *         while (running) Thread.sleep(16)
 *     }
 * }
 * ```
 */
interface CameraSystem : AutoCloseable {
    /** The selected backend. [CameraBackend.UVC] stays visible as UVC even when it is implemented on top of AVFoundation/V4L2/Camera2. */
    val backend: CameraBackend

    /** Human readable backend name, for example `"AVFoundation"`. */
    val name: String

    /** Every camera the backend currently sees. */
    fun cameras(): List<CameraInfo>

    /** The camera the backend would use for [lensFacing], or `null` when there is none. */
    fun defaultCamera(lensFacing: CameraLensFacing = CameraLensFacing.BACK): CameraInfo?

    /** Looks a camera up by [CameraInfo.id]. */
    fun camera(id: String): CameraInfo? = cameras().firstOrNull { it.id == id }

    /**
     * Re-enumerates cameras.
     *
     * Native and Android backends enumerate synchronously, so the default does
     * nothing. The browser can only enumerate asynchronously; there
     * [cameras] returns the most recent snapshot and this call starts the next
     * one, visible on a later [cameras] call.
     */
    fun refresh() {}

    /**
     * Opens the camera selected by [CameraConfig.cameraId], or by
     * [CameraConfig.lensFacing] when no id is set.
     */
    fun open(config: CameraConfig = CameraConfig()): CameraSession

    /** Opens [camera] with [config]. */
    fun open(camera: CameraInfo, config: CameraConfig = CameraConfig()): CameraSession

    /** Releases backend resources. Open sessions must be closed first. */
    override fun close()
}

/**
 * The camera backend of the current platform: AVFoundation on macOS/iOS,
 * Media Foundation on Windows, V4L2 on Linux, Camera2 on Android, Camera2 NDK
 * and V4L2 on Android Native, `getUserMedia` on the web.
 *
 * @param backend [CameraBackend.AUTO] for the platform default, a concrete
 *   backend to force it, or [CameraBackend.UVC] to only see USB cameras.
 * @throws CameraUnsupportedException when the backend does not exist here.
 */
expect fun cameraSystem(backend: CameraBackend = CameraBackend.AUTO): CameraSystem

/** Backends [cameraSystem] accepts on this platform, including [CameraBackend.AUTO]. */
expect fun availableCameraBackends(): List<CameraBackend>
