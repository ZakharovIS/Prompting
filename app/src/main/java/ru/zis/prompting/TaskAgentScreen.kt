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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.zis.prompting.task.TaskStage
import ru.zis.prompting.task.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskAgentScreen(
    onBack: () -> Unit,
    vm: TaskAgentViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        vm.refreshActiveProfile()
    }

    val state = vm.taskState
    val infoScrollState = rememberScrollState()
    val visibleMessages = vm.messages.filter { !it.isHidden }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FSM Агент") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    TextButton(onClick = { vm.resetTask() }, enabled = !vm.loading) {
                        Text("Сброс")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Профиль: ${vm.activeProfileName ?: "не выбран"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.activeProfileName == null) MaterialTheme.colorScheme.error else Color.Unspecified
            )

            TaskStageProgress(current = state.stage)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 92.dp)
                        .verticalScroll(infoScrollState),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("${state.stage} • ${state.status} • шаг ${state.currentStep}/${state.totalSteps}")
                    Text("→ ${state.expectedAction}")
                }
            }

            if (!vm.error.isNullOrBlank()) {
                Text(
                    text = vm.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.stage == TaskStage.IDLE) {
                OutlinedTextField(
                    value = vm.inputTask,
                    onValueChange = { vm.inputTask = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("Введите задачу") }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = { vm.startTask() },
                        enabled = !vm.loading && vm.inputTask.isNotBlank()
                    ) {
                        Text("Начать")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (state.status == TaskStatus.PAUSED) vm.continueStage() else vm.pause()
                        },
                        enabled = !vm.loading && state.stage != TaskStage.DONE
                    ) {
                        Text(if (state.status == TaskStatus.PAUSED) "Продолжить" else "Пауза")
                    }

                    TextButton(
                        onClick = { vm.continueStage() },
                        enabled = !vm.loading && state.status == TaskStatus.ACTIVE && state.stage != TaskStage.DONE
                    ) {
                        Text("Следующий шаг")
                    }

                    TextButton(onClick = { vm.resetTask() }, enabled = !vm.loading) {
                        Text("Новая задача")
                    }

                    if (vm.loading) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    }
                }

                OutlinedTextField(
                    value = vm.userInput,
                    onValueChange = { vm.userInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = {
                        Text(
                            if (state.status == TaskStatus.PAUSED) {
                                "Свободный вопрос"
                            } else {
                                "Корректировка этапа"
                            }
                        )
                    },
                    placeholder = {
                        Text(
                            if (state.status == TaskStatus.PAUSED) {
                                "Вопрос вне контекста задачи"
                            } else {
                                "Уточните, как скорректировать текущий шаг"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendUserInput() }),
                    trailingIcon = {
                        IconButton(
                            onClick = { vm.sendUserInput() },
                            enabled = !vm.loading && vm.userInput.isNotBlank()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.baseline_send_24),
                                contentDescription = "Отправить"
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleMessages) { msg ->
                    AgentMessageBubble(msg = msg)
                }
            }
        }
    }
}

@Composable
private fun TaskStageProgress(current: TaskStage) {
    val stages = listOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.DONE)
    val currentIndex = stages.indexOf(current)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stages.forEachIndexed { index, stage ->
            val done = currentIndex >= 0 && index <= currentIndex
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = stage.name.lowercase(),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(msg: UiMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(msg.text)
            }
        }
    }
}
