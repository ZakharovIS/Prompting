package ru.zis.prompting.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PipelineStepStatus {
    PENDING,
    RUNNING,
    DONE,
    ERROR
}

data class PipelineStepState(
    val order: Int,
    val name: String,
    val status: PipelineStepStatus,
    val message: String? = null
)

data class PipelineRunState(
    val runId: Long = 0,
    val steps: List<PipelineStepState> = emptyList(),
    val finalMessage: String? = null,
    val isError: Boolean = false
)

object WeatherPipelineTracker {
    private val _state = MutableStateFlow(PipelineRunState())
    val state: StateFlow<PipelineRunState> = _state.asStateFlow()

    fun startRun(runId: Long, steps: List<PipelineStepState>) {
        _state.value = PipelineRunState(runId = runId, steps = steps)
    }

    fun updateStep(runId: Long, order: Int, status: PipelineStepStatus, message: String? = null) {
        val current = _state.value
        if (current.runId != runId) return

        _state.value = current.copy(
            steps = current.steps.map { step ->
                if (step.order == order) step.copy(status = status, message = message) else step
            }
        )
    }

    fun finishRun(runId: Long, finalMessage: String, isError: Boolean) {
        val current = _state.value
        if (current.runId != runId) return
        _state.value = current.copy(finalMessage = finalMessage, isError = isError)
    }
}
