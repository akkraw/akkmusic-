package dev.local.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * Обложка трека: встроенная в файл картинка или обложка альбома из системной медиатеки.
 * Тяжёлая работа — вызывать не на главном потоке.
 */
object CoverArt {

    fun load(ctx: Context, trackId: Long, albumId: Long?, size: Int = 800): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

        // Android 10+: система сама достаёт обложку из файла
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return ctx.contentResolver.loadThumbnail(uri, Size(size, size), null)
            } catch (_: Exception) {
            }
        }
        // Старый способ: обложка альбома
        if (albumId != null) {
            try {
                val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                ctx.contentResolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }
                    ?.let { return it }
            } catch (_: Exception) {
            }
        }
        // Последний шанс: картинка прямо из тегов файла
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
