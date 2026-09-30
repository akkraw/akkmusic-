package dev.local.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Сервис, в котором живёт плеер. Media3 сам:
 *  - держит foreground-уведомление с кнопками, пока идёт воспроизведение;
 *  - публикует медиасессию (экран блокировки, Bluetooth-кнопки, часы, авто).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // ---------- Подсчёт прослушиваний ----------
    private lateinit var playCounts: PlayCounts
    private val handler = Handler(Looper.getMainLooper())
    private var playedMs = 0L        // сколько реально играл текущий трек
    private var lastTickAt = 0L
    private var counted = false      // уже засчитан в этот раз

    private val listenTick = object : Runnable {
        override fun run() {
            accumulate()
            maybeCount()
            if (mediaSession?.player?.isPlaying == true) handler.postDelayed(this, 1000)
        }
    }

    private val countingListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Новый трек (или тот же на повторе) — начинаем считать заново
            playedMs = 0
            counted = false
            lastTickAt = SystemClock.elapsedRealtime()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(listenTick)
            if (isPlaying) {
                lastTickAt = SystemClock.elapsedRealtime()
                handler.postDelayed(listenTick, 1000)
            } else {
                accumulate()
                maybeCount()
            }
        }
    }

    private fun accumulate() {
        val now = SystemClock.elapsedRealtime()
        if (mediaSession?.player?.isPlaying == true || lastTickAt > 0) {
            playedMs += (now - lastTickAt).coerceIn(0, 5_000)
        }
        lastTickAt = now
    }

    private fun maybeCount() {
        if (counted) return
        val player = mediaSession?.player ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (playedMs >= PlayCounts.thresholdMs(player.duration)) {
            playCounts.increment(id)
            counted = true
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            // handleAudioFocus = true: пауза на звонок, приглушение под навигатор и т.п.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            // Пауза, когда отключаются наушники
            .setHandleAudioBecomingNoisy(true)
            // Не даём процессору уснуть посреди трека при выключенном экране
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        playCounts = PlayCounts(this)
        player.addListener(countingListener)

        // Тап по уведомлению открывает приложение
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Приложение смахнули из недавних: если музыка не играет — выключаемся. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(listenTick)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Когда UI передаёт треки в сессию, URI файла может потеряться при упаковке.
     * Поэтому кладём его в requestMetadata.mediaUri и восстанавливаем здесь.
     */
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
