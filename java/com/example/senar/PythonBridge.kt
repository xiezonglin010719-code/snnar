package com.example.senar

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

object PythonBridge {

    private const val TAG = "PythonBridge"
    private var py: Python? = null
    /* -------------------- init -------------------- */
    fun init(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
            Log.d(TAG, "Python started via Chaquopy")
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        py = Python.getInstance()
    }

    /** 内部安全获取 Python 实例 */
    private fun getPython(): Python {
        return py ?: throw IllegalStateException("Python not initialized. Call PythonBridge.init() first.")
    }




    /* -------------------- 小工具：PyObject → 基本数组 -------------------- */

    private fun PyObject.toShortArraySafe(): ShortArray {
        try {
            @Suppress("UNCHECKED_CAST")
            return this.toJava(ShortArray::class.java) as ShortArray
        } catch (_: Throwable) {
        }
        val list = try {
            this.callAttr("tolist").asList()
        } catch (_: Throwable) {
            this.asList()
        }
        val out = ShortArray(list.size)
        for (i in list.indices) {
            out[i] = list[i].toInt().toShort()
        }
        return out
    }

    private fun PyObject.toFloatArraySafe(): FloatArray {
        try {
            @Suppress("UNCHECKED_CAST")
            return this.toJava(FloatArray::class.java) as FloatArray
        } catch (_: Throwable) {
        }
        val list = try {
            this.callAttr("tolist").asList()
        } catch (_: Throwable) {
            this.asList()
        }
        val out = FloatArray(list.size)
        for (i in list.indices) {
            out[i] = list[i].toDouble().toFloat()
        }
        return out
    }

    private fun PyObject.toIntArraySafe(): IntArray {
        try {
            @Suppress("UNCHECKED_CAST")
            return this.toJava(IntArray::class.java) as IntArray
        } catch (_: Throwable) {
        }
        val list = try {
            this.callAttr("tolist").asList()
        } catch (_: Throwable) {
            this.asList()
        }
        val out = IntArray(list.size)
        for (i in list.indices) {
            out[i] = list[i].toInt()
        }
        return out
    }

//    fun updateMotionState(
//        streamId: String,
//        pitchDeg: Double,
//        rollDeg: Double,
//        accMag: Double,
//        tsSec: Double
//    ) {
//        try {
//            val py = Python.getInstance()
//            val m = py.getModule("signal_processor")
//            m.callAttr(
//                "update_motion_state",
//                streamId,
//                pitchDeg,
//                rollDeg,
//                accMag,
//                tsSec
//            )
//        } catch (e: Exception) {
//            Log.e(TAG, "updateMotionState 调用 Python 失败: ${e.message}", e)
//        }
//    }


    /* -------------------- 通用：PyObject → Kotlin -------------------- */

    /**
     * 尝试把 PyObject 当成 dict 解析。
     * - 成功：Map<String, Any?>
     * - 失败：打印日志 + 包一层 "_error"/"_repr"
     */
    private fun pyDictToMap(obj: PyObject): Map<String, Any?> {
        // 先尝试用 asMap 解析真正的 Python dict
        val mapLike: Map<*, *>? = try {
            @Suppress("UNCHECKED_CAST")
            obj.asMap() as Map<*, *>
        } catch (_: Throwable) {
            null
        }

        // 如果 asMap 不行，再尝试把它当成 Java Map
        val realMap: Map<*, *>? = mapLike ?: try {
            @Suppress("UNCHECKED_CAST")
            obj.toJava(Map::class.java) as Map<*, *>
        } catch (_: Throwable) {
            null
        }

        if (realMap == null) {
            Log.e(TAG, "pyDictToMap: object is not a dict/map, class=${obj.javaClass.name}, repr=$obj")
            return mapOf(
                "_error" to "not a dict",
                "_repr" to obj.toString()
            )
        }

        // 把 Map<*, *> 递归转成 Kotlin 类型
        val out = LinkedHashMap<String, Any?>(realMap.size)
        for ((kRaw, vRaw) in realMap) {
            val key = kRaw?.toString() ?: continue
            val vPy = vRaw as? PyObject
            out[key] = if (vPy != null) pyToKotlin(vPy) else vRaw
        }
        return out
    }


