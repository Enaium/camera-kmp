plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val webMain by creating {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(project(":examples:codec-core"))
                implementation(project(":camera-kmp"))
                implementation(libs.libyuv.kmp)
                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
            }
            // The getUserMedia glue the camera backend calls has to be served
            // next to the Kotlin module; reusing the library's file keeps the
            // example from carrying a second copy.
            resources.srcDir(rootProject.file("camera-kmp/src/webMain/resources"))
        }
        val jsMain by getting {
            dependsOn(webMain)
        }
        val wasmJsMain by getting {
            dependsOn(webMain)
        }
    }
}
