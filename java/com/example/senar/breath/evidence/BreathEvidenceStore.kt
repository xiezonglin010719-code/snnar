package com.example.senar.breath.evidence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class EvidenceUiState(
    val selectedId: String? = null,
    val items: Map<String, EvidenceClip> = emptyMap()
)

object BreathEvidenceStore {
    private val _state = MutableStateFlow(EvidenceUiState())
    val state: StateFlow<EvidenceUiState> = _state

    fun put(clip: EvidenceClip) {
        _state.update { s -> s.copy(items = s.items + (clip.id to clip)) }
    }

    fun update(id: String, f: (EvidenceClip) -> EvidenceClip) {
        _state.update { s ->
            val old = s.items[id] ?: return@update s
            s.copy(items = s.items + (id to f(old)))
        }
    }

    fun select(id: String?) {
        _state.update { it.copy(selectedId = id) }
    }

    fun get(id: String): EvidenceClip? = _state.value.items[id]
}
