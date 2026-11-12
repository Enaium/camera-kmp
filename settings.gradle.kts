pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "camera-kmp"

include(":camera-kmp")
include(":examples:codec-core")
include(":examples:camera-codec")
include(":examples:browser-camera")
include(":examples:camera-codec:android")
