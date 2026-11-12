import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

kotlin {
    // ==================== JVM (desktop) ====================
    // The desktop backends call the OS frameworks through the Foreign Function
    // & Memory API, final since JDK 22, so the JVM target is not compatible
    // with older JDKs.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

    // ==================== Android (JVM) ====================
    android {
        namespace = "cn.enaium.camera"
        compileSdk = 37
        minSdk = 26

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== Native ====================
    macosArm64()
    macosX64()

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    linuxX64()
    linuxArm64()

    mingwX64()

    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== Web ====================
    // Both web runtimes drive the same getUserMedia backend: the browser entry
    // point, device enumeration and the frame pump live in src/webMain, only
    // the JS interop declarations differ per runtime.
    js(IR) {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }

    // ==================== Per-target sources and cinterop ====================
    // There is no hierarchy template: every native target compiles the shared
    // src/nativeMain plus the directories of the OS family it belongs to.
    // Kotlin/Native resolves expect/actual declarations inside such a
    // compilation, which is what makes the shared directories possible without
    // intermediate source sets.
    targets.withType<KotlinNativeTarget> {
        val targetName = this.name

        compilations.getByName("main") {
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")

            when {
                targetName.startsWith("macos") -> {
                    // AVFoundation, CoreMedia and CoreVideo ship with the
                    // Kotlin/Native Apple platform libraries.
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/appleMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/macosMain/kotlin")
                }

                targetName.startsWith("ios") -> {
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/appleMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/iosMain/kotlin")
                }

                targetName.startsWith("linux") -> {
                    // linux/videodev2.h is vendored: the Kotlin/Native Linux
                    // sysroot does not expose it to cinterop and the header is
                    // stable kernel ABI rather than a user-space library.
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/v4l2NativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/linuxMain/kotlin")
                    cinterops.create("v4l2") {
                        defFile(project.file("src/nativeInterop/cinterop/v4l2.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                }

                targetName == "mingwX64" -> {
                    // Media Foundation is not covered by the MinGW platform
                    // libraries, so its interfaces are described in a vendored
                    // header and linked against the Windows SDK import
                    // libraries.
                    defaultSourceSet.kotlin.srcDir("src/mingwMain/kotlin")
                    cinterops.create("mediafoundation") {
                        defFile(project.file("src/nativeInterop/cinterop/mediafoundation.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                }

                else -> {
                    // androidNative: the NDK camera2 headers are part of the
                    // Kotlin/Native Android sysroot; V4L2 uses the same vendored
                    // kernel header as Linux so external/UVC cameras attached
                    // to the device can be opened through /dev/video*.
                    defaultSourceSet.kotlin.srcDir("src/androidNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/v4l2NativeMain/kotlin")
                    if (targetName == "androidNativeArm64" || targetName == "androidNativeX64") {
                        defaultSourceSet.kotlin.srcDir("src/androidNative64Main/kotlin")
                    } else {
                        defaultSourceSet.kotlin.srcDir("src/androidNative32Main/kotlin")
                    }
                    cinterops.create("camera2") {
                        defFile(project.file("src/nativeInterop/cinterop/camera2.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                    cinterops.create("v4l2") {
                        defFile(project.file("src/nativeInterop/cinterop/v4l2.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                }
            }
        }
    }

    // ==================== Source sets ====================
    sourceSets {
        // No hierarchy template on this project, so the intermediate web source
        // set that both web targets share is wired by hand.
        val webMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.libyuv.kmp)
                implementation(libs.kotlinx.browser)
            }
        }
        val jsMain by getting {
            dependsOn(webMain)
        }
        val wasmJsMain by getting {
            dependsOn(webMain)
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }

        commonMain {
            dependencies {
                api(libs.libyuv.kmp)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }

        val macosArm64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "camera-kmp",
        version = project.version.toString(),
    )

    pom {
        name.set("camera-kmp")
        description.set(
            "Kotlin Multiplatform camera capture: YUV frames, format and resolution selection, " +
                    "rotation and mirroring on the JVM (AVFoundation, Media Foundation, V4L2), " +
                    "Android (Camera2), Android Native (Camera2 NDK, V4L2), iOS/macOS (AVFoundation), " +
                    "Linux (V4L2), Windows (Media Foundation) and the browser (getUserMedia).",
        )
        url.set("https://github.com/Enaium/camera-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/camera-kmp")
            connection.set("scm:git:git@github.com:Enaium/camera-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/camera-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/camera-kmp/issues")
        }
    }
}

// The Kotlin web toolchain wants to fetch its Node build from a repository this
// project does not resolve from. The Node on PATH (every CI image and developer
// machine has one) is used instead.
plugins.withType<NodeJsPlugin> {
    the<NodeJsEnvSpec>().download = false
}

// ==================== wasm/js: npm package ====================
// The web backends only call the flat cameraKmp_* globals; the getUserMedia
// stream, the hidden <video> and the canvas live in camera-kmp-web.js. Pages
// need that file next to the Kotlin module, so it ships as an npm package
// (@enaium/camera-kmp) that is loaded before the module starts.
val webNpmDir = layout.buildDirectory.dir("npm").get().asFile
val webGlue = project.file("src/webMain/resources/camera-kmp-web.js")
val webNpmVersion: String = version.toString()

tasks.register("assembleWebNpm") {
    group = "build"
    description = "Stages the @enaium/camera-kmp npm package in build/npm."
    inputs.file(webGlue)
    inputs.property("npmPackageVersion", webNpmVersion)
    outputs.dir(webNpmDir)
    doLast {
        webNpmDir.deleteRecursively()
        webNpmDir.mkdirs()
        webGlue.copyTo(webNpmDir.resolve("camera-kmp-web.js"), overwrite = true)
        webNpmDir.resolve("README.md").writeText(
            """
            # @enaium/camera-kmp

            getUserMedia glue for the `camera-kmp` browser backend (the `js` and
            `wasmJs` targets). Load it before the Kotlin/Wasm or Kotlin/JS module;
            it defines the `cameraKmp_*` functions the backend calls.

            ```html
            <script src="camera-kmp-web.js"></script>
            ```

            or import it for its side effects from a bundler:

            ```js
            import "@enaium/camera-kmp";
            ```

            The backend attaches the `MediaStream` to a hidden `<video>`, draws the
            current picture into a 2D canvas and copies the RGBA bytes into a typed
            array supplied by Kotlin. `cameraKmp_status` is 0 while the permission
            prompt is open, 1 when frames are available and -1 on failure.
            `cameraKmp_frame` is only called when the application asks for a frame,
            so the page does not run a capture loop of its own.

            The page must be served over HTTPS or from localhost; the browser hides
            camera labels and capability ranges until the user grants permission.

            https://github.com/Enaium/camera-kmp
            """.trimIndent() + "\n",
        )
        webNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/camera-kmp",
              "version": "$webNpmVersion",
              "description": "getUserMedia glue for the camera-kmp browser backend",
              "license": "MIT",
              "main": "camera-kmp-web.js",
              "exports": {
                ".": "./camera-kmp-web.js",
                "./camera-kmp-web.js": "./camera-kmp-web.js",
                "./package.json": "./package.json"
              },
              "files": ["camera-kmp-web.js", "README.md"],
              "keywords": ["camera", "getusermedia", "kotlin", "kotlin-wasm", "yuv"],
              "homepage": "https://github.com/Enaium/camera-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/camera-kmp.git"
              }
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.register<Exec>("publishWebNpm") {
    group = "publishing"
    description = "Publishes @enaium/camera-kmp to npm (token: npm.publish.key)."
    dependsOn("assembleWebNpm")
    workingDir = webNpmDir
    // The token lives in ~/.gradle/gradle.properties (next to the Maven Central
    // and signing entries); CI writes the same key from a repository secret.
    val npmKey = providers.gradleProperty("npm.publish.key")
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties " +
                    "or pass -Pnpm.publish.key=<token>",
            )
        environment("NODE_AUTH_TOKEN", key)
        webNpmDir.resolve(".npmrc").writeText("//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n")
    }
    commandLine("npm", "publish", "--access", "public", "--tag", "latest")
}
