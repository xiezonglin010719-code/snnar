package com.example.senar.breath.offline

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NpyReader {

    /**
     * 仅支持：
     * - little-endian float32: '<f4'
     * - C-order
     */
    fun readFloat32Npy(bytes: ByteArray): Pair<LongArray, FloatArray> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // magic \x93NUMPY
        val magic = ByteArray(6)
        bb.get(magic)
        val magicStr = magic.decodeToString()
        require(magicStr == "\u0093NUMPY") { "Bad npy magic: $magicStr" }

        val major = bb.get().toInt()
        val minor = bb.get().toInt()

        val headerLen = when (major) {
            1 -> bb.short.toInt() and 0xFFFF
            2, 3 -> bb.int
            else -> error("Unsupported npy version: $major.$minor")
        }

        val headerBytes = ByteArray(headerLen)
        bb.get(headerBytes)
        val header = headerBytes.decodeToString()

        require(header.contains("'descr': '<f4'") || header.contains("\"descr\": \"<f4\"")) {
            "Only <f4 supported, header=$header"
        }
        require(header.contains("False")) { "Fortran order not supported" }

        val shape = parseShape(header)
        val count = shape.fold(1L) { a, b -> a * b }

        val out = FloatArray(count.toInt())
        for (i in 0 until out.size) {
            out[i] = bb.float
        }
        return shape to out
    }

    private fun parseShape(header: String): LongArray {
        // shape: (a, b, c, ...)
        val i0 = header.indexOf("shape")
        val l = header.indexOf('(', i0)
        val r = header.indexOf(')', l + 1)
        require(l >= 0 && r > l) { "Cannot parse shape from header=$header" }
        val inside = header.substring(l + 1, r).trim()
        if (inside.isEmpty()) return longArrayOf()

        val parts = inside.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return parts.map { it.toLong() }.toLongArray()
    }
}
