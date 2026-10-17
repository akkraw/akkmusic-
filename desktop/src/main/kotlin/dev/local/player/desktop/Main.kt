package dev.local.player.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URL
import javax.swing.JFileChooser

private val Accent = Color(0xFF8CCBF2)
private val OnAccent = Color(0xFF0B2A3D)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent,
    background = Color(0xFF111518),
    surface = Color(0xFF1A2227),
    onBackground = Color(0xFFEBF0F4),
    onSurface = Color(0xFFEBF0F4),
    onSurfaceVariant = Color(0xFF9AA8B2),
)

fun main() {
    // Любую ошибку старта пишем в лог рядом с настройками — у оконного приложения
    // нет консоли, иначе падение на запуске выглядит как «ничего не происходит».
    val log = java.io.File(AppConfig.appDir(), "crash.log")
    Thread.setDefaultUncaughtExceptionHandler { _, e -> logCrash(log, e) }
    try {
        application {
            val state = rememberWindowState(width = 1040.dp, height = 720.dp)
            Window(onCloseRequest = ::exitApplication, title = "AkkMusic", state = state) {
                MaterialTheme(colorScheme = DarkColors) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App()
                    }
                }
            }
        }
    } catch (e: Throwable) {
        logCrash(log, e)
        throw e
    }
}

private fun logCrash(file: java.io.File, e: Throwable) {
    try {
        file.appendText("[${java.time.LocalDateTime.now()}] " + e.stackTraceToString() + "\n")
    } catch (_: Throwable) {
    }
}

@Composable
private fun App() {
    val scope = rememberCoroutineScope()
    val engine = remember { PlayerEngine() }
    DisposableEffect(Unit) { onDispose { engine.release() } }

    var source by remember { mutableStateOf(AppConfig.source) }
    var localFolder by remember { mutableStateOf(AppConfig.localFolder) }
    var serverOn by remember { mutableStateOf(AppConfig.serverConfigured) }

    var library by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    // перерисовка при смене состояния плеера
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(engine) {
        engine.onChange = { tick++ }
        onDispose { engine.onChange = null }
    }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val local = if (source.usesLocal) LocalLibrary.scan(localFolder) else emptyList()
                    val remote = if (source.usesServer) AppConfig.serverClient()?.songs() ?: emptyList() else emptyList()
                    (local + remote)
                }
                library = result
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить библиотеку"
                library = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(source, localFolder, serverOn) { reload() }

    val configured = localFolder != null || serverOn

    val shown = remember(library, query) {
        val q = query.trim().lowercase().replace('ё', 'е')
        if (q.isEmpty()) library
        else library.filter {
            (it.title + " " + it.artist + " " + it.album).lowercase().replace('ё', 'е').contains(q)
        }
    }

    if (!configured) {
        SetupScreen(
            onPickFolder = { f -> AppConfig.localFolder = f; localFolder = f },
            onConnectServer = { showSettings = true },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                count = shown.size,
                query = query,
                onQuery = { query = it },
                onSettings = { showSettings = true },
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                } else if (shown.isEmpty()) {
                    Text(
                        if (library.isEmpty()) "Музыка не найдена" else "Ничего не найдено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    val currentId = engine.currentTrack?.id
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { track ->
                            TrackRow(track, track.id == currentId) {
                                engine.play(shown, shown.indexOf(track))
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
            engine.currentTrack?.let { PlayerBar(engine, it, tick) }
        }
    }

    if (showSettings) {
        SettingsDialog(
            source, localFolder, serverOn, engine,
            onSource = { source = it; AppConfig.source = it },
            onFolder = { AppConfig.localFolder = it; localFolder = it },
            onServerChanged = { serverOn = AppConfig.serverConfigured },
            onClose = { showSettings = false },
        )
    }
}

@Composable
private fun TopBar(count: Int, query: String, onQuery: (String) -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("AkkMusic", style = MaterialTheme.typography.headlineSmall)
                Text("$count треков", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.width(280.dp),
            )
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
        }
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (playing) Accent.copy(alpha = 0.16f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = if (playing) Accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${track.artist} · ${track.album}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (track.remote) {
                Icon(Icons.Default.Cloud, "С сервера", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp).size(16.dp))
            }
            Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerBar(engine: PlayerEngine, track: Track, tick: Int) {
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // тикер позиции
    LaunchedEffect(track.id) {
        while (true) {
            if (!dragging) { pos = engine.positionMs(); dur = engine.durationMs() }
            kotlinx.coroutines.delay(500)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(track, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { engine.toggleShuffle(); }) {
                    Icon(Icons.Default.Shuffle, "Перемешать",
                        tint = if (engine.shuffle) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { engine.previous() }) { Icon(Icons.Default.SkipPrevious, "Назад") }
                FilledIconButton(
                    onClick = { engine.togglePlay() },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = OnAccent),
                ) {
                    Icon(if (engine.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Играть")
                }
                IconButton(onClick = { engine.next() }) { Icon(Icons.Default.SkipNext, "Вперёд") }
                IconButton(onClick = { engine.cycleRepeat() }) {
                    Icon(
                        if (engine.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Повтор",
                        tint = if (engine.repeat != RepeatMode.OFF) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(if (dragging) dragValue.toLong() else pos),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = if (dragging) dragValue else pos.toFloat(),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { engine.seekTo(dragValue.toLong()); dragging = false },
                    valueRange = 0f..(dur.coerceAtLeast(1).toFloat()),
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatTime(dur), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Обложка трека: локальная из тегов, серверная по URL. Грузится в фоне. */
@Composable
private fun Cover(track: Track, size: androidx.compose.ui.unit.Dp) {
    val image by produceState<ImageBitmap?>(null, track.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val bytes = when {
                    track.sourcePath != null -> LocalLibrary.coverBytes(track.sourcePath)
                    track.coverUrl != null -> URL(track.coverUrl).openStream().use { it.readBytes() }
                    else -> null
                }
                bytes?.let { loadImageBitmap(ByteArrayInputStream(it)) }
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(BitmapPainter(img), null, modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            Surface(Modifier.fillMaxSize(), color = Accent.copy(alpha = 0.2f)) {
                Icon(Icons.Default.MusicNote, null, tint = Accent,
                    modifier = Modifier.padding(10.dp))
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
