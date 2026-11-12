package cn.enaium.camera

/** Chooses the desktop backend from the operating system. */
internal class JvmCameraSystem(private val delegate: CameraSystem) : CameraSystem by delegate

private val isMac: Boolean
    get() = System.getProperty("os.name").lowercase().contains("mac")

private val isLinux: Boolean
    get() = System.getProperty("os.name").lowercase().contains("linux")

private val isWindows: Boolean
    get() = System.getProperty("os.name").lowercase().contains("windows")

actual fun availableCameraBackends(): List<CameraBackend> = when {
    isMac -> listOf(CameraBackend.AUTO, CameraBackend.AVFOUNDATION, CameraBackend.UVC)
    isLinux -> listOf(CameraBackend.AUTO, CameraBackend.V4L2, CameraBackend.UVC)
    isWindows -> listOf(CameraBackend.AUTO, CameraBackend.MEDIA_FOUNDATION)
    else -> emptyList()
}

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when {
    isMac -> when (backend) {
        CameraBackend.AUTO, CameraBackend.AVFOUNDATION -> JvmCameraSystem(MacCameraSystem(CameraBackend.AVFOUNDATION))
        CameraBackend.UVC -> JvmCameraSystem(MacCameraSystem(CameraBackend.UVC))
        else -> throw CameraUnsupportedException(
            "${backend.displayName} is not available on macOS; use AVFoundation or UVC",
        )
    }

    isLinux -> LinuxCameraFactory.create(backend)

    isWindows -> WindowsCameraFactory.create(backend)

    else -> throw CameraUnsupportedException(
        "no camera backend for ${System.getProperty("os.name")}; " +
            "supported operating systems are macOS, Linux and Windows",
    )
}
