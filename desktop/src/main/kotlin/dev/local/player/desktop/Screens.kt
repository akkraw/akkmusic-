package dev.local.player.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

/** Выбор папки через системный диалог. Возвращает путь или null. */
private fun pickFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Папка с музыкой"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
        chooser.selectedFile.absolutePath else null
}

@Composable
fun SetupScreen(onPickFolder: (String) -> Unit, onConnectServer: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AkkMusic", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Выбери папку с музыкой на компьютере\nили подключись к своему серверу",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { pickFolder()?.let(onPickFolder) }) {
                Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Выбрать папку")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onConnectServer) {
                Icon(Icons.Default.Dns, null); Spacer(Modifier.width(8.dp)); Text("Подключиться к серверу")
            }
        }
    }
}

@Composable
fun SettingsDialog(
    source: LibrarySource,
    localFolder: String?,
    serverOn: Boolean,
    engine: PlayerEngine,
    onSource: (LibrarySource) -> Unit,
    onFolder: (String?) -> Unit,
    onServerChanged: () -> Unit,
    onClose: () -> Unit,
) {
    var showServer by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!engine.vlcAvailable) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Text(
                            "Не найден VLC — без него воспроизведение не работает. " +
                                "Установи VLC с videolan.org и перезапусти приложение.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text("Источник музыки", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    LibrarySource.LOCAL to "Папка",
                    LibrarySource.SERVER to "Сервер",
                    LibrarySource.ALL to "Всё",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (src, label) ->
                        SegmentedButton(
                            selected = source == src,
                            onClick = { onSource(src) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            enabled = when (src) {
                                LibrarySource.LOCAL -> localFolder != null
                                LibrarySource.SERVER -> serverOn
                                LibrarySource.ALL -> localFolder != null && serverOn
                            },
                        ) { Text(label) }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Папка с музыкой", style = MaterialTheme.typography.labelLarge)
                Text(
                    localFolder ?: "не выбрана",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row {
                    TextButton(onClick = { pickFolder()?.let(onFolder) }) {
                        Text(if (localFolder == null) "Выбрать" else "Сменить")
                    }
                    if (localFolder != null) {
                        TextButton(onClick = { onFolder(null) }) { Text("Убрать") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Сервер", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (serverOn) AppConfig.serverUrl ?: "" else "не подключён",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row {
                    TextButton(onClick = { showServer = true }) {
                        Text(if (serverOn) "Изменить" else "Подключиться")
                    }
                    if (serverOn) {
                        TextButton(onClick = { AppConfig.forgetServer(); onServerChanged() }) { Text("Отключить") }
                    }
                }
            }
        },
    )

    if (showServer) {
        ServerDialog(
            onClose = { showServer = false },
            onConnected = { onServerChanged(); showServer = false },
        )
    }
}

@Composable
private fun ServerDialog(onClose: () -> Unit, onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(AppConfig.serverUrl ?: "") }
    var user by remember { mutableStateOf(AppConfig.serverUser ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Сервер Navidrome") },
        text = {
            Column {
                OutlinedTextField(url, { url = it }, label = { Text("Адрес (https://…)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Логин") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val client = SubsonicClient.withPassword(url, user, password)
                            withContext(Dispatchers.IO) { client.ping() }
                            AppConfig.saveServer(client)
                            onConnected()
                        } catch (e: Exception) {
                            error = e.message ?: "Не удалось подключиться"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Проверка…" else "Подключиться") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } },
    )
}
