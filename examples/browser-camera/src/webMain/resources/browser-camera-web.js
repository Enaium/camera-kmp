/*
 * Canvas presentation glue for the browser codec example.
 *
 * Kotlin/JS and Kotlin/Wasm both hand over an RGBA Uint8Array; the actual
 * ImageData construction and putImageData call stay in JavaScript, next to the
 * DOM object they belong to.
 */
(function () {
    if (globalThis.cameraKmpDrawRGBA) return;
    globalThis.cameraKmpDrawRGBA = function (canvas, data, width, height) {
        var context = canvas.getContext('2d');
        if (!context) return;
        var image = context.createImageData(width, height);
        image.data.set(data);
        context.putImageData(image, 0, 0);
    };
})();
