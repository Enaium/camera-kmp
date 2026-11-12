#ifndef CAMERA_KMP_MEDIAFOUNDATION_MIN_H
#define CAMERA_KMP_MEDIAFOUNDATION_MIN_H

/*
 * Media Foundation for the mingwX64 backend.
 *
 * The MinGW sysroot that ships with Kotlin/Native carries the Media Foundation
 * headers but no import library for mfreadwrite, so the source reader factory
 * is resolved from mfreadwrite.dll at runtime (see MediaFoundationCameraSystem);
 * everything else is linked from mfplat, mf and mfuuid.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <mfobjects.h>

#endif
