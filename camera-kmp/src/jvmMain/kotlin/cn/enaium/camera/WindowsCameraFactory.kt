package cn.enaium.camera

/**
 * Selects the Windows desktop backend.
 *
 * Media Foundation is the only native capture API on Windows; [CameraBackend.UVC]
 * is not a separate pipeline there because USB cameras are exposed through
 * Media Foundation as well.
 */
internal object WindowsCameraFactory {
    fun create(backend: CameraBackend): CameraSystem = when (backend) {
        CameraBackend.AUTO, CameraBackend.MEDIA_FOUNDATION -> {
            if (!System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
                throw CameraUnsupportedException(
                    "Media Foundation is only available on Windows, but this JVM runs on " +
                        System.getProperty("os.name"),
                )
            }
            JvmMediaFoundationCameraSystem(CameraBackend.MEDIA_FOUNDATION)
        }

        else -> throw CameraUnsupportedException(
            "${backend.displayName} is not available on Windows; use Media Foundation or AUTO",
        )
    }
}
