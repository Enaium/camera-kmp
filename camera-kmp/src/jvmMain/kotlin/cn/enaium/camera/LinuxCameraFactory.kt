package cn.enaium.camera

/**
 * Selects the Linux desktop backend.
 *
 * V4L2 is the only native capture API on Linux; [CameraBackend.UVC] is the
 * same implementation restricted to cameras whose `bus_info` reports USB.
 */
internal object LinuxCameraFactory {
    fun create(backend: CameraBackend): CameraSystem = when (backend) {
        CameraBackend.AUTO, CameraBackend.V4L2 -> JvmV4l2CameraSystem(CameraBackend.V4L2)
        CameraBackend.UVC -> JvmV4l2CameraSystem(CameraBackend.UVC)
        else -> throw CameraUnsupportedException(
            "${backend.displayName} is not available on Linux; use V4L2 or UVC",
        )
    }
}
