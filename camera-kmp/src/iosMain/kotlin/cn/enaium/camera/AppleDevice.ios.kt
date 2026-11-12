package cn.enaium.camera

import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDevicePositionUnspecified
import platform.AVFoundation.position

/** iOS has no transport property; an unspecified position means an external camera. */
internal fun appleTransportType(device: AVCaptureDevice): CameraTransport = when (device.position) {
    AVCaptureDevicePositionFront, AVCaptureDevicePositionBack -> CameraTransport.INTERNAL
    else -> CameraTransport.USB
}

internal fun appleCameraFacing(device: AVCaptureDevice): CameraLensFacing = when (device.position) {
    AVCaptureDevicePositionFront -> CameraLensFacing.FRONT
    AVCaptureDevicePositionBack -> CameraLensFacing.BACK
    AVCaptureDevicePositionUnspecified -> CameraLensFacing.EXTERNAL
    else -> CameraLensFacing.EXTERNAL
}
