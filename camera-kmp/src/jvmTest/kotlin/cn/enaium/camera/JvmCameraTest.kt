package cn.enaium.camera

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmCameraTest {
    @Test
    fun startsCaptureWhenRequested() {
        if (System.getenv("CAMERA_TEST_CAPTURE") != "1") return
        val system = cameraSystem()
        try {
            val camera = system.defaultCamera() ?: error("no camera")
            val session = system.open(camera, CameraConfig(format = CameraFormat(640, 480, PixelFormat.I420, 30)))
            try {
                session.start({ frame -> println("frame ${frame.width}x${frame.height} ${frame.format.pixelFormat}") }) {
                    println("session error: ${it.message}")
                }
                println("running=${session.isRunning} format=${session.format}")
                assertTrue(session.isRunning)
                val frame = session.readFrame(5_000)
                println("frame arrived: $frame")
            } finally {
                session.close()
            }
        } finally {
            system.close()
        }
    }

    @Test
    fun enumeratesDesktopCameras() {
        val backends = availableCameraBackends()
        println("backends=$backends")
        assertTrue(backends.isNotEmpty(), "the JVM backend should name at least one backend")
        val system = cameraSystem()
        try {
            println("backend=${system.name}")
            val cameras = system.cameras()
            cameras.forEach { camera ->
                println(camera)
                println("  resolutions=${camera.resolutions}")
                println("  facing=${camera.lensFacing} transport=${camera.transport} default=${camera.isDefault}")
            }
            if (System.getenv("CAMERA_TEST_REQUIRE_CAMERA") == "1") {
                assertTrue(cameras.isNotEmpty(), "a desktop machine is expected to expose at least one camera")
                assertNotNull(system.defaultCamera(), "one camera should be the default")
            }
        } finally {
            system.close()
        }
    }
}
