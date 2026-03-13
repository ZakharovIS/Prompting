package ru.zis.prompting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.zis.prompting.mcp.PipelineStepState
import ru.zis.prompting.mcp.PipelineStepStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherPipelineScreen(
    onBack: () -> Unit,
    vm: WeatherPipelineViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weather MCP Pipeline") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Назад") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = vm.citiesInput,
                onValueChange = { vm.citiesInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Города (через запятую)") },
                placeholder = { Text("Moscow, Kazan, Saint Petersburg") }
            )

            Button(
                onClick = { vm.runPipeline() },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (vm.loading) "Выполняется..." else "Запустить пайплайн")
            }

            vm.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Run ID: ${if (state.runId == 0L) "—" else state.runId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.steps, key = { it.order }) { step ->
                    PipelineStepCard(step)
                }

                state.finalMessage?.let { finalMessage ->
                    items(listOf(finalMessage), key = { "final_message" }) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (state.isError) "Итог: ошибка" else "Итог: успех",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(finalMessage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineStepCard(step: PipelineStepState) {
    val prettyName = when (step.name) {
        "geocode_address" -> "🌍 geocode_address"
        "sunrise_sunset" -> "🌅 sunrise_sunset"
        "weather_multi_fetch" -> "🌤 weather_multi_fetch"
        "weather_save_report" -> "💾 weather_save_report"
        else -> step.name
    }

    val prettyStatus = when (step.status) {
        PipelineStepStatus.PENDING -> "⏳ PENDING"
        PipelineStepStatus.RUNNING -> "🔄 RUNNING"
        PipelineStepStatus.DONE -> "✅ DONE"
        PipelineStepStatus.ERROR -> "❌ ERROR"
    }

    val color = when (step.status) {
        PipelineStepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
        PipelineStepStatus.RUNNING -> MaterialTheme.colorScheme.tertiaryContainer
        PipelineStepStatus.DONE -> MaterialTheme.colorScheme.primaryContainer
        PipelineStepStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("${step.order}. $prettyName", style = MaterialTheme.typography.titleSmall)
            Text("Статус: $prettyStatus", style = MaterialTheme.typography.bodySmall)
            step.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
