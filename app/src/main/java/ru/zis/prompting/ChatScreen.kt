package ru.zis.prompting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenProfiles: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenWeatherHistory: () -> Unit,
    vm: ChatViewModel = viewModel()
) {
    val maxPromptChars = 1500
    var memoryDialog by remember { mutableStateOf<MemoryDialogType?>(null) }
    var topBarMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshActiveProfile()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                actions = {
                    IconButton(
                        onClick = { topBarMenuExpanded = true },
                        enabled = !vm.historyLoading
                    ) {
                        Text("⋮")
                    }

                    DropdownMenu(
                        expanded = topBarMenuExpanded,
                        onDismissRequest = { topBarMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Профили") },
                            onClick = {
                                topBarMenuExpanded = false
                                onOpenProfiles()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("MCP") },
                            onClick = {
                                topBarMenuExpanded = false
                                onOpenMcp()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("История погоды") },
                            onClick = {
                                topBarMenuExpanded = false
                                onOpenWeatherHistory()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Память: краткосрочная") },
                            onClick = {
                                topBarMenuExpanded = false
                                memoryDialog = MemoryDialogType.ShortTerm
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Память: рабочая") },
                            onClick = {
                                topBarMenuExpanded = false
                                memoryDialog = MemoryDialogType.Working
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Память: долговременная") },
                            onClick = {
                                topBarMenuExpanded = false
                                memoryDialog = MemoryDialogType.LongTerm
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Очистить чат") },
                            onClick = {
                                topBarMenuExpanded = false
                                vm.clearChat()
                            },
                            enabled = !vm.loading
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Модель: ${vm.model}",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Профиль: ${vm.activeProfileName ?: "не выбран"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.activeProfileName == null) MaterialTheme.colorScheme.error else Color.Unspecified
            )

            Text(
                text = "Профиль задачи: ${vm.taskProfileLabel ?: "не определён"}" +
                        (vm.taskProfileCode?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Стадия: ${vm.taskStageLabel ?: "не определена"}" +
                        (vm.taskStageCode?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.taskStageCode == null) MaterialTheme.colorScheme.error else Color.Unspecified
            )

            /*Text(
                text = "Температура: ${String.format(Locale.US, "%.2f", vm.temperature)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = vm.temperature,
                onValueChange = { vm.temperature = it },
                valueRange = 0f..2f
            )*/

            if (vm.historyLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .height(16.dp)
                            .width(16.dp)
                    )
                    Text("Загрузка истории...", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!vm.error.isNullOrBlank()) {
                Text(
                    text = vm.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Диалог
            val listState = rememberLazyListState()

            LaunchedEffect(vm.messages.size) {
                if (vm.messages.isNotEmpty()) {
                    listState.animateScrollToItem(vm.messages.lastIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.messages) { msg ->
                    MessageBubble(msg)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (vm.loading) "Генерация..." else "",
                    style = MaterialTheme.typography.bodySmall
                )

                TextButton(
                    onClick = { vm.retryLastUser() },
                    enabled = !vm.loading && !vm.historyLoading && vm.messages.any { it.role == "user" }
                ) {
                    Icon(
                        painterResource(R.drawable.baseline_refresh_24),
                        contentDescription = "Повторить"
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Повторить запрос")
                }

                TextButton(
                    onClick = { vm.resetTaskState() },
                    enabled = !vm.loading && !vm.historyLoading
                ) {
                    Text("Сброс стадии")
                }
            }

            // Ввод + кнопка
            OutlinedTextField(
                value = vm.inputText,
                onValueChange = { if (it.length <= maxPromptChars) vm.inputText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Сообщение") },
                supportingText = { Text("${vm.inputText.length} / $maxPromptChars") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send() }),
                trailingIcon = {
                    IconButton(
                        onClick = { vm.send() },
                        enabled = !vm.loading && vm.inputText.isNotBlank()
                    ) {
                        Icon(
                            painterResource(R.drawable.baseline_send_24),
                            contentDescription = "Отправить"
                        )
                    }
                }
            )
        }
    }

    memoryDialog?.let { type ->
        val (title, text) = when (type) {
            MemoryDialogType.ShortTerm -> "Краткосрочная память" to vm.shortTermMemoryDump()
            MemoryDialogType.Working -> "Рабочая память" to vm.workingMemoryDump()
            MemoryDialogType.LongTerm -> "Долговременная память" to vm.longTermMemoryDump()
        }
        val dialogScrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { memoryDialog = null },
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(dialogScrollState)
                ) {
                    Text(text)
                }
            },
            confirmButton = {
                TextButton(onClick = { memoryDialog = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

private enum class MemoryDialogType {
    ShortTerm,
    Working,
    LongTerm
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isUser && (msg.latencyMs != null || msg.usage != null)) {
                    Spacer(Modifier.height(6.dp))
                    val u = msg.usage
                    Text(
                        text = buildString {
                            if (msg.latencyMs != null) append("Latency: ${msg.latencyMs} ms")
                            u?.let { usage ->
                                if (usage.totalTokens != null ||
                                    usage.currentRequestTokens != null ||
                                    usage.modelResponseTokens != null ||
                                    usage.cumulativeInputTokens != null ||
                                    usage.cumulativeOutputTokens != null
                                ) {
                                    if (msg.latencyMs != null) append(" | ")
                                    append(
                                        "Tokens: запрос=${usage.currentRequestTokens ?: "?"} " +
                                                "ответ=${usage.modelResponseTokens ?: "?"}"
                                    )

                                    if (usage.cumulativeInputTokens != null || usage.cumulativeOutputTokens != null) {
                                        append(
                                            " | за сессию: in=${usage.cumulativeInputTokens ?: "?"} " +
                                                    "out=${usage.cumulativeOutputTokens ?: "?"}"
                                        )
                                    }

                                    if (usage.totalTokens != null) {
                                        append(" | total=${usage.totalTokens}")
                                    }

                                    /*if (usage.inputTokens != null || usage.outputTokens != null || usage.totalTokens != null) {
                                        append(" | api: in=${usage.inputTokens ?: "?"} out=${usage.outputTokens ?: "?"} total=${usage.totalTokens ?: "?"}")
                                    }*/
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
