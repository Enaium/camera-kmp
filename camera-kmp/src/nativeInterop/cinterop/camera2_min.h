#ifndef CAMERA_KMP_CAMERA2_MIN_H
#define CAMERA_KMP_CAMERA2_MIN_H

/*
 * Camera2 NDK for the Android Native targets.
 *
 * Kotlin/Native's Android cinterop compiles with the toolchain's base API
 * level, while every camera2 NDK declaration is guarded by
 * __ANDROID_API__ >= 24. Raising the level before including the headers keeps
 * the manager/device/metadata and image reader declarations in the bindings.
 */
#undef __ANDROID_API__
#define __ANDROID_API__ 26

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCaptureRequest.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>
#include <media/NdkImage.h>

#endif
