package com.example.senar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 底部“状态条”：
 * - 上半部分：姿态边缘 posture_flag_plot == 1 → 蓝色块
 * - 下半部分：伪影 artifact_mask_plot == 1 → 红色块
 * - baseline_rebuild == true 时：整体铺一层淡黄色
 *
 * X 轴对应最近一段时间（跟 envelope_plot 对齐即可），不标刻度，只看颜色。
 */
class BreathStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var env: FloatArray = FloatArray(0)
    private var artifact: FloatArray = FloatArray(0)
    private var posture: FloatArray = FloatArray(0)
    private var baselineRebuild: Boolean = false

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF222222.toInt()
    }

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF444444.toInt()
    }

    private val paintPosture = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 姿态/翻身：蓝色
        color = 0xFF2196F3.toInt()
    }

    private val paintArtifact = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 伪影：红色
        color = 0xFFD32F2F.toInt()
    }

    private val paintBaseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // baseline reset 期间：淡黄色遮罩
        color = 0x66FFC107
    }

    fun setData(
        envArr: FloatArray,
        artifactArr: FloatArray,
        postureArr: FloatArray,
        baselineRebuild: Boolean
    ) {
        this.env = envArr
        this.artifact = artifactArr
        this.posture = postureArr
        this.baselineRebuild = baselineRebuild
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 背景
        canvas.drawRect(0f, 0f, w, h, paintBg)
        canvas.drawRect(0f, 0f, w, h, paintGrid)

        val n = max(env.size, max(artifact.size, posture.size))
        if (n <= 0) {
            // 没数据只画背景
            return
        }

        val stepX = w / n

        val halfH = h / 2f
        val postureTop = 0f
        val postureBottom = halfH
        val artifactTop = halfH
        val artifactBottom = h

        for (i in 0 until n) {
            val x0 = i * stepX
            val x1 = (i + 1) * stepX

            val idxArt = min(i, max(artifact.size - 1, 0))
            val idxPos = min(i, max(posture.size - 1, 0))

            val aVal = if (artifact.isNotEmpty()) artifact[idxArt] else 0f
            val pVal = if (posture.isNotEmpty()) posture[idxPos] else 0f

            // 伪影：下半条红色
            if (aVal > 0.5f) {
                canvas.drawRect(x0, artifactTop, x1, artifactBottom, paintArtifact)
            }

            // 姿态/翻身：上半条蓝色
            if (pVal > 0.5f) {
                canvas.drawRect(x0, postureTop, x1, postureBottom, paintPosture)
            }
        }

        // baseline reset：全局黄色罩一层
        if (baselineRebuild) {
            canvas.drawRect(0f, 0f, w, h, paintBaseline)
        }
    }
}
