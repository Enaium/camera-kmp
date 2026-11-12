package cn.enaium.camera

/**
 * Thrown when a camera cannot be enumerated or opened, or when a backend
 * rejects a configuration.
 *
 * Camera backends never crash the process for recoverable problems (no camera,
 * missing permission, unsupported format): the exception is delivered from the
 * opening call or through the session's error callback.
 */
open class CameraException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the requested backend or pixel format is not available on this platform. */
class CameraUnsupportedException(message: String, cause: Throwable? = null) : CameraException(message, cause)

/**
 * Selects a capture backend.
 *
 * [AUTO] is the platform default (`AVFoundation` on Apple, `Media Foundation`
 * on Windows, `V4L2` on Linux, `Camera2` on Android, `getUserMedia` on the
 * web). [UVC] is a selection filter rather than a separate implementation:
 * UVC cameras are exposed by the operating system's own capture framework, so
 * the backend lists only cameras whose transport is USB/external.
 */
enum class CameraBackend(val displayName: String) {
    /** The platform's preferred backend. */
    AUTO("Auto"),

    /** Apple AVFoundation (macOS, iOS). */
    AVFOUNDATION("AVFoundation"),

    /** Windows Media Foundation. */
    MEDIA_FOUNDATION("Media Foundation"),

    /** Linux V4L2, also used for Android Native `/dev/video*` access. */
    V4L2("V4L2"),

    /** Android Camera2, available on the JVM/Android and Android Native. */
    CAMERA2("Camera2"),

    /** Only cameras attached over USB (UVC), through the platform framework. */
    UVC("UVC"),

    /** Browser `navigator.mediaDevices.getUserMedia`. */
    WEB("getUserMedia"),
}

/** Which way the camera faces. */
enum class CameraLensFacing {
    /** Towards the user, usually above the display. */
    FRONT,

    /** Away from the user. */
    BACK,

    /** A camera that is not part of the device, for example a USB/UVC camera. */
    EXTERNAL,

    /** The backend cannot tell. */
    UNKNOWN,
}

/** How the camera is attached, when the backend can tell. */
enum class CameraTransport {
    /** Built into the device. */
    INTERNAL,

    /** Attached over USB (a UVC camera in the usual case). */
    USB,

    /** A virtual camera, for example an OBS or Continuity Camera device. */
    VIRTUAL,

    UNKNOWN,
}
