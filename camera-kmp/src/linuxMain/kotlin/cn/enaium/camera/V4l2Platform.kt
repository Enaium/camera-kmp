package cn.enaium.camera

actual fun availableCameraBackends(): List<CameraBackend> = listOf(
    CameraBackend.AUTO,
    CameraBackend.V4L2,
    CameraBackend.UVC,
)

actual fun cameraSystem(backend: CameraBackend): CameraSystem = when (backend) {
    CameraBackend.AUTO, CameraBackend.V4L2 -> V4l2CameraSystem(CameraBackend.V4L2)
    CameraBackend.UVC -> V4l2CameraSystem(CameraBackend.UVC)
    else -> throw CameraUnsupportedException(
        "${backend.displayName} is not available here; use V4L2 or UVC",
    )
}
