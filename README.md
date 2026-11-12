# camera-kmp

Kotlin Multiplatform camera capture with raw YUV frames, CameraX-style device
information, configurable resolution / pixel format / frame rate, rotation and
mirroring, on every desktop and mobile platform Kotlin supports.

```kotlin
cameraSystem().use { system ->
    system.cameras().forEach { camera ->
        println("${camera.name}: ${camera.resolutions}")
    }
    system.open(
        CameraConfig(
            format = CameraFormat(1280, 720, PixelFormat.I420, 30),
            lensFacing = CameraLensFacing.FRONT,
            mirrorHorizontally = true,
        ),
    ).use { session ->
        session.start({ frame -> consume(frame.toI420()) })
        // ... or pull frames: session.readFrame(1000)
    }
}
```

## Backends

| Platform | Target | Backends |
| --- | --- | --- |
| Android | `android` (JVM) | Camera2 |
| Android Native | `androidNative*` | Camera2 NDK, V4L2 |
| iOS | `iosArm64`, `iosX64`, `iosSimulatorArm64` | AVFoundation |
| macOS | `macosArm64`, `macosX64` | AVFoundation, UVC |
| Linux | `linuxX64`, `linuxArm64` | V4L2, UVC |
| Windows | `mingwX64` | Media Foundation |
| JVM desktop | `jvm` | AVFoundation on macOS, Media Foundation on Windows, V4L2 on Linux |
| Browser | `js`, `wasmJs` | `navigator.mediaDevices.getUserMedia` |

`CameraBackend.UVC` is a selection filter rather than a second driver: USB
cameras are exposed by the operating system's own framework, so selecting UVC
lists only cameras whose transport is USB/external (AVFoundation on Apple,
`bus_info`/`uvcvideo` on Linux, `LENS_FACING_EXTERNAL` on Android).

## Features

- YUV and packed frames: `I420`, `NV12`, `NV21`, `YUY2`, `UYVY`, `RGBA`, `BGRA`,
  `RGB24`. A session delivers exactly the requested layout regardless of what
  the camera produces; conversion is done with libyuv.
- Resolution, frame rate and pixel format selection, with the closest native
  mode chosen automatically and reported through `CameraSession.format`.
- Clockwise rotation (0/90/180/270) and horizontal mirroring applied by the
  library, changeable while streaming.
- Device enumeration with lens facing, sensor orientation, transport type,
  device path and supported resolutions / frame rates.
- Callback (`start`) and blocking pull (`readFrame`) delivery; the browser is
  callback-only by design.
- Explicit backend selection (`cameraSystem(CameraBackend.V4L2)`) and
  `CameraBackend.AUTO` for the platform default.
- No camera/preview UI dependency: frames are plain `ByteArray`s, ready for
  encoders, ML or a renderer.

## Installation

```kotlin
implementation("cn.enaium.camera:camera-kmp:1.0.0")
```

The JVM desktop backend uses the Foreign Function & Memory API, so the `jvm`
target needs **JDK 22 or newer** at runtime. Other targets have no extra native
dependencies: libyuv ships with the artifact.

## API

### Systems and devices

```kotlin
val system = cameraSystem()                 // platform default
// cameraSystem(CameraBackend.UVC)          // only USB cameras
// cameraSystem(CameraBackend.CAMERA2)      // force Camera2 on Android

val cameras: List<CameraInfo> = system.cameras()
val camera = system.defaultCamera(CameraLensFacing.BACK) ?: cameras.first()

camera.resolutions            // List<CameraResolution>, largest first
camera.frameRates(camera.resolutions.first())
camera.nativePixelFormats     // layouts the backend delivers natively
camera.sensorOrientation      // degrees, for display compensation
camera.transport              // INTERNAL / USB / VIRTUAL / UNKNOWN
camera.devicePath             // /dev/video0 where applicable

val mode = camera.bestFormat(1280, 720, pixelFormat = null, frameRate = 30)
```

### Sessions

```kotlin
val session = system.open(
    camera,
    CameraConfig(
        format = CameraFormat(1280, 720, PixelFormat.NV12, 30),
        rotationDegrees = 90,
        mirrorHorizontally = false,
        bufferCount = 4,
    ),
)

session.start(
    listener = { frame -> render(frame) },
    onError = { error -> log(error) },
)

session.setRotation(270)
session.setMirror(true)
val frame = session.readFrame(timeoutMillis = 1_000) // not on the web
session.stop()
session.close()
```

Front/back selection is part of `CameraConfig`; switching lenses means closing
the session and opening a new one with the other `lensFacing` (the backend
releases the device in between).

`CameraSession.format` reports the negotiated output format (native resolution,
requested pixel format and frame rate, with rotation applied to width/height).
`CameraConfig.outputFormat` reports the same before opening.

