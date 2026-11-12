package cn.enaium.camera

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin Foreign Function & Memory layer over the Objective-C runtime and the
 * Apple frameworks.
 *
 * Every call builds a downcall handle for its exact signature once and caches
 * it. Objective-C messages are dispatched through `objc_msgSend` with the
 * signature of the message, which is how a compiler emits them too; BOOL is a
 * signed byte on Apple platforms, NSInteger/size_t are 64-bit longs.
 */
internal object JvmNative {
    val linker: Linker = Linker.nativeLinker()
    private val defaultLookup: SymbolLookup = linker.defaultLookup()
    private val globalArena: Arena = Arena.global()

    private val lookups: List<SymbolLookup> = buildList {
        add(defaultLookup)
        runCatching {
            add(SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", globalArena))
        }
        runCatching {
            add(SymbolLookup.libraryLookup("/usr/lib/system/libdispatch.dylib", globalArena))
        }
        runCatching {
            add(
                SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/AVFoundation.framework/AVFoundation",
                    globalArena,
                ),
            )
        }
        runCatching {
            add(
                SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreMedia.framework/CoreMedia",
                    globalArena,
                ),
            )
        }
        runCatching {
            add(
                SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreVideo.framework/CoreVideo",
                    globalArena,
                ),
            )
        }
        runCatching {
            add(
                SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/Foundation.framework/Foundation",
                    globalArena,
                ),
            )
        }
    }

    private val handles = ConcurrentHashMap<String, MethodHandle>()
    private val selectors = ConcurrentHashMap<String, MemorySegment>()
    private val classes = ConcurrentHashMap<String, MemorySegment>()

    fun symbol(name: String): MemorySegment = lookups.asSequence()
        .mapNotNull { it.find(name).orElse(null) }
        .firstOrNull()
        ?: throw CameraException("missing native symbol $name")

    fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
        handles.computeIfAbsent("$name|$descriptor") {
            linker.downcallHandle(symbol(name), descriptor)
        }

    fun raw(name: String, returnLayout: MemoryLayout, vararg argLayouts: MemoryLayout, args: List<Any?>): Any? {
        val descriptor = FunctionDescriptor.of(returnLayout, *argLayouts)
        return handle(name, descriptor).invokeWithArguments(args)
    }

    /** Downcall whose return value is a struct: FFM expects the allocator as the first argument. */
    fun rawStruct(
        name: String,
        returnLayout: MemoryLayout,
        vararg argLayouts: MemoryLayout,
        arena: Arena,
        args: List<Any?>,
    ): MemorySegment {
        val descriptor = FunctionDescriptor.of(returnLayout, *argLayouts)
        return handle(name, descriptor).invokeWithArguments(listOf(arena) + args) as MemorySegment
    }

    fun void(name: String, vararg argLayouts: MemoryLayout, args: List<Any?>) {
        val descriptor = FunctionDescriptor.ofVoid(*argLayouts)
        handle(name, descriptor).invokeWithArguments(args)
    }

    fun selector(name: String): MemorySegment = selectors.computeIfAbsent(name) {
        raw(
            "sel_registerName",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(globalArena.allocateFrom(name)),
        ) as MemorySegment
    }

    fun objcClass(name: String): MemorySegment = classes.computeIfAbsent(name) {
        raw(
            "objc_getClass",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(globalArena.allocateFrom(name)),
        ) as MemorySegment
    }

    /** `[receiver selector]`. */
    fun send(receiver: MemorySegment, selector: String): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector)),
    ) as MemorySegment

    /** `[receiver selector:arg]` where arg is an object pointer. */
    fun send(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector), arg),
    ) as MemorySegment

    /** `[receiver selector:arg arg2]` where both arguments are object pointers. */
    fun send(
        receiver: MemorySegment,
        selector: String,
        arg1: MemorySegment,
        arg2: MemorySegment,
    ): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector), arg1, arg2),
    ) as MemorySegment

    fun sendVoid(receiver: MemorySegment, selector: String) {
        void(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(receiver, selector(selector)),
        )
    }

    fun sendVoid(receiver: MemorySegment, selector: String, arg: MemorySegment) {
        void(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(receiver, selector(selector), arg),
        )
    }

    fun sendVoid(receiver: MemorySegment, selector: String, arg1: MemorySegment, arg2: MemorySegment) {
        void(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(receiver, selector(selector), arg1, arg2),
        )
    }

    fun sendVoid(receiver: MemorySegment, selector: String, value: Boolean) {
        void(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_BYTE,
            args = listOf(receiver, selector(selector), if (value) 1.toByte() else 0.toByte()),
        )
    }

    fun sendVoid(receiver: MemorySegment, selector: String, value: MemorySegment, nsInteger: Long) {
        void(
            "objc_msgSend",
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            args = listOf(receiver, selector(selector), value, nsInteger),
        )
    }

    fun sendBool(receiver: MemorySegment, selector: String): Boolean {
        val result = raw(
            "objc_msgSend",
            ValueLayout.JAVA_BYTE,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(receiver, selector(selector)),
        ) as Byte
        return result.toInt() != 0
    }

    fun sendBool(receiver: MemorySegment, selector: String, arg: MemorySegment): Boolean {
        val result = raw(
            "objc_msgSend",
            ValueLayout.JAVA_BYTE,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            args = listOf(receiver, selector(selector), arg),
        ) as Byte
        return result.toInt() != 0
    }

    fun sendInt(receiver: MemorySegment, selector: String): Int = raw(
        "objc_msgSend",
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector)),
    ) as Int

    fun sendLong(receiver: MemorySegment, selector: String): Long = raw(
        "objc_msgSend",
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector)),
    ) as Long

    fun sendDouble(receiver: MemorySegment, selector: String): Double = raw(
        "objc_msgSend",
        ValueLayout.JAVA_DOUBLE,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        args = listOf(receiver, selector(selector)),
    ) as Double

    /** `[receiver selector:nsInteger]`. */
    fun sendObjectLong(receiver: MemorySegment, selector: String, value: Long): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        args = listOf(receiver, selector(selector), value),
    ) as MemorySegment

    fun sendObjectInt(receiver: MemorySegment, selector: String, value: Int): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        args = listOf(receiver, selector(selector), value),
    ) as MemorySegment

    fun sendObjectStruct(
        receiver: MemorySegment,
        selector: String,
        layout: MemoryLayout,
        value: MemorySegment,
    ): MemorySegment = raw(
        "objc_msgSend",
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        layout,
        args = listOf(receiver, selector(selector), value),
    ) as MemorySegment

    fun string(arena: Arena, value: String): MemorySegment = send(
        objcClass("NSString"),
        "stringWithUTF8String:",
        arena.allocateFrom(value),
    )

    fun stringValue(segment: MemorySegment): String {
        if (segment.address() == 0L) return ""
        val utf8 = send(segment, "UTF8String")
        if (utf8.address() == 0L) return ""
        return utf8.reinterpret(Long.MAX_VALUE).getString(0)
    }

    fun arrayCount(array: MemorySegment): Int = sendLong(array, "count").toInt()

    fun arrayGet(array: MemorySegment, index: Int): MemorySegment =
        sendObjectLong(array, "objectAtIndex:", index.toLong())

    inline fun <T> autoreleasePool(block: () -> T): T {
        val pool = raw(
            "objc_autoreleasePoolPush",
            ValueLayout.ADDRESS,
            args = emptyList(),
        ) as MemorySegment
        try {
            return block()
        } finally {
            void(
                "objc_autoreleasePoolPop",
                ValueLayout.ADDRESS,
                args = listOf(pool),
            )
        }
    }

    fun release(segment: MemorySegment) {
        if (segment.address() == 0L) return
        sendVoid(segment, "release")
    }

    fun errorMessage(error: MemorySegment): String {
        if (error.address() == 0L) return "no NSError"
        return stringValue(send(error, "localizedDescription"))
    }
}
