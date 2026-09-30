package dev.local.player

import android.app.PendingIntent
import android.content.Intent
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