### Frames

`CameraFrame` is a tightly packed snapshot:

```kotlin
class CameraFrame(
    val format: CameraFormat,
    val data: ByteArray,
    val timestampNanos: Long,
    val rotationDegrees: Int,
    val mirrored: Boolean,
)
```

Use `frame.toI420()` for a lossless planar picture with separate `y`, `u` and
`v` arrays (the layout the example encoders consume), or read `uOffset` /
`vOffset` and the `PixelFormat` row sizes directly.

## Platform notes

### Android

Add the permission and request it before opening:

The library and its libyuv dependency require `compileSdk = 37` (or newer) in
the consuming Android module.

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

```kotlin
if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
}
```

Camera2 is used on the JVM Android target; the NDK Camera2 API and V4L2 are used
on the `androidNative*` targets. V4L2 on Android is typically limited to
external/UVC cameras and depends on the device's SELinux policy.

The JVM Android backend needs an application `Context` to reach `CameraManager`.
The library manifest registers a non-exported `CameraContextProvider` that
captures it before the first activity starts; apps that remove providers can
call `installAndroidCameraContext(context)` instead.

### Apple

Add `NSCameraUsageDescription` to the app's `Info.plist`. The first session
triggers the system permission prompt. On macOS the terminal/app also needs
camera access in System Settings → Privacy & Security → Camera.

### Linux

The process needs read/write access to `/dev/video*` (usually the `video`
group). UVC cameras appear as V4L2 devices with `usb` in their bus info.

### Windows

Media Foundation is used directly. Windows 10/11 and a camera driver that
exposes NV12 or YUY2 are required; MJPEG-only modes are not exposed by this
library (use a camera mode with a raw format).

### Web

Serve the page over HTTPS or `localhost`; `getUserMedia` is unavailable on
plain HTTP. Load the glue script before the Kotlin module:

```html
<script src="camera-kmp-web.js"></script>
```

or, with a bundler:

```js
import "@enaium/camera-kmp";
```

The npm package is `@enaium/camera-kmp` and contains `camera-kmp-web.js`.
Browsers hide labels and capability ranges until permission is granted, so
`cameras()` improves after the first `start()`; `refresh()` starts a new
enumeration. `readFrame` is not supported on the web — use `start`.

## Examples

`examples:camera-codec` shows two live pictures side by side: the camera frame
and the same frame after a full H.264 or H.265 encode/decode round trip.

