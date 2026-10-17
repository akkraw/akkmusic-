package dev.local.player.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

fun main() {
    val log = File(AppConfig.appDir(), "crash.log")
    Thread.setDefaultUncaughtExceptionHandler { _, e -> logCrash(log, e) }
    try {
        application {
            val state = rememberWindowState(width = 1040.dp, height = 720.dp)
            Window(onCloseRequest = ::exitApplication, title = "AkkMusic", state = state) {
                AppRoot()
            }
        }
    } catch (e: Throwable) {
        logCrash(log, e)
        throw e
    }
}

private fun logCrash(file: File, e: Throwable) {
    try {
        file.appendText("[${java.time.LocalDateTime.now()}] " + e.stackTraceToString() + "\n")
    } catch (_: Throwable) {
    }
}

@Composable
private fun AppRoot() {
    val scope = rememberCoroutineScope()
    val engine = remember { PlayerEngine() }
    DisposableEffect(Unit) { onDispose { engine.release() } }

    // Внешний вид
    var accentKey by remember { mutableStateOf(AppConfig.accent ?: "sky") }
    var textKey by remember { mutableStateOf(AppConfig.textColor ?: "white") }
    var bgPath by remember { mutableStateOf(AppConfig.bgPath) }
    var bgDim by remember { mutableStateOf(AppConfig.bgDim) }

    // Источник и библиотека
    var source by remember { mutableStateOf(AppConfig.source) }
    var localFolder by remember { mutableStateOf(AppConfig.localFolder) }
    var serverOn by remember { mutableStateOf(AppConfig.serverConfigured) }
    var library by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var fullOpen by remember { mutableStateOf(false) }

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
                library = withContext(Dispatchers.IO) {
                    val local = if (source.usesLocal) LocalLibrary.scan(localFolder) else emptyList()
                    val remote = if (source.usesServer) AppConfig.serverClient()?.songs() ?: emptyList() else emptyList()
                    local + remote
                }
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить библиотеку"
                library = emptyList()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(source, localFolder, serverOn) { reload() }

    val hasBg = bgPath != null
    val panelColor = if (hasBg) Color(0xFF0B0F12).copy(alpha = 0.72f) else Color(0xFF1A2227)
    val bgImage = rememberFileImage(bgPath)

    AkkTheme(accentOf(accentKey).color, textOf(textKey).color, panelColor) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Фон-картинка + затемнение
            if (bgImage != null) {
                Image(BitmapPainter(bgImage), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = bgDim / 100f)))
            }

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
                    TopBar(shown.size, query, { query = it }, { showSettings = true })
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary)
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
                    engine.currentTrack?.let { PlayerBar(engine, it, tick) { fullOpen = true } }
                }
            }

            // Полноэкранный плеер поверх всего
            AnimatedVisibility(
                visible = fullOpen && engine.currentTrack != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                engine.currentTrack?.let { FullPlayer(engine, it, tick) { fullOpen = false } }
            }
        }

        if (showSettings) {
            SettingsDialog(
                source, localFolder, serverOn, engine,
                accentKey, textKey, bgPath, bgDim,
                onSource = { source = it; AppConfig.source = it },
                onFolder = { AppConfig.localFolder = it; localFolder = it },
                onServerChanged = { serverOn = AppConfig.serverConfigured },
                onAccent = { accentKey = it; AppConfig.accent = it },
                onText = { textKey = it; AppConfig.textColor = it },
                onBg = { bgPath = it; AppConfig.bgPath = it },
                onDim = { bgDim = it; AppConfig.bgDim = it },
                onClose = { showSettings = false },
            )
        }
    }
}

@Composable
private fun TopBar(count: Int, query: String, onQuery: (String) -> Unit, onSettings: () -> Unit) {
    Surface(color = LocalPanelColor.current, tonalElevation = 2.dp) {
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
                value = query, onValueChange = onQuery, singleLine = true,
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
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = if (playing) accent.copy(alpha = 0.16f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = if (playing) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text("${track.artist} · ${track.album}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
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
private fun PlayerBar(engine: PlayerEngine, track: Track, tick: Int, onExpand: () -> Unit) {
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(track.id) {
        while (true) {
            if (!dragging) { pos = engine.positionMs(); dur = engine.durationMs() }
            delay(100)
        }
    }

    Surface(color = LocalPanelColor.current, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Клик по обложке и названию открывает большой плеер
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onExpand() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallCover(track, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                IconButton(onClick = { engine.toggleShuffle() }) {
                    Icon(Icons.Default.Shuffle, "Перемешать",
                        tint = if (engine.shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { engine.previous() }) { Icon(Icons.Default.SkipPrevious, "Назад") }
                FilledIconButton(
                    onClick = { engine.togglePlay() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary),
                ) {
                    Icon(if (engine.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Играть")
                }
                IconButton(onClick = { engine.next() }) { Icon(Icons.Default.SkipNext, "Вперёд") }
                IconButton(onClick = { engine.cycleRepeat() }) {
                    Icon(
                        if (engine.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Повтор",
                        tint = if (engine.repeat != RepeatMode.OFF) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(if (dragging) dragValue.toLong() else pos),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = if (dragging) dragValue else pos.toFloat(),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { engine.seekTo(dragValue.toLong()); dragging = false },
                    valueRange = 0f..(dur.coerceAtLeast(1).toFloat()),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatTime(dur), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SmallCover(track: Track, size: Dp) {
    val cover = rememberCover(track)
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (cover != null) {
            Image(BitmapPainter(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp))
            }
        }
    }
}

/** Картинка из файла (фон) в фоне. */
@Composable
private fun rememberFileImage(path: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try {
                File(path).inputStream().use { loadImageBitmap(it) }
            } catch (e: Exception) {
                null
            }
        }
    }
    return state.value
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
