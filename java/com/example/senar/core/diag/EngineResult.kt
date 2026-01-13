package com.example.senar.core.diag

import com.example.senar.core.model.DiagnosisFrame
import com.example.senar.core.model.SleepEvent

data class EngineResult(
    val frame: DiagnosisFrame,
    val events: List<SleepEvent>,
    val rawFeatures: Map<String, Any?>? = null,
    val rawEvents: List<Map<String, Any?>>? = null
)
