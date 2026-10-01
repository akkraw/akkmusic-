package dev.local.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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

    // ---------- Таймер сна ----------
    private val sleepFire = Runnable { startSleepFade() }
    private var fadeStep = 0

    private val fadeTick = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            fadeStep++
            if (fadeStep >= FADE_STEPS) {
                player.pause()
                player.volume = 1f
                clearSleep()
            } else {
                player.volume = 1f - fadeStep.toFloat() / FADE_STEPS
                handler.postDelayed(this, FADE_MS / FADE_STEPS)
            }
        }
    }

    /** minutes > 0 — через столько минут; 0 — в конце текущего трека; < 0 — выключить. */
    private fun setSleep(minutes: Int) {
        val player = mediaSession?.player ?: return
        handler.removeCallbacks(sleepFire)
        handler.removeCallbacks(fadeTick)
        player.volume = 1f
        (player as? ExoPlayer)?.pauseAtEndOfMediaItems = false
        val prefs = getSharedPreferences(SLEEP_PREFS, Context.MODE_PRIVATE).edit()
        when {
            minutes > 0 -> {
                val delay = minutes * 60_000L
                // затухание начинается за FADE_MS до конца
                handler.postDelayed(sleepFire, (delay - FADE_MS).coerceAtLeast(0))
                prefs.putLong(KEY_SLEEP_AT, System.currentTimeMillis() + delay)
            }
            minutes == 0 -> {
                (player as? ExoPlayer)?.pauseAtEndOfMediaItems = true
                prefs.putLong(KEY_SLEEP_AT, SLEEP_END_OF_TRACK)
            }
            else -> prefs.remove(KEY_SLEEP_AT)
        }
        prefs.apply()
    }

    private fun startSleepFade() {
        fadeStep = 0
        handler.post(fadeTick)
    }

    private fun clearSleep() {
        (mediaSession?.player as? ExoPlayer)?.pauseAtEndOfMediaItems = false
        getSharedPreferences(SLEEP_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply()
    }

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

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                clearSleep()
            }
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
        handler.removeCallbacks(sleepFire)
        handler.removeCallbacks(fadeTick)
        getSharedPreferences(SLEEP_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply()
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
    private inner class SessionCallback : MediaSession.Callback {

        // Разрешаем приложению отправлять команду таймера сна
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SLEEP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) {
                setSleep(args.getInt(ARG_MINUTES, -1))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

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

    companion object {
        const val CMD_SLEEP = "dev.local.player.SLEEP"
        const val ARG_MINUTES = "minutes"
        const val SLEEP_PREFS = "sleep"
        /** Время остановки (мс) или SLEEP_END_OF_TRACK; отсутствует — таймер выключен. */
        const val KEY_SLEEP_AT = "sleep_at"
        const val SLEEP_END_OF_TRACK = -2L
        private const val FADE_MS = 10_000L
        private const val FADE_STEPS = 20
    }
}
