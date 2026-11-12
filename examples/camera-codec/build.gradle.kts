import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kotlin/Native's Android toolchain sysroot provides the NDK stub libraries
// (libEGL, libGLESv2, libandroid, ...) that SDL3 and the camera/x264/x265
// backends reference at link time, without requiring a full NDK install.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: providers.gradleProperty("konan.data.dir").getOrElse("${System.getProperty("user.home")}/.konan")
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

fun mingwTool(name: String): String? = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.map { File(it, name) }
    ?.firstOrNull { it.isFile && it.canExecute() }
    ?.absolutePath

val mingwGcc = mingwTool("x86_64-w64-mingw32-gcc")
val mingwAr = mingwTool("x86_64-w64-mingw32-ar")
val gthrShimDir = layout.buildDirectory.dir("gthr-shim")

// libde265-kmp's mingw static library references four GCC win32-thread shims
// that the Kotlin/Native MinGW runtime does not carry. They wrap a Windows
// CRITICAL_SECTION; building this archive keeps the example linkable without
// patching the dependency.
val buildGthrShim = tasks.register("buildGthrShim") {
    group = "build"
    description = "Builds the gthread mutex shim the libde265 mingwX64 library needs."
    onlyIf { mingwGcc != null && mingwAr != null }
    val source = project.file("src/nativeInterop/cinterop/gthr_shim.c")
    val outputDir = gthrShimDir.get().asFile
    inputs.file(source)
    outputs.dir(outputDir)
    doLast {
        outputDir.mkdirs()
        val objectFile = File(outputDir, "gthr_shim.o")
        val compile = ProcessBuilder(
            mingwGcc!!, "-O2", "-c", source.absolutePath, "-o", objectFile.absolutePath,
        ).inheritIO().start().waitFor()
        check(compile == 0) { "the MinGW compiler failed to build gthr_shim.o" }
        val archive = ProcessBuilder(
            mingwAr!!, "rcs", File(outputDir, "libgthr_shim.a").absolutePath, objectFile.absolutePath,
        ).inheritIO().start().waitFor()
        check(archive == 0) { "the MinGW archiver failed to build libgthr_shim.a" }
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
        mainRun {
            mainClass = "cn.enaium.camera.example.codec.Main_jvmKt"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

    macosArm64 { binaries.executable() }
    macosX64 { binaries.executable() }

    // x265-kmp and SDL both embed a glibc 2.38 compatibility shim for
    // __isoc23_sscanf/__isoc23_strtol. They are interchangeable for the
    // example, but lld rejects the duplicate definitions unless the link is
    // allowed to pick one.
    linuxX64 {
        binaries.executable {
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    linuxArm64 {
        binaries.executable {
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    mingwX64 {
        binaries.executable {
            // libde265's mingw static library expects GCC's win32-thread
            // gthread shims (see buildGthrShim).
            if (mingwGcc != null && mingwAr != null) {
                linkerOpts("-L${gthrShimDir.get().asFile.absolutePath}", "-lgthr_shim")
            }
        }
    }

    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    // The root build turns the automatic hierarchy template off, so the shared
    // entry-point directories are added to each native compilation by hand.
    targets.withType<KotlinNativeTarget> {
        val sources = compilations.getByName("main").defaultSourceSet.kotlin
        sources.srcDir("src/nativeMain/kotlin")
        if (name.startsWith("androidNative")) sources.srcDir("src/androidNativeMain/kotlin")
    }

    sourceSets {
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

        commonMain {
            dependencies {
                api(project(":examples:codec-core"))
                implementation(libs.sdl.kmp)
                implementation(libs.imgui.kmp)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// SDL3 (and every other windowing client of the JVM) must create its window on
// the first thread on macOS, otherwise video init fails with "No available
// video device". --enable-native-access silences the JVM warning of the native
// bindings the example draws through.
tasks.withType<JavaExec>().configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}


tasks.matching { it.name.startsWith("link") && it.name.endsWith("ExecutableMingwX64") }.configureEach {
    dependsOn(buildGthrShim)
}
