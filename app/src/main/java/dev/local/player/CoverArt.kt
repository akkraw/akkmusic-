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

    /** Готовый набор для большого плеера: обложка, свечение под ней и размытый фон. */
    class Cover(val art: Bitmap, val glow: Bitmap, val back: Bitmap)

    // Недавние обложки в памяти: вернулся к треку или листаешь туда-сюда —
    // картинка появляется сразу, без чтения файла, скачивания и размытия.
    private val memory = object : android.util.LruCache<String, Cover>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cover): Int =
            value.art.byteCount + value.glow.byteCount + value.back.byteCount
    }

    fun cached(key: String): Cover? = memory.get(key)

    fun remember(key: String, cover: Cover) {
        memory.put(key, cover)
    }

    private const val MAX_DISK_COVERS = 300

    /**
     * Обложка с сервера: качаем один раз и кладём в кеш на диске —
     * дальше она открывается без сети.
     */
    fun loadUrl(ctx: Context, url: String, size: Int = 800): Bitmap? = try {
        val dir = java.io.File(ctx.cacheDir, "covers")
        val file = java.io.File(dir, md5(url) + ".img")
        val bytes = if (file.exists()) file.readBytes() else {
            val fresh = java.net.URL(url).openConnection().run {
                connectTimeout = 10_000
                readTimeout = 20_000
                getInputStream().use { it.readBytes() }
            }
            try {
                dir.mkdirs()
                val tmp = java.io.File(dir, file.name + ".tmp")
                tmp.writeBytes(fresh)
                tmp.renameTo(file)
                trimDisk(dir)
            } catch (_: Exception) {
            }
            fresh
        }
        decodeScaled(bytes, size) ?: run {
            file.delete() // сервер вернул не картинку — не храним
            null
        }
    } catch (_: Exception) {
        null
    }

    /** Кеш не растёт бесконечно: при переполнении удаляем самые старые файлы. */
    private fun trimDisk(dir: java.io.File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_DISK_COVERS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_COVERS * 2 / 3)
            .forEach { it.delete() }
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun decodeScaled(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

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
            r.embeddedPicture?.let { decodeScaled(it, size) }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
