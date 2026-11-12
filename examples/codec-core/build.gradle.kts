import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Camera capture + H.264/H.265 encode/decode pipeline shared by the desktop and
 * browser examples. It deliberately has no window/renderer dependency so the
 * js/wasmJs targets can use it without pulling SDL in.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
        }
    }

    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    js(IR) {
        browser()
    }
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":camera-kmp"))
                api(libs.libyuv.kmp)
                implementation(libs.x264.kmp)
                implementation(libs.x265.kmp)
                implementation(libs.openh264.kmp)
                implementation(libs.libde265.kmp)
            }
        }
    }
}
