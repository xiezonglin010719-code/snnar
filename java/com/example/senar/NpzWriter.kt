package com.example.senar

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object NpzWriter {

    /**
     * 写一个 npz，里头至少有 images.npy
     */
    fun writeSingleArray(
        file: File,
        key: String,
        images: Array<FloatArray>,
        vararg extras: Pair<String, Any>
    ) {
        file.outputStream().use { fos ->
            ZipOutputStream(fos).use { zos ->
                // images
                zos.putNextEntry(ZipEntry("$key.npy"))
                val imgBytes = NpyEncoder.fromFloatCHW(images)
                zos.write(imgBytes)
                zos.closeEntry()

                // extras
                for ((ek, ev) in extras) {
                    zos.putNextEntry(ZipEntry("$ek.npy"))
                    val b = NpyEncoder.fromAny(ev)
                    zos.write(b)
                    zos.closeEntry()
                }
            }
        }
    }
}