    private fun pyToKotlin(vObj: PyObject?): Any? {
        if (vObj == null) return null

        // 1) 尝试基本类型：Number / String / Boolean
        try {
            val j = vObj.toJava(Any::class.java)
            if (j is Number || j is String || j is Boolean) return j
        } catch (_: Throwable) {
        }

        // 2) 优先尝试 dict / Map
        try {
            @Suppress("UNCHECKED_CAST")
            val m = vObj.asMap() as Map<*, *>?
            if (m != null) {
                val out = LinkedHashMap<String, Any?>(m.size)
                for ((kRaw, vRaw) in m) {
                    val key = kRaw?.toString() ?: continue
                    val vPy = vRaw as? PyObject
                    out[key] = if (vPy != null) pyToKotlin(vPy) else vRaw
                }
                return out
            }
        } catch (_: Throwable) {
            // ignore, fall through
        }

        // 再试一次 Java Map（有些对象可能已经被转成 Java 的 Map）
        try {
            @Suppress("UNCHECKED_CAST")
            val m2 = vObj.toJava(Map::class.java) as Map<*, *>?
            if (m2 != null) {
                val out = LinkedHashMap<String, Any?>(m2.size)
                for ((kRaw, vRaw) in m2) {
                    val key = kRaw?.toString() ?: continue
                    out[key] = vRaw
                }
                return out
            }
        } catch (_: Throwable) {
            // ignore, fall through
        }

        // 3) 尝试 numpy/list → Kotlin 数组 / List
        val list = try {
            vObj.callAttr("tolist").asList()
        } catch (_: Throwable) {
            try {
                vObj.asList()
            } catch (_: Throwable) {
                null
            }
        }

        if (list != null) {
            val first = try {
                list.firstOrNull()
            } catch (_: Throwable) {
                null
            }

            if (first is PyObject) {
                // float[]
                try {
                    first.toDouble()
                    val fa = FloatArray(list.size)
                    for (i in list.indices) {
                        fa[i] = (list[i] as PyObject).toDouble().toFloat()
                    }
                    return fa
                } catch (_: Throwable) {
                }

                // int[]
                try {
                    first.toInt()
                    val ia = IntArray(list.size)
                    for (i in list.indices) {
                        ia[i] = (list[i] as PyObject).toInt()
                    }
                    return ia
                } catch (_: Throwable) {
                }
            }

            // 通用 List<Any?>
            return list.map { one ->
                val p = one as? PyObject
                if (p == null) one
                else {
                    try {
                        p.toJava(Any::class.java)
                    } catch (_: Throwable) {
                        p.toString()
                    }
                }
            }
        }

        // 4) 最后兜底：直接转字符串
        return vObj.toString()
    }


    private fun pyToAny(o: PyObject?): Any? {
        if (o == null) return null

        // Chaquopy 没有 isNone，用 repr / toString 判断
        if (o.toString() == "None") return null

        // ---------- 基本类型 ----------
        try { return o.toJava(Boolean::class.java) } catch (_: Throwable) {}
        try { return o.toJava(Int::class.java) } catch (_: Throwable) {}
        try { return o.toJava(Long::class.java) } catch (_: Throwable) {}
        try { return o.toJava(Double::class.java) } catch (_: Throwable) {}
        try { return o.toJava(String::class.java) } catch (_: Throwable) {}

        // ---------- dict ----------
        try {
            val m = o.asMap()
            val out = linkedMapOf<String, Any?>()
            for ((k, v) in m) {
                val key = when (k) {
                    is PyObject -> try { k.toJava(String::class.java) } catch (_: Throwable) { k.toString() }
                    else -> k.toString()
                }
                out[key] = pyToAny(v as? PyObject)
            }
            return out
        } catch (_: Throwable) {}

        // ---------- list / tuple ----------
        try {
            val lst = o.asList()
            return lst.map { pyToAny(it as? PyObject) }
        } catch (_: Throwable) {}

        // ---------- numpy / 复杂对象兜底 ----------
        return o.toString()
    }


