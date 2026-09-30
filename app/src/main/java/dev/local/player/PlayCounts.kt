package dev.local.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Счётчик прослушиваний по id трека из MediaStore.
 * Пишет сервис воспроизведения, читает экран со списком.
 */
class PlayCounts(context: Context) {

    val prefs: SharedPreferences =
        context.getSharedPreferences("play_counts", Context.MODE_PRIVATE)

    fun get(trackId: Long): Int = prefs.getInt(trackId.toString(), 0)

    fun all(): Map<Long, Int> = prefs.all.mapNotNull { (k, v) ->
        val id = k.toLongOrNull() ?: return@mapNotNull null
        (v as? Int)?.let { id to it }
    }.toMap()

    fun increment(trackId: Long) {
        prefs.edit().putInt(trackId.toString(), get(trackId) + 1).apply()
    }

    companion object {
        /** Прослушивание засчитывается после 30 секунд (или половины, если трек короче минуты). */
        fun thresholdMs(durationMs: Long): Long =
            if (durationMs in 1 until 60_000) durationMs / 2 else 30_000
    }
}
