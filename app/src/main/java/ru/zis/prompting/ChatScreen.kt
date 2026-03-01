package ru.zis.prompting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.zis.prompting.agent.ContextStrategy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val maxPromptChars = 1500
    var showCheckpointDialog by remember { mutableStateOf(false) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showBranchSwitchDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RouterAI LLM chat") },
                actions = {
                    TextButton(onClick = { vm.clearChat() }, enabled = !vm.loading) {
                        Text("Очистить")
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = vm.strategy == strategy,
                        onClick = { vm.switchStrategy(strategy) },
                        label = { Text(strategy.title) },
                        enabled = !vm.loading && !vm.historyLoading
                    )
                }
            }

            if (vm.strategy == ContextStrategy.STICKY_FACTS) {
                FactsPanel(vm)
            }

            if (vm.strategy == ContextStrategy.BRANCHING) {
                BranchingPanel(
                    vm = vm,
                    onSaveCheckpoint = { showCheckpointDialog = true },
                    onCreateBranch = { showBranchDialog = true },
                    onSwitchBranch = { showBranchSwitchDialog = true }
                )
            }

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
                    CircularProgressIndicator(strokeWidth = 2.dp,
                        modifier = Modifier.height(16.dp).width(16.dp))
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
                    Icon(painterResource(R.drawable.baseline_refresh_24), contentDescription = "Повторить")
                    Spacer(Modifier.width(8.dp))
                    Text("Повторить запрос")
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
                        Icon(painterResource(R.drawable.baseline_send_24), contentDescription = "Отправить")
                    }
                }
            )
        }
    }

    if (showCheckpointDialog) {
        NameInputDialog(
            title = "Сохранить checkpoint",
            label = "Имя checkpoint",
            onDismiss = { showCheckpointDialog = false },
            onConfirm = {
                vm.saveCheckpoint(it)
                showCheckpointDialog = false
            }
        )
    }

    if (showBranchDialog) {
        CreateBranchDialog(
            checkpointNames = vm.checkpointNames,
            onDismiss = { showBranchDialog = false },
            onConfirm = { checkpointName, branchName ->
                vm.createBranch(checkpointName, branchName)
                showBranchDialog = false
            }
        )
    }

    if (showBranchSwitchDialog) {
        SelectBranchDialog(
            activeBranch = vm.activeBranch,
            branchNames = vm.branchNames,
            onDismiss = { showBranchSwitchDialog = false },
            onSelect = {
                vm.switchBranch(it)
                showBranchSwitchDialog = false
            }
        )
    }
}

@Composable
private fun FactsPanel(vm: ChatViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Facts", style = MaterialTheme.typography.titleSmall)
            if (vm.facts.isEmpty()) {
                Text("Пока нет извлечённых фактов", style = MaterialTheme.typography.bodySmall)
            } else {
                vm.facts.forEach { (k, v) ->
                    Text("• $k: $v", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BranchingPanel(
    vm: ChatViewModel,
    onSaveCheckpoint: () -> Unit,
    onCreateBranch: () -> Unit,
    onSwitchBranch: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Branching", style = MaterialTheme.typography.titleSmall)
            Text("Активная ветка: ${vm.activeBranch}", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSaveCheckpoint, enabled = !vm.loading) {
                    Text("Checkpoint")
                }
                TextButton(onClick = onCreateBranch, enabled = !vm.loading && vm.checkpointNames.isNotEmpty()) {
                    Text("Создать ветку")
                }
                TextButton(onClick = onSwitchBranch, enabled = !vm.loading && vm.branchNames.isNotEmpty()) {
                    Text("Переключить")
                }
            }
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun CreateBranchDialog(
    checkpointNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var selectedCheckpoint by remember { mutableStateOf(checkpointNames.firstOrNull().orEmpty()) }
    var branchName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать ветку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Checkpoint:", style = MaterialTheme.typography.bodySmall)
                checkpointNames.forEach { cp ->
                    TextButton(onClick = { selectedCheckpoint = cp }) {
                        Text(if (cp == selectedCheckpoint) "✓ $cp" else cp)
                    }
                }
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Имя новой ветки") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedCheckpoint, branchName.trim()) },
                enabled = selectedCheckpoint.isNotBlank() && branchName.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun SelectBranchDialog(
    activeBranch: String,
    branchNames: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбор ветки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                branchNames.forEach { branch ->
                    TextButton(onClick = { onSelect(branch) }) {
                        Text(if (branch == activeBranch) "✓ $branch" else branch)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
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
