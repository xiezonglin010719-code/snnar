package com.example.senar.breath.offline

import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.min

object Mp4AudioExtractor {

    /**
     * 输出：PCM16 mono，targetSampleRate（默认 48k）
     * 注意：这是“能跑通”的最小版；不同 mp4 编码差异大，遇到异常你把错误发我我再适配。
     */
    fun extractMonoPcm16(ctx: Context, uri: Uri, targetSampleRate: Int = 48000): ShortArray {
        val extractor = MediaExtractor()
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { afd ->
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }

        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = f
                break
            }
        }
        require(audioTrack >= 0 && format != null) { "No audio track in mp4" }

        extractor.selectTrack(audioTrack)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Short>(48000 * 30) // 先给个容量
        val info = MediaCodec.BufferInfo()
        var eos = false

        while (true) {
            if (!eos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        val pts = extractor.sampleTime
                        codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)!!
                val chunk = ByteArray(info.size)
                outBuf.get(chunk)
                outBuf.clear()
                codec.releaseOutputBuffer(outIndex, false)

                // 解析为 PCM16（多数 decoder 输出 PCM16）
                // 如果是 float PCM，这里会乱：遇到这种再给我报错我补分支
                val shorts = bytesToShortLE(chunk)
                // 这里先假设已经 mono；若是 stereo，取 L
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (channels <= 1) {
                    for (s in shorts) out.add(s)
                } else {
                    var i = 0
                    while (i + (channels - 1) < shorts.size) {
                        out.add(shorts[i]) // 取第0通道
                        i += channels
                    }
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // ignore
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no-op
            }

            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }

        codec.stop()
        codec.release()
        extractor.release()

        // 如果采样率不是 48k：做一个最简线性重采样
        val srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val mono = out.toShortArray()
        return if (srcSr == targetSampleRate) mono else resampleLinear(mono, srcSr, targetSampleRate)
    }

    private fun bytesToShortLE(b: ByteArray): ShortArray {
        val n = b.size / 2
        val out = ShortArray(n)
        var i = 0
        var j = 0
        while (i + 1 < b.size) {
            val lo = b[i].toInt() and 0xFF
            val hi = b[i + 1].toInt()
            out[j] = ((hi shl 8) or lo).toShort()
            i += 2; j += 1
        }
        return out
    }

    private fun resampleLinear(x: ShortArray, srcSr: Int, dstSr: Int): ShortArray {
        val srcN = x.size
        val dstN = (srcN.toDouble() * dstSr / srcSr).toInt().coerceAtLeast(1)
        val out = ShortArray(dstN)
        for (i in 0 until dstN) {
            val t = i.toDouble() * srcSr / dstSr
            val a = t.toInt().coerceIn(0, srcN - 1)
            val b = min(a + 1, srcN - 1)
            val frac = t - a
            val v = (1.0 - frac) * x[a] + frac * x[b]
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
