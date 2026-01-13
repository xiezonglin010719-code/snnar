package com.example.senar

import java.nio.ByteBuffer
import java.nio.ByteOrder

object NpyEncoder {

    fun fromFloatCHW(arr: Array<FloatArray>): ByteArray {
        val c = arr.size
        val hw = arr[0].size
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($c, $hw), }"
        val payloadSize = c * hw * 4
        return buildNpy(header, payloadSize) { bb ->
            for (i in 0 until c) {
                for (j in 0 until hw) {
                    bb.putFloat(arr[i][j])
                }
            }
        }
    }

    fun fromAny(value: Any): ByteArray {
        return when (value) {
            is IntArray -> {
                val n = value.size
                val header = "{'descr': '<i4', 'fortran_order': False, 'shape': ($n,), }"
                val payloadSize = n * 4
                buildNpy(header, payloadSize) { bb ->
                    value.forEach { bb.putInt(it) }
                }
            }
            is FloatArray -> {
                val n = value.size
                val header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($n,), }"
                val payloadSize = n * 4
                buildNpy(header, payloadSize) { bb ->
                    value.forEach { bb.putFloat(it) }
                }
            }
            is String -> {
                val data = value.toByteArray(Charsets.UTF_8)
                val n = data.size
                val header = "{'descr': '|u1', 'fortran_order': False, 'shape': ($n,), }"
                val payloadSize = n
                buildNpy(header, payloadSize) { bb ->
                    bb.put(data)
                }
            }
            else -> {
                throw IllegalArgumentException("type not supported: ${value::class.java}")
            }
        }
    }

    private fun buildNpy(
        headerRaw: String,
        payloadSize: Int,
        fill: (ByteBuffer) -> Unit
    ): ByteArray {
        val magic = byteArrayOf(
            0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
            'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()
        )
        val verMajor: Byte = 1
        val verMinor: Byte = 0

        var header = headerRaw
        if (!header.endsWith("\n")) header += "\n"
        var headerBytes = header.toByteArray(Charsets.US_ASCII)

        val baseLen = 6 + 2 + 2
        var padLen = 16 - ((baseLen + headerBytes.size) % 16)
        if (padLen == 16) padLen = 0
        if (padLen > 0) {
            headerBytes += ByteArray(padLen) { 0x20 }
        }
        headerBytes[headerBytes.size - 1] = 0x0A

        val totalSize = baseLen + headerBytes.size + payloadSize
        val out = ByteArray(totalSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        bb.put(magic)
        bb.put(verMajor)
        bb.put(verMinor)
        bb.putShort(headerBytes.size.toShort())
        bb.put(headerBytes)

        // payload
        val payloadBuf = bb.slice()
        fill(payloadBuf)

        return out
    }
}
