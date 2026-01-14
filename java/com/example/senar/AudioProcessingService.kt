package com.example.senar

import android.Manifest
import android.app.*
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.senar.core.diag.EngineResult
import com.example.senar.core.mapper.FrameMapper
import com.example.senar.core.mapper.toEntity
import com.example.senar.core.model.DiagMode
import com.example.senar.core.repo.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlin.math.atan2
import kotlin.math.sqrt

class AudioProcessingService : Service() {

    interface UpdateListener {
        fun onUpdate(result: EngineResult)
    }

    companion object {
        private const val TAG = "AudioService"
        private const val TAG_DIAG_AUDIO = "DIAG_AUDIO"
        private const val TAG_DIAG_PY = "DIAG_PY"
        private const val TAG_DIAG_FRAME = "DIAG_FRAME"

        private const val NOTIF_CHANNEL_ID = "sonar_osa_channel"
        private const val NOTIF_ID = 1001

        private const val SAMPLE_RATE = 48_000
        private const val CHIRPS_PER_FRAME = 32
        private const val FRAME_SAMPLES = 16_384

        /** ✅ 多人：启动/切换都用这个 extra */
        const val EXTRA_PERSON_ID = "extra_person_id"

        /** ✅ 运行中切换目标人：startService/foregroundService 发送此 action */
        const val ACTION_SET_PERSON = "com.example.senar.ACTION_SET_PERSON"

        private var updateListener: UpdateListener? = null
        fun setListener(l: UpdateListener?) { updateListener = l }
    }

    // ------------------ 成员变量 ------------------

    private var audioRecord: AudioRecord? = null

    private var processingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false

    // FMCW
    private lateinit var txChirp: ShortArray

    /** ✅ 本次“开诊断”的会话时间戳：固定不变，用于让同一轮 session 的不同人也可区分 */
    private val sessionEpochSec: Long = System.currentTimeMillis() / 1000L

    /** ✅ 当前目标人 */
    @Volatile private var currentPersonId: String = MultiPersonDiagnosis.DEFAULT_PERSON_ID

    /** ✅ 当前 Python 状态 key（多人隔离的核心） */
    @Volatile private var currentStreamId: String = "init"

    private val visibleDirName = "sonar_spec_visible"

    // IMU
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var linAccSensor: Sensor? = null
    private var accelFallbackSensor: Sensor? = null

    private var lastGravity: FloatArray? = null
    private var lastLinAcc: FloatArray? = null
    private var hasGravity: Boolean = false
    private var hasLinAcc: Boolean = false

    private var lastMotionUpdateMs: Long = 0L
    private val motionUpdateIntervalMs: Long = 80L

    private var lastResult: EngineResult? = null

    // ✅ Room session
    private var sessionId: Long = 0L
    private val sessionRepo by lazy { SessionRepository.from(this) }

    // ------------------ ✅ 帧队列：只保留最新帧 ------------------

    private data class FramePacket(
        val pcm: ShortArray,
        val tCaptureNs: Long,
        val seq: Long
    )

    private val frameChan = Channel<FramePacket>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var seqRead: Long = 0L
    private var droppedFrames: Long = 0L
    private var lastReadNs: Long = 0L
    private var lastFrameNs: Long = 0L

    // ------------------ ✅ 诊断：Audio read 节奏（纳秒） ------------------

    private var readCbCount: Long = 0L
    private var sumReadWallNs: Long = 0L
    private var sumReadTheoNs: Long = 0L

    private fun logReadCallbackDiag(r: Int, sampleRate: Int) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dtWallNs = if (lastReadNs == 0L) 0L else (nowNs - lastReadNs)
        lastReadNs = nowNs

        val theoNs = (r.toDouble() / sampleRate.toDouble() * 1e9).toLong()

        sumReadWallNs += dtWallNs
        sumReadTheoNs += theoNs
        readCbCount++

