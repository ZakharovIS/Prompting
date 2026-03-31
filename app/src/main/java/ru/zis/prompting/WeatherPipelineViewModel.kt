package ru.zis.prompting

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.zis.prompting.mcp.PipelineRunState
import ru.zis.prompting.mcp.WeatherPipelineTracker
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class WeatherPipelineViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App

    var citiesInput by mutableStateOf("Moscow, Kazan, Saint Petersburg")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val state: StateFlow<PipelineRunState> = WeatherPipelineTracker.state

    fun runPipeline() {
        if (loading) return

        val cities = citiesInput
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (cities.isEmpty()) {
            error = "Введите минимум один город"
            return
        }

        loading = true
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            val result = app.mcpRegistry.callTool(
                name = "weather_pipeline",
                arguments = buildJsonObject {
                    put("cities", buildJsonArray {
                        cities.forEach { add(JsonPrimitive(it)) }
                    })
                }
            )

            if (result.isError) {
                error = result.content
            }
            loading = false
        }
    }
}
