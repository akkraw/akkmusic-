package dev.local.player.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

private val EdgeDark = Color(0xFF0B0F12)

fun main() {
    val log = File(AppConfig.appDir(), "crash.log")
    Thread.setDefaultUncaughtExceptionHandler { _, e -> logCrash(log, e) }
    try {
        application {
            val state = rememberWindowState(width = 1040.dp, height = 720.dp)
            // undecorated: своя рамка и кнопки, без системной планки
            Window(onCloseRequest = ::exitApplication, state = state, undecorated = true, title = "AkkMusic") {
                AppRoot(state, ::exitApplication)
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
private fun FrameWindowScope.AppRoot(windowState: WindowState, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val engine = remember { PlayerEngine() }
    DisposableEffect(Unit) { onDispose { engine.release() } }

    var accentKey by remember { mutableStateOf(AppConfig.accent ?: "sky") }
    var textKey by remember { mutableStateOf(AppConfig.textColor ?: "white") }
    var fontKey by remember { mutableStateOf(AppConfig.font ?: "system") }
    var bgPath by remember { mutableStateOf(AppConfig.bgPath) }
    var bgDim by remember { mutableStateOf(AppConfig.bgDim) }
    var bgBlur by remember { mutableStateOf(AppConfig.bgBlur) }

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
            loading = true; error = null
            try {
                library = withContext(Dispatchers.IO) {
                    val local = if (source.usesLocal) LocalLibrary.scan(localFolder) else emptyList()
                    val remote = if (source.usesServer) AppConfig.serverClient()?.songs() ?: emptyList() else emptyList()
                    local + remote
                }
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить библиотеку"; library = emptyList()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(source, localFolder, serverOn) { reload() }

    val hasBg = bgPath != null
    val panelColor = if (hasBg) EdgeDark.copy(alpha = 0.72f) else Color(0xFF1A2227)
    val bgImage = rememberFileImage(bgPath)

    AkkTheme(accentOf(accentKey).color, textOf(textKey).color, panelColor, Fonts.family(fontKey)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (bgImage != null) {
                Image(
                    BitmapPainter(bgImage), null,
                    modifier = Modifier.fillMaxSize().then(if (bgBlur > 0) Modifier.blur(bgBlur.dp) else Modifier),
                    contentScale = ContentScale.Crop,
                )
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

            Column(Modifier.fillMaxSize()) {
                WindowChrome(
                    windowState = windowState,
                    hasBg = hasBg,
                    count = if (configured) shown.size else 0,
                    query = query,
                    showSearch = configured,
                    onQuery = { query = it },
                    onSettings = { showSettings = true },
                    onExit = onExit,
                )
                if (!configured) {
                    SetupScreen(
                        onPickFolder = { f -> AppConfig.localFolder = f; localFolder = f },
                        onConnectServer = { showSettings = true },
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
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
                                    TrackRow(track, track.id == currentId) { engine.play(shown, shown.indexOf(track)) }
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                            }
                        }
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                        }
                    }
                    engine.currentTrack?.let { PlayerBar(engine, it, tick, hasBg) { fullOpen = true } }
                }
            }

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
                accentKey, textKey, fontKey, bgPath, bgDim, bgBlur,
                onSource = { source = it; AppConfig.source = it },
                onFolder = { AppConfig.localFolder = it; localFolder = it },
                onServerChanged = { serverOn = AppConfig.serverConfigured },
                onAccent = { accentKey = it; AppConfig.accent = it },
                onText = { textKey = it; AppConfig.textColor = it },
                onFont = { fontKey = it; AppConfig.font = it },
                onBg = { bgPath = it; AppConfig.bgPath = it },
                onDim = { bgDim = it; AppConfig.bgDim = it },
                onBlur = { bgBlur = it; AppConfig.bgBlur = it },
                onClose = { showSettings = false },
            )
        }
    }
}

/** Своя верхняя панель: перетаскивание окна, заголовок, поиск, настройки и кнопки окна. */
@Composable
private fun FrameWindowScope.WindowChrome(
    windowState: WindowState,
    hasBg: Boolean,
    count: Int,
    query: String,
    showSearch: Boolean,
    onQuery: (String) -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val brush = if (hasBg)
        Brush.verticalGradient(listOf(EdgeDark.copy(alpha = 0.85f), EdgeDark.copy(alpha = 0.35f)))
    else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))

    Box(Modifier.fillMaxWidth().background(brush)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Перетаскивание окна за заголовок
            Box(
                Modifier.weight(1f).pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        window.setLocation(
                            (window.x + drag.x).toInt(),
                            (window.y + drag.y).toInt(),
                        )
                    }
                }
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "akkmusic_",
                        fontFamily = Fonts.monoOrFallback(),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (showSearch) {
                        Text("$count треков", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (showSearch) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    placeholder = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.width(260.dp),
                )
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
            }
            // Кнопки окна
            IconButton(onClick = { windowState.isMinimized = true }) {
                Icon(Icons.Default.Minimize, "Свернуть")
            }
            IconButton(onClick = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating
                    else WindowPlacement.Fullscreen
            }) {
                Icon(
                    if (windowState.placement == WindowPlacement.Fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    "Во весь экран",
                )
            }
            IconButton(onClick = onExit) { Icon(Icons.Default.Close, "Закрыть") }
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
private fun PlayerBar(engine: PlayerEngine, track: Track, tick: Int, hasBg: Boolean, onExpand: () -> Unit) {
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var volume by remember { mutableStateOf(AppConfig.volume.toFloat()) }
    val accent = MaterialTheme.colorScheme.primary

    LaunchedEffect(track.id) {
        while (true) {
            if (!dragging) { pos = engine.positionMs(); dur = engine.durationMs() }
            delay(100)
        }
    }

    val brush = if (hasBg)
        Brush.verticalGradient(listOf(EdgeDark.copy(alpha = 0.4f), EdgeDark.copy(alpha = 0.9f)))
    else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))

    Box(Modifier.fillMaxWidth().background(brush)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Громкость
                Icon(
                    if (volume < 1f) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).size(18.dp),
                )
                Slider(
                    value = volume,
                    onValueChange = { volume = it; engine.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    modifier = Modifier.width(110.dp).padding(start = 6.dp),
                )
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
