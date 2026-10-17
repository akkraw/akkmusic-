package dev.local.player.desktop

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.util.concurrent.Executors

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Движок воспроизведения на libVLC. Держит очередь, перемешивание, повтор.
 * Отдаёт состояние через [onChange]; позицию UI опрашивает сам (она меняется часто).
 */
class PlayerEngine {

    /** Нашлась ли установленная VLC. Без неё воспроизведение не работает. */
    val vlcAvailable: Boolean = try {
        NativeDiscovery().discover()
    } catch (e: Throwable) {
        false
    }

    private val component: AudioPlayerComponent? = if (vlcAvailable) AudioPlayerComponent() else null
    private val player: MediaPlayer? = component?.mediaPlayer()
    private val advanceExec = Executors.newSingleThreadExecutor()

    var queue: List<Track> = emptyList()
        private set
    private var order: List<Int> = emptyList()   // индексы queue в порядке проигрывания
    private var orderPos: Int = -1

    var shuffle: Boolean = false
        private set
    var repeat: RepeatMode = RepeatMode.OFF
        private set

    var currentTrack: Track? = null
        private set
    var playing: Boolean = false
        private set

    /** Вызывается при смене трека, паузы, режимов — чтобы UI перерисовался. */
    var onChange: (() -> Unit)? = null

    init {
        player?.audio()?.setVolume(AppConfig.volume)
        player?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mp: MediaPlayer) {
                // Переключать трек в обработчике события VLC нельзя — уводим в другой поток
                advanceExec.execute { onTrackFinished() }
            }
            override fun playing(mp: MediaPlayer) = pushState(true)
            override fun paused(mp: MediaPlayer) = pushState(false)
            override fun stopped(mp: MediaPlayer) = pushState(false)
        })
    }

    private fun pushState(isPlaying: Boolean) {
        playing = isPlaying
        onChange?.invoke()
    }

    // ---------- Очередь ----------

    /** Поставить список в очередь и начать с [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int, shuffleOn: Boolean = shuffle) {
        if (tracks.isEmpty()) return
        queue = tracks
        shuffle = shuffleOn
        rebuildOrder(startIndex)
        playOrderPos(0)
    }

    private fun rebuildOrder(startIndex: Int) {
        val indices = queue.indices.toMutableList()
        if (shuffle) {
            indices.shuffle()
            // поставить выбранный трек первым
            indices.remove(startIndex)
            indices.add(0, startIndex)
        }
        order = indices
        orderPos = if (shuffle) 0 else startIndex
    }

    private fun playOrderPos(pos: Int) {
        if (pos !in order.indices) return
        orderPos = pos
        val track = queue[order[pos]]
        currentTrack = track
        player?.media()?.play(track.mrl)
        playing = true
        onChange?.invoke()
    }

    private fun onTrackFinished() {
        when (repeat) {
            RepeatMode.ONE -> playOrderPos(orderPos)
            else -> {
                if (orderPos + 1 < order.size) playOrderPos(orderPos + 1)
                else if (repeat == RepeatMode.ALL) playOrderPos(0)
                else { playing = false; onChange?.invoke() }
            }
        }
    }

    // ---------- Управление ----------

    fun togglePlay() {
        val p = player ?: return
        if (p.status().isPlaying) p.controls().pause()
        else if (currentTrack != null) p.controls().play()
    }

    fun next() {
        if (orderPos + 1 < order.size) playOrderPos(orderPos + 1)
        else if (order.isNotEmpty()) playOrderPos(0)
    }

    fun previous() {
        // первые 3 секунды — назад к началу трека, иначе к предыдущему
        if ((player?.status()?.time() ?: 0) > 3000) { seekTo(0); return }
        if (orderPos - 1 >= 0) playOrderPos(orderPos - 1)
        else seekTo(0)
    }

    fun seekTo(ms: Long) {
        player?.controls()?.setTime(ms)
    }

    fun setVolume(percent: Int) {
        player?.audio()?.setVolume(percent.coerceIn(0, 100))
        AppConfig.volume = percent
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        val current = order.getOrNull(orderPos) ?: 0
        rebuildOrder(current)
        onChange?.invoke()
    }

    fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        onChange?.invoke()
    }

    // ---------- Опрос состояния для UI ----------

    fun positionMs(): Long = player?.status()?.time()?.coerceAtLeast(0) ?: 0
    fun durationMs(): Long = (player?.status()?.length() ?: 0).coerceAtLeast(0)
    fun queuePosition(): Int = orderPos + 1
    fun queueSize(): Int = order.size

    fun release() {
        try { component?.release() } catch (e: Throwable) {}
        advanceExec.shutdown()
    }
}
