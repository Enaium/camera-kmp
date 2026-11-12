import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import java.util.Properties
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.android.application)
}

// The example links one libmain.so per Android ABI (with an exported SDL_main
// entry point); this module copies them into jniLibs and depends on the link
// tasks so the APK is always assembled from the current libraries.
val pinnedNdkVersion = "27.0.12077973"

val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

val cxxSharedTriple = mapOf(
    "androidNativeArm64" to "aarch64-linux-android",
    "androidNativeArm32" to "arm-linux-androideabi",
    "androidNativeX64" to "x86_64-linux-android",
    "androidNativeX86" to "i686-linux-android",
)

abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cxxSharedTriples: MapProperty<String, String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val sdkDir: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @TaskAction
    fun run() {
        val bin = project.layout.projectDirectory.dir("../build/bin").asFile
        val binaries = "main${buildType.get().replaceFirstChar { it.uppercase() }}Shared"
        outputDir.get().asFile.deleteRecursively()

        fun ndkCxxShared(target: String): File? {
            val sdkDir = sdkDir.orNull ?: return null
            val ndkRoot = File(sdkDir, "ndk")
            val ndk = ndkRoot.resolve(ndkVersion.get()).takeIf { it.isDirectory }
                ?: ndkRoot.listFiles()
                    ?.filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)+")) }
                    ?.maxByOrNull { it.name }
                ?: return null
            val prebuiltDir = File(ndk, "toolchains/llvm/prebuilt")
            // The NDK host directory is named after the build host, not the
            // JVM architecture: Apple Silicon still uses darwin-x86_64.
            val hostPrefix = when {
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin-"
                System.getProperty("os.name").lowercase().contains("windows") -> "windows-"
                else -> "linux-"
            }
            val host = prebuiltDir.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.startsWith(hostPrefix) }
                ?: return null
            val triple = cxxSharedTriples.get()[target] ?: return null
            return File(host, "sysroot/usr/lib/$triple/libc++_shared.so").takeIf { it.exists() }
        }

        abis.get().forEach { (target, abi) ->
            val source = File(bin, "$target/$binaries/libmain.so")
            if (!source.exists()) {
                throw GradleException("Expected $source - did the libmain.so link task fail?")
            }
            val destination = File(outputDir.get().asFile, abi)
            destination.mkdirs()
            source.copyTo(File(destination, "libmain.so"), overwrite = true)
            // The libc++ that Kotlin/Native bundles can be older than the one
            // the codec/SDL static libraries were linked against; using it
            // makes libmain.so fail to load with a missing std::__ndk1
            // symbol. The APK therefore requires the pinned project NDK.
            val cxxShared = ndkCxxShared(target)
                ?: throw GradleException(
                    "No libc++_shared.so for $abi. Install Android NDK ${ndkVersion.get()} " +
                        "(sdkmanager \"ndk;${ndkVersion.get()}\") and point ANDROID_HOME or " +
                        "local.properties sdk.dir at that SDK.",
                )
            cxxShared.copyTo(File(destination, "libc++_shared.so"), overwrite = true)
        }
    }
}

fun registerPrepareJniLibs(buildType: String): TaskProvider<PrepareJniLibsTask> {
    val capitalized = buildType.replaceFirstChar { it.uppercase() }
    val task = tasks.register<PrepareJniLibsTask>("prepareJniLibs$capitalized") {
        outputDir.set(layout.buildDirectory.dir("generated/jniLibs/$buildType"))
        abis.set(androidAbis)
        cxxSharedTriples.set(cxxSharedTriple)
        this.buildType.set(buildType)
        sdkDir.set(androidSdkDir.orEmpty())
        ndkVersion.set(pinnedNdkVersion)
        androidAbis.keys.forEach { target ->
            val linkTask = project(":examples:camera-codec").tasks.named(
                "linkMain${capitalized}Shared${target.replaceFirstChar { it.uppercase() }}",
            )
            dependsOn(linkTask)
            inputs.files(linkTask.flatMap { (it as KotlinNativeLink).outputFile })
        }
    }
    return task
}

val androidSdkDir: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }

val prepareDebugJniLibs = registerPrepareJniLibs("debug")
val prepareReleaseJniLibs = registerPrepareJniLibs("release")

android {
    namespace = "cn.enaium.camera.example.codec"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "cn.enaium.camera.example.codec"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += androidAbis.values
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // SDL3 ships a per-ABI libsdl_jni.so for the JVM bindings; this app
            // is native only and SDL3 is linked into libmain.so.
            excludes += "**/libsdl_jni.so"
        }
    }
}

androidComponents {
    onVariants { variant ->
        val prepare = if (variant.buildType == "release") prepareReleaseJniLibs else prepareDebugJniLibs
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare) { it.outputDir }
    }
}

dependencies {
    implementation(libs.sdl.kmp)
}
