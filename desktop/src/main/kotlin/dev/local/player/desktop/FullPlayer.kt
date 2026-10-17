package dev.local.player.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URL

/** Загрузка обложки трека (из тегов файла или по URL сервера) в фоне. */
@Composable
fun rememberCover(track: Track?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, track?.id) {
        value = if (track == null) null else withContext(Dispatchers.IO) {
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
    return state.value
}

@Composable
fun FullPlayer(engine: PlayerEngine, track: Track, tick: Int, onClose: () -> Unit) {
    val cover = rememberCover(track)
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    LaunchedEffect(track.id) {
        while (true) {
            if (!dragging) { pos = engine.positionMs(); dur = engine.durationMs() }
            delay(100)
        }
    }

    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    // clickable без ряби — чтобы клики не проходили на список под плеером
    Box(
        Modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        // Размытая обложка на весь экран
        if (cover != null) {
            Image(
                BitmapPainter(cover), null,
                modifier = Modifier.fillMaxSize().blur(60.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // Затемнение: сверху легче, снизу плотнее (там кнопки и текст)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(bg.copy(alpha = 0.78f), bg.copy(alpha = 0.88f), bg.copy(alpha = 0.97f))
                )
            )
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, "Свернуть")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${engine.queuePosition()} / ${engine.queueSize()}",
                    style = MaterialTheme.typography.labelMedium, color = variant,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Spacer(Modifier.weight(1f))
            CoverWithGlow(cover, accent)
            Spacer(Modifier.weight(1f))

            Text(
                track.title, style = MaterialTheme.typography.headlineSmall, color = onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                track.artist, style = MaterialTheme.typography.titleMedium, color = accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            if (track.album.isNotBlank()) {
                Text(
                    track.album, style = MaterialTheme.typography.bodyMedium, color = variant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))
            Slider(
                value = if (dragging) dragValue else pos.toFloat(),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { engine.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..(dur.coerceAtLeast(1).toFloat()),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Row(Modifier.fillMaxWidth(0.7f)) {
                Text(formatTime(if (dragging) dragValue.toLong() else pos),
                    style = MaterialTheme.typography.labelMedium, color = variant)
                Spacer(Modifier.weight(1f))
                Text(formatTime(dur), style = MaterialTheme.typography.labelMedium, color = variant)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(0.7f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { engine.toggleShuffle() }) {
                    Icon(Icons.Default.Shuffle, "Перемешать",
                        tint = if (engine.shuffle) accent else variant)
                }
                IconButton(onClick = { engine.previous() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Назад", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { engine.togglePlay() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accent, contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (engine.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Играть", modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { engine.next() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipNext, "Вперёд", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { engine.cycleRepeat() }) {
                    Icon(
                        if (engine.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Повтор", tint = if (engine.repeat != RepeatMode.OFF) accent else variant,
                    )
                }
            }
            Spacer(Modifier.weight(0.4f))
        }
    }
}

/** Обложка по центру с мягким цветным свечением под ней. */
@Composable
private fun CoverWithGlow(cover: ImageBitmap?, accent: Color) {
    Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        if (cover != null) {
            // свечение: размытая увеличенная копия обложки
            Image(
                BitmapPainter(cover), null,
                modifier = Modifier.matchParentSize().scale(1.06f)
                    .blur(48.dp).alpha(0.7f),
                contentScale = ContentScale.Crop,
            )
            Image(
                BitmapPainter(cover), null,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(24.dp))
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(96.dp))
            }
        }
    }
}
