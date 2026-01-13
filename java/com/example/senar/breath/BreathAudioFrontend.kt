package com.example.senar.breath

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.min

class BreathAudioFrontend(private val ctx: Context) {
    private var recorder: AudioRecord? = null

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun start(sampleRate: Int = BreathRealtimeManager.SAMPLE_RATE) {
        if (recorder != null) return
        if (!hasRecordPermission()) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED
        else
            MediaRecorder.AudioSource.VOICE_RECOGNITION

        recorder = try {
            AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: SecurityException) {
            recorder = null
            throw e
        }

        try {
            recorder!!.startRecording()
        } catch (e: Throwable) {
            stop()
            throw e
        }
    }

    fun stop() {
        recorder?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        recorder = null
    }

    /**
     * 读 seconds 秒 PCM16，返回 FloatArray [-1,1]
     */
    fun readPcmSeconds(seconds: Int, sampleRate: Int): FloatArray {
        val r = recorder ?: error("AudioRecord not started")
        val total = seconds * sampleRate
        val out = FloatArray(total)

        val buf = ShortArray(4096)
        var off = 0
        while (off < total) {
            val need = min(buf.size, total - off)
            val n = r.read(buf, 0, need)
            if (n <= 0) continue
            for (i in 0 until n) {
                out[off + i] = buf[i] / 32768f
            }
            off += n
        }
        return out
    }
}