    private fun getModuleSafe(name: String): PyObject {
        return getPython().getModule(name)
    }

    /* -------------------- 声纳发射：扫频 -------------------- */

    // 单个 chirp
    fun generateChirp(
        fStart: Double = 18_000.0,
        fEnd: Double = 20_000.0,
        duration: Double = 0.01075,   // 10.75 ms
        sampleRate: Int = 48_000
    ): ShortArray {
        val m = getModuleSafe("signal_generator")
        val arr = m.callAttr("generate_swept_sinusoid", fStart, fEnd, duration, sampleRate)
        return arr.toShortArraySafe()
    }

    // 重复拼接 chirp（如一次性生成长 buffer）
    fun tileChirp(chirp: ShortArray, repeats: Int): ShortArray {
        val out = ShortArray(chirp.size * repeats)
        for (r in 0 until repeats) {
            System.arraycopy(chirp, 0, out, r * chirp.size, chirp.size)
        }
        return out
    }

    fun generateSweptSinusoid(
        fStart: Double = 18_000.0,
        fEnd: Double = 22_000.0,
        duration: Double = 0.01075,
        sampleRate: Int = 48_000
    ): ShortArray {
        val py = Python.getInstance()
        val m = py.getModule("signal_generator")
        val arr = m.callAttr("generate_swept_sinusoid", fStart, fEnd, duration, sampleRate)
        return arr.toShortArraySafe()
    }

    fun generateContinuousSignal(single: ShortArray): ShortArray {
        val py = Python.getInstance()
        val m = py.getModule("signal_generator")
        val arr = m.callAttr("generate_continuous_signal", single)
        return arr.toShortArraySafe()
    }

    /* -------------------- FMCW 单帧（如有需要） -------------------- */

    fun fmcwProcessFrame(
        rxPcm: ShortArray,
        txChirp: ShortArray,
        chirpsPerFrame: Int,
        sampleRate: Int = 48_000
    ): Map<String, Any?> {
        return try {
            val m = getModuleSafe("signal_processor")
            val obj: PyObject = m.callAttr(
                "process_fmcw_frame",
                rxPcm, txChirp, chirpsPerFrame, sampleRate
            )
            val map = pyDictToMap(obj)
            if (map.isEmpty()) {
                Log.e(TAG, "fmcwProcessFrame: result not a dict, skip")
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "fmcwProcessFrame failed: ${e.message}", e)
            emptyMap()
        }
    }

    /* -------------------- FMCW 流式处理（新版：返回 (features, events)） -------------------- */

    fun fmcwProcessFrameStream(
        streamId: String,
        rxPcm: ShortArray,
        txChirp: ShortArray,
        chirpsPerFrame: Int,
        sampleRate: Int = 48_000,
        saveDir: String? = null
    ): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
        return try {
            val m = Python.getInstance().getModule("signal_processor")
            val obj: PyObject = m.callAttr(
                "process_fmcw_frame_stream",
                streamId,
                rxPcm,
                txChirp,
                chirpsPerFrame,
                sampleRate,
                saveDir
            )

            // Python 返回的是 (features_dict, events_list)
            val tupleList: List<PyObject> = try {
                obj.asList().map { it as PyObject }
            } catch (e: Exception) {
                Log.e(TAG, "fmcwProcessFrameStream: ret is not list/tuple, repr=$obj", e)
                val featuresOnly = pyDictToMap(obj)
                return Pair(featuresOnly, emptyList())
            }

            if (tupleList.isEmpty()) {
                Log.e(TAG, "fmcwProcessFrameStream: empty tuple, repr=$obj")
                return Pair(emptyMap(), emptyList())
            }

            // 第 0 个：features dict
            val featuresPy = tupleList[0]
            val features = pyDictToMap(featuresPy)

            // 第 1 个：events list（每个元素是 dict）
            val events = mutableListOf<Map<String, Any?>>()
            if (tupleList.size >= 2) {
                val eventsPy = tupleList[1]
                try {
                    for (ev in eventsPy.asList()) {
                        val evPy = ev as? PyObject ?: continue
                        events.add(pyDictToMap(evPy))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fmcwProcessFrameStream: parse events failed, repr=$eventsPy", e)
                }
            }

            Log.d(
                TAG,
                "fmcwProcessFrameStream: features keys=${features.keys}, events=${events.size}"
            )
            Pair(features, events)
        } catch (e: Exception) {
            Log.e(TAG, "fmcwProcessFrameStream exception: ${e.message}", e)
            val errMap = mapOf(
                "_error" to (e.message ?: "unknown"),
                "_repr" to "fmcwProcessFrameStream exception (bridge)",
                "decision_period_sec" to 30.0,
                "recent_event_count" to 0,
                "diagnosis" to "Bridge异常"
            )
            Pair(errMap, emptyList())
        }
    }


