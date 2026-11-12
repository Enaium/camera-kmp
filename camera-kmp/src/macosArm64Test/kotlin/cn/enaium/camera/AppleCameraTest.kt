@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.camera

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleCameraTest {
    @Test
    fun enumeratesAVFoundationCameras() {
        val system = cameraSystem()
        try {
            println("backend=${system.name}")
            val cameras = system.cameras()
            cameras.forEach { camera ->
                println(camera)
                println("  resolutions=${camera.resolutions}")
                println("  facing=${camera.lensFacing} transport=${camera.transport} default=${camera.isDefault}")
            }
            if (getenv("CAMERA_TEST_REQUIRE_CAMERA")?.toKString() == "1") {
                assertTrue(cameras.isNotEmpty(), "a macOS machine is expected to expose at least one camera")
                assertTrue(cameras.any { it.isDefault }, "one camera should be marked default")
                assertTrue(cameras.all { it.supportedFormats.isNotEmpty() }, "every camera should advertise formats")
            }
        } finally {
            system.close()
        }
    }

    @Test
    fun capturesFramesWhenRequested() {
        if (getenv("CAMERA_TEST_CAPTURE")?.toKString() != "1") return
        val system = cameraSystem()
        try {
            val camera = system.defaultCamera() ?: error("no camera")
            println("capturing from ${camera.name}")
            val session = system.open(
                camera,
                CameraConfig(
                    format = CameraFormat(640, 480, PixelFormat.I420, 30),
                    rotationDegrees = 90,
                    mirrorHorizontally = true,
                ),
            )
            try {
                var received = 0
                session.start({ frame ->
                    received++
                    println("frame ${frame.width}x${frame.height} ${frame.format.pixelFormat} rot=${frame.rotationDegrees} mirror=${frame.mirrored} bytes=${frame.data.size}")
                }) { error -> println("session error: ${error.message}") }
                val frame = session.readFrame(5_000)
                assertNotNull(frame, "no frame arrived; check the camera permission of the terminal")
                assertTrue(frame.width == 480 && frame.height == 640, "rotation should swap the dimensions: $frame")
                assertTrue(frame.mirrored)
                assertTrue(frame.data.size >= frame.format.frameSizeBytes)
                assertTrue(received >= 1)
            } finally {
                session.close()
            }
        } finally {
            system.close()
        }
    }
}