- Encoders: [x264-kmp](https://github.com/Enaium/x264-kmp),
  [x265-kmp](https://github.com/Enaium/x265-kmp).
- Decoders: [openh264-kmp](https://github.com/Enaium/openh264-kmp),
  [libde265-kmp](https://github.com/Enaium/libde265-kmp).
- YUV conversion: [libyuv-kmp](https://github.com/Enaium/libyuv-kmp).
- Window/renderer: [sdl-kmp](https://github.com/Enaium/sdl-kmp).
- Interface: [imgui-kmp](https://github.com/Enaium/imgui-kmp).

The windowed and Android builds show an ImGui control panel for resolution,
codec, source (camera or synthetic), bitrate and frame rate. Bitrate and frame
rate apply when their slider is released; the other controls apply immediately.


```bash
# Window, real camera, H.264
./gradlew :examples:camera-codec:jvmRun --args="--source camera --codec h264"

# Window, synthetic test pattern, HEVC
./gradlew :examples:camera-codec:jvmRun --args="--source synthetic --codec hevc"

# Headless verification (CI): 60 frames through encode + decode
./gradlew :examples:camera-codec:jvmRun \
  --args="--headless --source synthetic --frames 60 --codec h264"

# Native macOS
CAMERA_CODEC_HEADLESS=1 CAMERA_CODEC_SOURCE=synthetic CAMERA_CODEC_FRAMES=60 \
CAMERA_CODEC_CODEC=h264 \
./gradlew :examples:camera-codec:runDebugExecutableMacosArm64

# Android APK (Camera permission requested by MainActivity)
# Requires Android NDK 27.0.12077973; the build copies its libc++_shared.so
# because Kotlin/Native's bundled copy is too old for the codec libraries.
./gradlew :examples:camera-codec:android:assembleDebug
```

Options: `--frames N` (`0` = unlimited), `--codec h264|hevc`,
`--source camera|synthetic`, `--headless`, `--width`, `--height`, `--fps`,
`--bitrate KBPS` (default 2000), `--quit-after-ms N` (closes the window after
N ms, useful for automated windowed runs). Native binaries read the same
options from `CAMERA_CODEC_*` environment variables
(`CAMERA_CODEC_QUIT_AFTER_MS` for the timeout). The interactive UI can change
resolution, codec, source, bitrate and frame rate without restarting the app.

When no camera is available, the example falls back to a deterministic colour
bar pattern, so the pipeline can be verified on CI runners and on machines
without camera permission.

### Browser example

`examples:browser-camera` runs the same two-view pipeline in a page. The
JavaScript and Wasm builds both use the real `getUserMedia` backend and the
Wasm codec builds (x264/x265 plus openh264/libde265), so no WebCodecs support
is required. Select the resolution and H.264 or H.265 in the page; the left
canvas is the camera frame and the right one is the decoded round trip.

```bash
# JS/Wasm page, built and served as static files
./gradlew :examples:browser-camera:jsBrowserDistribution
python3 -m http.server 8000 \
  --directory examples/browser-camera/build/dist/js/productionExecutable

./gradlew :examples:browser-camera:wasmJsBrowserDistribution
python3 -m http.server 8001 \
  --directory examples/browser-camera/build/dist/wasmJs/productionExecutable
```

Open `http://localhost:8000/` (JS) or `http://localhost:8001/` (Wasm) and allow
the camera. The page loads the `camera-kmp-web.js` glue and
`browser-camera-web.js` drawing helper next to the generated module.

## Building

```bash
./gradlew :camera-kmp:compileKotlinJvm
./gradlew :camera-kmp:jvmTest
./gradlew :camera-kmp:macosArm64Test
./gradlew :examples:camera-codec:jvmRun --args="--headless --source synthetic --frames 60"
```

Publishing is split across runners because Maven Central coordinates are
immutable; see `.github/workflows/publish.yml`. The web glue is published as the
`@enaium/camera-kmp` npm package.

## Verification

The macOS ARM64 development host exercises AVFoundation through both the
Kotlin/Native and JVM backends (enumeration, format selection, live capture),
the common test suite on JVM/native/JS/Wasm, the H.264 and HEVC examples on JVM
and macosArm64 (headless and windowed), both codecs in the JS and Wasm browser
example (Chromium fake-camera device), the Android AAR/APK assembly and the
mingwX64 example link. The APK was also installed on an arm64 emulator: it
loaded `libmain.so`, opened the emulated Camera2 device and rendered both
views (120 frames logged). The Android ImGui panel was exercised on that
emulator (resolution, codec and bitrate controls; selecting HEVC and moving the
bitrate slider restarted the pipeline at the new settings). On a Fedora 44
ARM64 VM with a VMware Virtual USB Video Device, the JVM and the cross-linked
`linuxArm64` native examples both captured 640x480 through V4L2 and
encoded/decoded H.264 and H.265 (the JVM H.265 run used a locally rebuilt x265
JNI, see Limitations). The native Cocoa window was closed
through its title-bar button in both debug and release builds (release after
360+ rendered frames); the process exits cleanly. The Linux V4L2, Windows Media
Foundation and Android Camera2/NDK backends are compiled for their targets and
linked where the host allows; runtime capture there requires the corresponding
operating system and camera hardware.

## Limitations

- MJPEG-only V4L2/Media Foundation modes are not decoded.
- Torch, zoom, focus/exposure controls and photo capture are not part of this
  first version; the API is raw video frames.
- `readFrame` is unavailable on the web; the browser is callback-driven.
- Web format lists are derived from the capability ranges the browser exposes
  (it does not publish an exact mode list).
- The JVM desktop target requires JDK 22+.
- The Android example packages the NDK 27 `libc++_shared.so`. Kotlin/Native's
  bundled libc++ lacks a `std::__ndk1` vtable used by the codec libraries, and
  replacing it with the NDK copy is what lets `libmain.so` load.
- On Linux ARM64, the published x265-kmp `4.2.0.1` JVM JNI jar contains an
  x86_64 `libx265_jni.so` under `linux-aarch64`; JVM HEVC needs a corrected
  JNI library there. The Kotlin/Native and web HEVC paths are unaffected.
- Kotlin/Native 2.4 does not support Linux ARM64 as a compiler host. The
  Linux ARM64 native example must be cross-linked from macOS or Linux x86_64
  and then executed on the ARM64 machine; cross-linking from macOS may also
  need an AArch64 Linux `libgcc.a` that provides the outline-atomic helpers
  used by the SDL build.
- On `mingwX64`, the published `libde265-kmp` static library references four
  GCC win32-thread gthread mutex shims that the Kotlin/Native MinGW runtime does
  not ship. The example builds a four-function shim
  (`examples/camera-codec/src/nativeInterop/cinterop/gthr_shim.c`); an
  application using libde265 on Windows needs the same archive, or an upstream
  libde265-kmp build made against the Kotlin/Native runtime.

## License

MIT
