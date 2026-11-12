package cn.enaium.camera.example.codec

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.libsdl.app.SDLActivity

/**
 * Launcher activity of the codec example.
 *
 * SDLActivity loads libmain.so and calls its exported SDL_main on a dedicated
 * SDL thread; the camera permission is requested before that thread starts the
 * capture stream. When the user denies it the native side still opens its
 * window and falls back to the synthetic test pattern.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("main")

    private val cameraPermission: Int = 1

    private companion object {
        /** Environment variable the native codec UI reads its scale from. */
        const val DPI_SCALE_ENV = "CAMERA_CODEC_DPI_SCALE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SDL reports the Android window in physical pixels, so the ImGui
        // interface needs the display density to lay out text and widgets at
        // a readable size. Set after super.onCreate (which loads libmain.so)
        // and before the SDL thread starts in onResume.
        nativeSetenv(DPI_SCALE_ENV, resources.displayMetrics.density.toString())
        requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    override fun onConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfiguration)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermission)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        setWindowStyle(true)
    }
}
