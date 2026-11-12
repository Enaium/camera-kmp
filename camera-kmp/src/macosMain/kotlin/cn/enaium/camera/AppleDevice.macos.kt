package cn.enaium.camera

import platform.AVFoundation.AVCaptureDevice

/**
 * The `transportType` property is macOS-only; its values are the IOKit
 * `kIOAudioDeviceTransportType*` four-character codes.
 */
internal fun appleTransportType(device: AVCaptureDevice): CameraTransport {
    return when (device.transportType) {
        0x626C746E -> CameraTransport.INTERNAL // 'bltn'
        0x75736220 -> CameraTransport.USB // 'usb '
        0x76697274 -> CameraTransport.VIRTUAL // 'virt'
        else -> CameraTransport.UNKNOWN
    }
}

/**
 * macOS cameras have no back/front position: the built-in one faces the user,
 * everything attached over USB or a virtual device is external.
 */
internal fun appleCameraFacing(device: AVCaptureDevice): CameraLensFacing =
    when (appleTransportType(device)) {
        CameraTransport.INTERNAL -> CameraLensFacing.FRONT
        CameraTransport.USB, CameraTransport.VIRTUAL -> CameraLensFacing.EXTERNAL
        CameraTransport.UNKNOWN -> CameraLensFacing.UNKNOWN
    }
