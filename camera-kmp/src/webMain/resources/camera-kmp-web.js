/*
 * getUserMedia glue for the camera-kmp browser backend.
 *
 * The Kotlin side (both js and wasmJs) only exchanges primitives and one RGBA
 * buffer with this file: a MediaStream is attached to a hidden <video>, the
 * current picture is drawn into a 2D canvas and getImageData() hands the bytes
 * back. Keeping the DOM objects here means Kotlin/Wasm and Kotlin/JS share a
 * single implementation and no DOM declarations have to be duplicated.
 *
 * Load this file before the Kotlin module (see the README). It installs flat
 * `cameraKmp_*` globals and does nothing when they already exist, so including
 * it twice is harmless.
 *
 * Frame handles are polled: `status` is 0 while the permission prompt is open,
 * 1 once the video has a picture, -1 when getUserMedia failed. Frames are only
 * pulled when the caller asks for one, so the browser does no work while the
 * application is not rendering.
 */
(function () {
    if (globalThis.cameraKmp_supported) return;

    var devices = [];
    var streams = new Map();
    var nextHandle = 1;

    function mediaDevices() {
        return globalThis.navigator && navigator.mediaDevices ? navigator.mediaDevices : null;
    }

    function consoleError(message, error) {
        if (globalThis.console) globalThis.console.error('camera-kmp: ' + message, error);
    }

    // 0 front, 1 back, 2 external, 3 unknown.
    function facingFromString(value) {
        if (!value) return 3;
        var text = String(value).toLowerCase();
        if (text === 'user' || text === 'front') return 0;
        if (text === 'environment' || text === 'back' || text === 'rear') return 1;
        if (text === 'external' || text === 'usb') return 2;
        return 3;
    }

    function facingFromLabel(label) {
        var text = (label || '').toLowerCase();
        if (text.indexOf('front') >= 0 || text.indexOf('facetime') >= 0) return 0;
        if (text.indexOf('back') >= 0 || text.indexOf('rear') >= 0) return 1;
        if (text.indexOf('usb') >= 0 || text.indexOf('external') >= 0) return 2;
        return 3;
    }

    function capabilities(device) {
        try {
            return device && typeof device.getCapabilities === 'function' ? device.getCapabilities() : {};
        } catch (error) {
            return {};
        }
    }

    function rangeMax(range) {
        if (!range) return 0;
        if (typeof range === 'number') return range;
        return range.max ? range.max : 0;
    }

    globalThis.cameraKmp_supported = function () {
        var md = mediaDevices();
        return !!(md && md.getUserMedia);
    };

    globalThis.cameraKmp_refreshDevices = function () {
        var md = mediaDevices();
        if (!md || !md.enumerateDevices) return;
        md.enumerateDevices().then(function (list) {
            devices = list.filter(function (device) {
                return device.kind === 'videoinput';
            });
        }).catch(function (error) {
            devices = [];
            consoleError('cannot enumerate cameras', error);
        });
    };

    globalThis.cameraKmp_deviceCount = function () {
        return devices.length;
    };

    globalThis.cameraKmp_deviceId = function (index) {
        var device = devices[index];
        return device ? device.deviceId : '';
    };

    globalThis.cameraKmp_deviceLabel = function (index) {
        var device = devices[index];
        if (!device) return '';
        if (device.label) return device.label;
        return 'Camera ' + (index + 1);
    };

    globalThis.cameraKmp_deviceFacing = function (index) {
        var device = devices[index];
        if (!device) return 3;
        var caps = capabilities(device);
        if (caps.facingMode && caps.facingMode.length) {
            return facingFromString(caps.facingMode[0]);
        }
        return facingFromLabel(device.label);
    };

    globalThis.cameraKmp_deviceMaxWidth = function (index) {
        var device = devices[index];
        return device ? rangeMax(capabilities(device).width) : 0;
    };

    globalThis.cameraKmp_deviceMaxHeight = function (index) {
        var device = devices[index];
        return device ? rangeMax(capabilities(device).height) : 0;
    };

    globalThis.cameraKmp_deviceMaxFrameRate = function (index) {
        var device = devices[index];
        return device ? Math.round(rangeMax(capabilities(device).frameRate)) : 0;
    };

    globalThis.cameraKmp_open = function (deviceId, width, height, frameRate) {
        var handle = nextHandle++;
        var entry = {
            status: 0,
            error: '',
            stream: null,
            track: null,
            video: null,
            canvas: null,
            context: null,
            settings: {},
            capabilities: {}
        };
        streams.set(handle, entry);

        var md = mediaDevices();
        if (!md || !md.getUserMedia) {
            entry.status = -1;
            entry.error = 'getUserMedia is not available (the page must be served over HTTPS or localhost)';
            return handle;
        }

        var video = {
            width: { ideal: width },
            height: { ideal: height },
            frameRate: { ideal: frameRate }
        };
        if (deviceId) {
            video.deviceId = { exact: deviceId };
        }

        md.getUserMedia({ audio: false, video: video }).then(function (stream) {
            if (!streams.has(handle)) {
                stream.getTracks().forEach(function (track) { track.stop(); });
                return;
            }
            entry.stream = stream;
            entry.track = stream.getVideoTracks()[0] || null;
            if (entry.track) {
                try {
                    entry.settings = entry.track.getSettings ? entry.track.getSettings() : {};
                } catch (error) {
                    entry.settings = {};
                }
                try {
                    entry.capabilities = entry.track.getCapabilities ? entry.track.getCapabilities() : {};
                } catch (error) {
                    entry.capabilities = {};
                }
            }
            var element = globalThis.document.createElement('video');
            element.autoplay = true;
            element.muted = true;
            element.playsInline = true;
            element.srcObject = stream;
            // Some browsers only pump a playing video that is in the
            // document; keep it invisible instead of detached.
            element.style.position = 'fixed';
            element.style.width = '1px';
            element.style.height = '1px';
            element.style.opacity = '0';
            element.style.pointerEvents = 'none';
            globalThis.document.body.appendChild(element);
            entry.video = element;
            var playing = element.play();
            if (playing && playing.catch) {
                playing.catch(function (error) {
                    consoleError('video.play() failed', error);
                });
            }
            entry.status = 1;
            console.log('camera-kmp: getUserMedia resolved', entry.settings);
        }).catch(function (error) {
            entry.status = -1;
            entry.error = (error && error.name ? error.name + ': ' : '') +
                (error && error.message ? error.message : String(error));
            console.error('camera-kmp: getUserMedia failed', entry.error);
        });

        return handle;
    };

    function entry(handle) {
        return streams.get(handle) || null;
    }

    globalThis.cameraKmp_status = function (handle) {
        var value = entry(handle);
        return value ? value.status : -1;
    };

    globalThis.cameraKmp_error = function (handle) {
        var value = entry(handle);
        return value ? value.error : 'unknown camera handle';
    };

    function actualWidth(value) {
        if (value.settings.width) return value.settings.width | 0;
        if (value.video && value.video.videoWidth) return value.video.videoWidth | 0;
        return 0;
    }

    function actualHeight(value) {
        if (value.settings.height) return value.settings.height | 0;
        if (value.video && value.video.videoHeight) return value.video.videoHeight | 0;
        return 0;
    }

    globalThis.cameraKmp_width = function (handle) {
        var value = entry(handle);
        return value ? actualWidth(value) : 0;
    };

    globalThis.cameraKmp_height = function (handle) {
        var value = entry(handle);
        return value ? actualHeight(value) : 0;
    };

    globalThis.cameraKmp_frameRate = function (handle) {
        var value = entry(handle);
        if (!value) return 0;
        var rate = value.settings.frameRate;
        return rate ? Math.max(1, Math.round(rate)) : 0;
    };

    globalThis.cameraKmp_facing = function (handle) {
        var value = entry(handle);
        if (!value) return 3;
        var facing = facingFromString(value.settings.facingMode);
        if (facing !== 3) return facing;
        var caps = value.capabilities || {};
        if (caps.facingMode && caps.facingMode.length) {
            return facingFromString(caps.facingMode[0]);
        }
        return 3;
    };

    globalThis.cameraKmp_maxWidth = function (handle) {
        var value = entry(handle);
        if (!value) return 0;
        return rangeMax(value.capabilities.width) || actualWidth(value);
    };

    globalThis.cameraKmp_maxHeight = function (handle) {
        var value = entry(handle);
        if (!value) return 0;
        return rangeMax(value.capabilities.height) || actualHeight(value);
    };

    globalThis.cameraKmp_maxFrameRate = function (handle) {
        var value = entry(handle);
        if (!value) return 0;
        return Math.round(rangeMax(value.capabilities.frameRate)) || globalThis.cameraKmp_frameRate(handle);
    };

    globalThis.cameraKmp_frame = function (handle, destination) {
        var value = entry(handle);
        if (!value || value.status !== 1 || !value.video) return 0;
        var video = value.video;
        var width = video.videoWidth | 0;
        var height = video.videoHeight | 0;
        if (width <= 0 || height <= 0) return 0;

        var canvas = value.canvas;
        if (!canvas) {
            canvas = globalThis.document.createElement('canvas');
            value.canvas = canvas;
            value.context = canvas.getContext('2d', { willReadFrequently: true });
        }
        if (canvas.width !== width) canvas.width = width;
        if (canvas.height !== height) canvas.height = height;
        var context = value.context;
        if (!context) {
            value.context = context = canvas.getContext('2d', { willReadFrequently: true });
        }
        if (!context) return 0;

        context.drawImage(video, 0, 0, width, height);
        var image = context.getImageData(0, 0, width, height);
        var bytes = image.data;
        if (destination.length < bytes.length) return 0;
        destination.set(bytes);
        return bytes.length;
    };

    globalThis.cameraKmp_close = function (handle) {
        var value = entry(handle);
        if (!value) return;
        streams.delete(handle);
        if (value.stream) {
            value.stream.getTracks().forEach(function (track) { track.stop(); });
            value.stream = null;
        }
        if (value.video) {
            try {
                value.video.pause();
            } catch (error) {
                // ignore
            }
            value.video.srcObject = null;
            if (value.video.parentNode) value.video.parentNode.removeChild(value.video);
            value.video = null;
        }
        value.canvas = null;
        value.context = null;
    };
})();