        if (readCbCount % 20L == 0L) {
            val ratio = if (sumReadWallNs > 0) sumReadTheoNs.toDouble() / sumReadWallNs.toDouble() else 0.0
            Log.d(
                TAG_DIAG_AUDIO,
                "read#=$readCbCount dt_wall=${"%.1f".format(dtWallNs / 1e6)}ms r=$r " +
                        "theo=${"%.1f".format(theoNs / 1e6)}ms sumTheo/sumWall=${"%.2f".format(ratio)} dropped=$droppedFrames"
            )
            sumReadWallNs = 0L
            sumReadTheoNs = 0L
        }
    }

    // ------------------ ✅ 诊断：Frame 节奏（纳秒） ------------------

    private var frameCount: Long = 0L
    private var sumFrameWallNs: Long = 0L
    private var sumFrameTheoNs: Long = 0L

    private fun logFrameDiag(sampleRate: Int) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dtWallNs = if (lastFrameNs == 0L) 0L else (nowNs - lastFrameNs)
        lastFrameNs = nowNs

        val theoNs = (FRAME_SAMPLES.toDouble() / sampleRate.toDouble() * 1e9).toLong()

        sumFrameWallNs += dtWallNs
        sumFrameTheoNs += theoNs
        frameCount++

        if (frameCount % 10L == 0L) {
            val ratio = if (sumFrameWallNs > 0) sumFrameTheoNs.toDouble() / sumFrameWallNs.toDouble() else 0.0
            Log.d(
                TAG_DIAG_FRAME,
                "frame#=$frameCount dt_wall=${"%.1f".format(dtWallNs / 1e6)}ms theo=${"%.1f".format(theoNs / 1e6)}ms " +
                        "sumTheo/sumWall=${"%.2f".format(ratio)} dropped=$droppedFrames"
            )
            sumFrameWallNs = 0L
            sumFrameTheoNs = 0L
        }
    }

    // ------------------ 传感器监听器 ------------------

    private val motionSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    if (lastGravity == null || lastGravity!!.size != 3) lastGravity = FloatArray(3)
                    System.arraycopy(event.values, 0, lastGravity!!, 0, 3)
                    hasGravity = true
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (lastLinAcc == null || lastLinAcc!!.size != 3) lastLinAcc = FloatArray(3)
                    System.arraycopy(event.values, 0, lastLinAcc!!, 0, 3)
                    hasLinAcc = true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (!hasLinAcc) {
                        if (lastLinAcc == null || lastLinAcc!!.size != 3) lastLinAcc = FloatArray(3)
                        System.arraycopy(event.values, 0, lastLinAcc!!, 0, 3)
                        hasLinAcc = true
                    }
                }
                else -> return
            }

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastMotionUpdateMs < motionUpdateIntervalMs) return

            val g = lastGravity
            val a = lastLinAcc
            if (!hasGravity || !hasLinAcc || g == null || a == null) return

            val gx = g[0].toDouble()
            val gy = g[1].toDouble()
            val gz = g[2].toDouble()

            val pitchRad = atan2(-gx, sqrt(gy * gy + gz * gz))
            val rollRad = atan2(gy, gz)

            val pitchDeg = Math.toDegrees(pitchRad)
            val rollDeg = Math.toDegrees(rollRad)

            val ax = a[0].toDouble()
            val ay = a[1].toDouble()
            val az = a[2].toDouble()
            val accMag = sqrt(ax * ax + ay * ay + az * az)

            val tsSec = event.timestamp / 1_000_000_000.0
            lastMotionUpdateMs = nowMs

            @Suppress("UNUSED_VARIABLE")
            val _unused = arrayOf(pitchDeg, rollDeg, accMag, tsSec)

            // 如果你之前有 updateMotionState 的调用，就放这里（我不擅自加回去，避免动你逻辑）
            // PythonBridge.updateMotionState(currentStreamId, pitchDeg, rollDeg, accMag, tsSec)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ------------------ 生命周期 ------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        PythonBridge.init(applicationContext)

        txChirp = PythonBridge.generateChirp(
            fStart = 18_000.0,
            fEnd = 22_000.0,
            duration = 0.01075,
            sampleRate = SAMPLE_RATE
        )
        txChirp = normalizePcm16(txChirp)

        initMotionSensors()
        createNotificationChannel()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val reqPerson = intent?.getStringExtra(EXTRA_PERSON_ID)

        // ✅ deviceId：你可以换成更稳定的（比如 ANDROID_ID），我先用 packageName 当 base，不引入新权限
        val deviceId = applicationContext.packageName

        if (!isRunning) {
            // 第一次启动
            currentPersonId = (reqPerson ?: MultiPersonDiagnosis.DEFAULT_PERSON_ID)
            currentStreamId = MultiPersonDiagnosis.streamIdForPerson(
                deviceId = deviceId,
                personId = currentPersonId,
                sessionEpochSec = sessionEpochSec
            )
            Log.d(TAG, "start service: person=$currentPersonId streamId=$currentStreamId")

            isRunning = true
            startForegroundInternal()
            startAudioRecordingAndProcessFrames()
        } else {
            // 运行中：切换人
            if (action == ACTION_SET_PERSON && !reqPerson.isNullOrBlank()) {
                switchPerson(deviceId = deviceId, newPersonId = reqPerson)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        isRunning = false

        processingJob?.cancel()
        processingJob = null

        stopRecordingSafely()

        // 你旧的落盘逻辑先保留
        runCatching { SleepSessionManager.endSessionAndPersist(this) }

        // ✅ 关键：先 endSession，再 cancel scope（否则 endSession 协程会被取消）
        val sid = sessionId
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(800L) {
                if (sid > 0L) runCatching { sessionRepo.endSession(sid) }
            }
        }

        serviceScope.cancel()

        runCatching { sensorManager?.unregisterListener(motionSensorListener) }
        sensorManager = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------ ✅ 多人：切换目标人 ------------------

    private fun switchPerson(deviceId: String, newPersonId: String) {
        if (newPersonId == currentPersonId) return

        val oldPerson = currentPersonId
        val oldStream = currentStreamId

        currentPersonId = newPersonId
        currentStreamId = MultiPersonDiagnosis.streamIdForPerson(
            deviceId = deviceId,
            personId = currentPersonId,
            sessionEpochSec = sessionEpochSec
        )

        Log.d(TAG, "switch person: $oldPerson -> $newPersonId, streamId: $oldStream -> $currentStreamId")

        // ✅ Room：结束旧 session，开启新 session（这样报告按人分开）
        val oldSid = sessionId
        serviceScope.launch {
            runCatching {
                if (oldSid > 0L) sessionRepo.endSession(oldSid)
            }
            runCatching {
                sessionId = sessionRepo.startSession(
                    streamId = currentStreamId,
                    personId = currentPersonId,   // ✅ 补上这个
                    diagType = "SONAR"
                )
                Log.d(TAG, "Room session switched: sessionId=$sessionId person=$currentPersonId streamId=$currentStreamId")
            }.onFailure {
                Log.e(TAG, "startSession after switch failed: ${it.message}", it)
                sessionId = 0L
            }
        }

        // 可选：如果你旧的 SleepSessionManager 也要按人分开，你可以在这里做一次 end/start
        // 我不默认加，避免破坏你原逻辑：
        // runCatching { SleepSessionManager.endSessionAndPersist(this) }
        // runCatching { SleepSessionManager.startNewSession() }
    }

    // ------------------ 工具 ------------------

    private fun normalizePcm16(src: ShortArray, targetPeak: Int = 28000): ShortArray {
        var maxAbs = 0
        for (v in src) {
            val a = kotlin.math.abs(v.toInt())
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs <= 0) return src

        val gain = targetPeak.toFloat() / maxAbs.toFloat()
        val out = ShortArray(src.size)
        for (i in src.indices) {
            val x = (src[i] * gain).toInt()
            out[i] = x.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        Log.d(TAG, "normalizePcm16: maxAbs=$maxAbs, gain=$gain")
        return out
    }

    private fun initMotionSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager 获取失败，无法使用 IMU 信息")
            return
        }
        val sm = sensorManager!!

        gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linAccSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelFallbackSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (gravitySensor == null) Log.w(TAG, "未找到 GRAVITY 传感器，姿态估计可能不稳定")
        if (linAccSensor == null && accelFallbackSensor == null) {
            Log.w(TAG, "既没有 LINEAR_ACCELERATION 也没有 ACCELEROMETER，无法估计运动强度")
        }

        gravitySensor?.let { sm.registerListener(motionSensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        linAccSensor?.let { sm.registerListener(motionSensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: run { accelFallbackSensor?.let { sm.registerListener(motionSensorListener, it, SensorManager.SENSOR_DELAY_GAME) } }
    }

    // ------------------ 前台通知 ------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "声纳 OSA 检测",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "用于保持声纳检测在前台运行" }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun startForegroundInternal() {
        val notifIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, notifIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("OSA 声纳检测中")
            .setContentText("请保持手机扬声器对准胸腔，保持安静呼吸")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    // ------------------ 录音 & 帧处理核心 ------------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioRecordingAndProcessFrames() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            MediaRecorder.AudioSource.UNPROCESSED
        else
            MediaRecorder.AudioSource.VOICE_RECOGNITION

        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .build()

        audioRecord = record

        // 旧逻辑先保留
        runCatching { SleepSessionManager.startNewSession() }

        // ✅ 新：开启 Room session（按当前 streamId/person 开）
        serviceScope.launch {
            runCatching {
                sessionId = sessionRepo.startSession(
                    streamId = currentStreamId,
                    personId = currentPersonId,   // ✅ 补上这个
                    diagType = "SONAR"
                )
                Log.d(TAG, "Room session started: sessionId=$sessionId streamId=$currentStreamId person=$currentPersonId")
            }.onFailure {
                Log.e(TAG, "startSession failed: ${it.message}", it)
                sessionId = 0L
            }
        }


        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording 失败: ${e.message}", e)
            stopSelf()
            return
        }

        val visibleDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            visibleDirName
        ).apply { if (!exists()) mkdirs() }

        val readBuffer = ShortArray(2048)
        val frameBuf = ShortArray(FRAME_SAMPLES)
        var frameFill = 0

        val readerJob = serviceScope.launch {
            var samplesInSec = 0L
            var beatNs = SystemClock.elapsedRealtimeNanos()

            while (isActive && isRunning) {
                val r = record.read(readBuffer, 0, readBuffer.size)
                if (r > 0) {
                    seqRead++
                    logReadCallbackDiag(r, SAMPLE_RATE)

                    samplesInSec += r.toLong()
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    if (nowNs - beatNs >= 1_000_000_000L) {
                        Log.d(TAG, "录音心跳：~$samplesInSec samples/s")
                        samplesInSec = 0
                        beatNs = nowNs
                    }

                    var srcOff = 0
                    while (srcOff < r) {
                        val can = minOf(r - srcOff, FRAME_SAMPLES - frameFill)
                        System.arraycopy(readBuffer, srcOff, frameBuf, frameFill, can)
                        frameFill += can
                        srcOff += can

                        if (frameFill >= FRAME_SAMPLES) {
                            frameFill = 0
                            logFrameDiag(SAMPLE_RATE)

                            val pkt = FramePacket(
                                pcm = frameBuf.copyOf(),
                                tCaptureNs = SystemClock.elapsedRealtimeNanos(),
                                seq = seqRead
                            )

                            if (!frameChan.isEmpty) droppedFrames++
                            frameChan.trySend(pkt)
                        }
                    }
                } else {
                    delay(2)
                }
            }
        }

        val workerJob = serviceScope.launch {
            for (pkt in frameChan) {
                if (!isRunning) break
                processOneFrame(pkt, visibleDir)
            }
        }

        processingJob = serviceScope.launch {
            try {
                joinAll(readerJob, workerJob)
            } finally {
                stopRecordingSafely()
            }
        }
    }

    private fun stopRecordingSafely() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    // ------------------ 单帧处理：Python + UI + Room ------------------

    private fun processOneFrame(pkt: FramePacket, visibleDir: File) {
        val privateDir = File(filesDir, "sonar_spec").apply { if (!exists()) mkdirs() }

        val t0Ns = SystemClock.elapsedRealtimeNanos()

        // ✅ 关键：每帧都用“当前 streamId”（这样运行中切换人立刻生效）
        val sidForPython = currentStreamId

        val (featuresMap, eventsList) = try {
            PythonBridge.fmcwProcessFrameStream(
                streamId = sidForPython,
                rxPcm = pkt.pcm,
                txChirp = txChirp,
                chirpsPerFrame = CHIRPS_PER_FRAME,
                sampleRate = SAMPLE_RATE,
                saveDir = privateDir.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "fmcwProcessFrameStream 调用失败: ${e.message}", e)
            Pair(
                mapOf(
                    "_error" to (e.message ?: "unknown"),
                    "_repr" to "fmcwProcessFrameStream exception (kotlin)",
                    "decision_period_sec" to 30.0,
                    "recent_event_count" to 0,
                    "diagnosis" to "Bridge异常"
                ),
                emptyList()
            )
        }

        val costMs = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e6
        Log.d(
            TAG_DIAG_PY,
            "seq=${pkt.seq} engine_call_cost=${"%.1f".format(costMs)}ms dropped=$droppedFrames person=$currentPersonId streamId=$sidForPython"
        )

        val features: MutableMap<String, Any?> = featuresMap.toMutableMap()
        val events: List<Map<String, Any?>> = eventsList

        // ✅ 额外加两列，方便你 UI/落库/报告区分（不影响你原逻辑）
        features["person_id"] = currentPersonId
        features["stream_id"] = sidForPython

        // 旧逻辑先保留
        runCatching { SleepSessionManager.recordFrame(features, events) }

        val frame = FrameMapper.mapToFrame(
            features = features,
            tsMs = System.currentTimeMillis(),
            mode = DiagMode.SONAR
        )
        val evs = FrameMapper.mapToEvents(events)

        val result = EngineResult(
            frame = frame,
            events = evs,
            rawFeatures = features,
            rawEvents = events
        )

        lastResult = result

        // ✅ Room 落库：frame + events
        val roomSid = sessionId
        if (roomSid > 0L) {
            serviceScope.launch {
                runCatching {
                    sessionRepo.insertFrame(
                        sessionId = roomSid,
                        tsEpochMs = System.currentTimeMillis(),
                        features = features,
                        rawDebugText = null
                    )
                    sessionRepo.insertEvents(
                        sessionId = roomSid,
                        events = result.events.map { it.toEntity(sessionId = roomSid) }
                    )
                }.onFailure {
                    Log.e(TAG, "Room insert failed: ${it.message}", it)
                }
            }
        }

        mainHandler.post {
            updateListener?.onUpdate(result)
        }
    }
}