    fun parseRtNpzForReport(npzPath: String): Map<String, Any?> {
        val python = getPython()
        val module = python.getModule("signal_processor")

        val ret = module.callAttr("parse_rt_npz_for_report", npzPath)

        val any = pyToAny(ret)
        if (any !is Map<*, *>) {
            Log.e(TAG, "parseRtNpzForReport: return not dict, repr=$ret")
            return mapOf(
                "_error" to "parse_rt_npz_for_report did not return dict",
                "_repr" to ret.toString(),
                "npz_path" to npzPath
            )
        }

        @Suppress("UNCHECKED_CAST")
        return any as Map<String, Any?>
    }



    /* -------------------- 声纳特征提取（备用） -------------------- */

    fun processAudioData(
        pcmData: ShortArray,
        sampleRate: Int = 48_000
    ): Map<String, Any?> {
        return try {
            val m = getModuleSafe("signal_processor")
            val obj: PyObject = try {
                m.callAttr("process_sonar_data", pcmData, sampleRate, true)
            } catch (_: Throwable) {
                try {
                    m.callAttr("extract_breathing_features", pcmData, sampleRate, true)
                } catch (_: Throwable) {
                    m.callAttr("process_audio_data", pcmData, sampleRate)
                }
            }

            val map = pyDictToMap(obj).toMutableMap()
            if (!map.containsKey("sample_rate")) map["sample_rate"] = sampleRate
            map
        } catch (e: Exception) {
            Log.e(TAG, "processAudioData failed: ${e.message}", e)
            emptyMap()
        }
    }

    /* -------------------- 事件检测（备用） -------------------- */

    fun detectApneaEvents(features: Map<String, Any?>): List<Map<String, Any?>> {
        return try {
            val m = getModuleSafe("signal_processor")
            val obj: PyObject = try {
                m.callAttr("detect_apnea_events", features)
            } catch (_: Throwable) {
                m.callAttr("detect_events", features)
            }

            val list = obj.asList()
            val out = ArrayList<Map<String, Any?>>(list.size)
            for (item in list) {
                out.add(pyDictToMap(item))
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "detectApneaEvents python call failed: ${e.message}")
            emptyList()
        }
    }

    /* -------------------- 实时保存用的谱图（备用） -------------------- */

    fun computeSonarSpec(pcm: ShortArray, sampleRate: Int = 48_000): Array<FloatArray> {
        val py = Python.getInstance()
        val m = py.getModule("signal_processor")
        return try {
            val pySpec = m.callAttr("compute_sonar_spectrogram", pcm, sampleRate, 64, 64)
            val outer = pySpec.asList()
            val ch0 = outer[0].asList()
            val out = Array(1) { FloatArray(64 * 64) }
            var idx = 0
            ch0.forEach { row ->
                val cols = row.asList()
                cols.forEach { v ->
                    out[0][idx++] = v.toDouble().toFloat()
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "computeSonarSpec failed: ${e.message}")
            Array(1) { FloatArray(64 * 64) }
        }
    }
}
