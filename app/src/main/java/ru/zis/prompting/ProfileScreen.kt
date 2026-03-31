package ru.zis.prompting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.zis.prompting.profile.UserProfile

private const val STYLE_HINT =
    "Пример: Отвечай кратко и по делу. Используй разговорный тон. Язык ответов — русский. Код без лишних комментариев."

private const val CONSTRAINTS_HINT =
    "Пример: Стек — Kotlin, Jetpack Compose, MVVM. Только бесплатные API. Без сторонних библиотек кроме Room и Retrofit."

private const val CONTEXT_HINT =
    "Пример: Я Android-разработчик. Проект — мобильное приложение для трекинга задач. Команда 3 человека. Дедлайн — 20 марта."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    vm: ProfileViewModel = viewModel()
) {
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var createMode by remember { mutableStateOf(false) }
    var invariantDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профили") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Назад") }
                },
                actions = {
                    TextButton(onClick = {
                        createMode = true
                        editingProfile = null
                    }) {
                        Text("+ Профиль")
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
                text = "Выберите активный профиль. Его style/constraints/context автоматически добавляются в каждый запрос.",
                style = MaterialTheme.typography.bodySmall
            )

            vm.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Профили",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (vm.loading) {
                    Text("Загрузка профилей...")
                } else if (vm.profiles.isEmpty()) {
                    Text("Профилей пока нет. Нажмите '+ Профиль'.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vm.profiles, key = { it.id }) { profile ->
                            val isActive = vm.activeProfileId == profile.id
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isActive) "${profile.name} (активный)" else profile.name,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (profile.style.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text("Стиль: ${profile.style}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (profile.constraints.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Ограничения: ${profile.constraints}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (profile.context.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Контекст: ${profile.context}", style = MaterialTheme.typography.bodySmall)
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!isActive) {
                                            TextButton(onClick = { vm.setActive(profile.id) }) {
                                                Text("Сделать активным")
                                            }
                                        }
                                        TextButton(onClick = { editingProfile = profile }) {
                                            Text("Редактировать")
                                        }
                                        TextButton(onClick = { vm.deleteProfile(profile.id) }) {
                                            Text("Удалить")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Инварианты ассистента (нарушать нельзя)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = invariantDraft,
                onValueChange = { invariantDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Новый инвариант") },
                placeholder = { Text("Например: Не предлагать решения вне Kotlin + Compose") },
                minLines = 2
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.addInvariant(invariantDraft)
                        invariantDraft = ""
                    }
                ) {
                    Text("Добавить инвариант")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (vm.invariants.isEmpty()) {
                    Text(
                        text = "Инварианты пока не добавлены.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vm.invariants, key = { it.id }) { item ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(item.rule, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = { vm.deleteInvariant(item.id) }) {
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (createMode || editingProfile != null) {
        val profile = editingProfile
        ProfileEditorDialog(
            title = if (profile == null) "Новый профиль" else "Редактирование профиля",
            initialName = profile?.name.orEmpty(),
            initialStyle = profile?.style.orEmpty(),
            initialConstraints = profile?.constraints.orEmpty(),
            initialContext = profile?.context.orEmpty(),
            initialSetActive = profile?.id == vm.activeProfileId,
            onDismiss = {
                createMode = false
                editingProfile = null
            },
            onSave = { name, style, constraints, context, setActive ->
                vm.saveProfile(
                    profileId = profile?.id,
                    name = name,
                    style = style,
                    constraints = constraints,
                    context = context,
                    setActive = setActive
                )
                createMode = false
                editingProfile = null
            }
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    title: String,
    initialName: String,
    initialStyle: String,
    initialConstraints: String,
    initialContext: String,
    initialSetActive: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, style: String, constraints: String, context: String, setActive: Boolean) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var style by remember(initialStyle) { mutableStateOf(initialStyle) }
    var constraints by remember(initialConstraints) { mutableStateOf(initialConstraints) }
    var context by remember(initialContext) { mutableStateOf(initialContext) }
    var setActive by remember(initialSetActive) { mutableStateOf(initialSetActive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название профиля") }
                )
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Стиль") },
                    placeholder = { Text(STYLE_HINT) }
                )
                OutlinedTextField(
                    value = constraints,
                    onValueChange = { constraints = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Ограничения (строго)") },
                    placeholder = { Text(CONSTRAINTS_HINT) }
                )
                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Контекст") },
                    placeholder = { Text(CONTEXT_HINT) }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { setActive = !setActive }) {
                        Text(if (setActive) "✓ Сделать активным" else "Сделать активным")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, style, constraints, context, setActive) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
