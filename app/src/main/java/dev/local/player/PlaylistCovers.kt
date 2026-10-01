package dev.local.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlin.math.min

/**
 * Обложки плейлистов: квадратная обрезка по центру, 512×512, JPEG во внутренней памяти.
 * Маленькие копии для списка кешируются в памяти.
 */
object PlaylistCovers {

    private const val SIZE = 512
    private val thumbs = HashMap<Long, Bitmap>()

    private fun file(ctx: Context, id: Long) = File(File(ctx.filesDir, "covers"), "$id.jpg")

    fun has(ctx: Context, id: Long): Boolean = file(ctx, id).exists()

    /** Тяжёлая работа — вызывать не на главном потоке. */
    fun save(ctx: Context, id: Long, uri: Uri): Boolean = try {
        val src = Backdrop.decode(ctx, uri, maxSide = SIZE * 2)
        if (src == null) false else {
            val side = min(src.width, src.height)
            val square = Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side
            )
            val out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            val f = file(ctx, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(f)
            synchronized(thumbs) { thumbs.remove(id) }
            true
        }
    } catch (e: Exception) {
        false
    }

    fun remove(ctx: Context, id: Long) {
        file(ctx, id).delete()
        synchronized(thumbs) { thumbs.remove(id) }
    }

    /** Уменьшенная копия (256 px) для списка и шапки, или null. */
    fun thumb(ctx: Context, id: Long): Bitmap? {
        synchronized(thumbs) { thumbs[id]?.let { return it } }
        val f = file(ctx, id)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            ?: return null
        synchronized(thumbs) { thumbs[id] = bmp }
        return bmp
    }
}
