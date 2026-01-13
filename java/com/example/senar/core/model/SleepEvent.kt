package com.example.senar.core.model

data class SleepEvent(
    val type: String,          // "OA" / "HYP" / "CEN" ... (按你 Python 返回的 type)
    val startSec: Float,
    val endSec: Float,
    val confidence: Float? = null
)
