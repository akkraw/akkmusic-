package dev.local.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.View
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Пользовательский фон. Выбранная картинка копируется во внутреннюю память
 * (чтобы не зависеть от галереи) вместе с заранее размытой маленькой версией —
 * её используют панели как «матовое стекло».
 */
object Backdrop {

    private const val MAX_SIDE = 1920
    private const val BLUR_WIDTH = 160

    private fun sharpFile(ctx: Context) = File(ctx.filesDir, "bg.jpg")
    private fun blurFile(ctx: Context) = File(ctx.filesDir, "bg_blur.png")

    fun has(ctx: Context): Boolean = sharpFile(ctx).exists() && blurFile(ctx).exists()

    // Раскодированный фон живёт, пока жив процесс: при пересоздании экрана
    // (смена цвета, поворот) картинку не читаем с диска заново.
    @Volatile private var sharpCache: Bitmap? = null
    @Volatile private var blurCache: Bitmap? = null

    /** Маленькая размытая версия (160 px) — раскодируется мгновенно, можно на главном потоке. */
    fun loadBlur(ctx: Context): Bitmap? {
        if (!has(ctx)) return null
        blurCache?.let { return it }
        return BitmapFactory.decodeFile(blurFile(ctx).path)?.also { blurCache = it }
    }

    /** Экран закрыт насовсем — память под фон больше не держим (музыка может играть дальше). */
    fun releaseMemory() {
        sharpCache = null
        blurCache = null
    }

    /** Чёткая картинка, если она уже раскодирована. */
    fun cachedSharp(): Bitmap? = sharpCache

    /** Большая картинка — тяжёлая, вызывать не на главном потоке. */
    fun loadSharp(ctx: Context): Bitmap? {
        if (!has(ctx)) return null
        sharpCache?.let { return it }
        return BitmapFactory.decodeFile(sharpFile(ctx).path)?.also { sharpCache = it }
    }

    /** Тяжёлая работа — вызывать не на главном потоке. */
    fun import(ctx: Context, uri: Uri): Boolean = try {
        val src = decode(ctx, uri)
        if (src == null) false else {
            val blurred = blur(src)
            writeAtomically(sharpFile(ctx)) { src.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            writeAtomically(blurFile(ctx)) { blurred.compress(Bitmap.CompressFormat.PNG, 100, it) }
            sharpCache = src
            blurCache = blurred
            true
        }
    } catch (e: Exception) {
        false
    }

    fun remove(ctx: Context) {
        sharpFile(ctx).delete()
        blurFile(ctx).delete()
        sharpCache = null
        blurCache = null
    }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use(write)
        tmp.renameTo(target)
    }

    /** Открывает картинку из галереи, уменьшая до [maxSide] по большей стороне. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder сам учитывает поворот фото (EXIF)
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, maxSide.toFloat() / max(w, h))
                decoder.setTargetSize(
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } ?: return null

        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Уменьшаем до 160 px и трижды размываем — при растягивании получается мягкое пятно. */
    private fun blur(src: Bitmap): Bitmap {
        val w = BLUR_WIDTH
        val h = max(1, (src.height * w / src.width.toFloat()).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(px, w, h, 3, horizontal = true)
            boxBlur(px, w, h, 3, horizontal = false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    /**
     * Мягкое цветное «свечение» для тени под обложкой: картинка уменьшается до 64 px,
     * кладётся в центр прозрачного холста 112×112 и сильно размывается — края уходят в ноль.
     * Холст в 1.75 раза больше содержимого.
     */
    fun glow(src: Bitmap): Bitmap {
        val inner = 64
        val pad = 24
        val side = inner + pad * 2
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            Bitmap.createScaledBitmap(src, inner, inner, true), pad.toFloat(), pad.toFloat(), null
        )
        val px = IntArray(side * side)
        out.getPixels(px, 0, side, 0, 0, side, side)
        repeat(3) {
            boxBlur(px, side, side, 7, horizontal = true)
            boxBlur(px, side, side, 7, horizontal = false)
        }
        out.setPixels(px, 0, side, 0, 0, side, side)
        return out
    }

    /** Размытие на весь экран для фона большого плеера. */
    fun blurForBackdrop(src: Bitmap): Bitmap = blur(src)

    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val out = IntArray(len)
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = p[idx(i)]
                sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (i in 0 until len) {
                out[i] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = p[idx(i + r + 1)]
                val rem = p[idx(i - r)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
            for (i in 0 until len) p[idx(i)] = out[i]
        }
    }
}

/**
 * Фон панели. Два режима:
 *  - с картинкой: кусок размытой картинки ровно под панелью + затемнение (матовое стекло);
 *  - без картинки: просто сплошной цвет.
 * В скруглённом стиле обрезается по скруглённому прямоугольнику и получает тонкую обводку.
 */
class PanelDrawable(
    private val blurred: Bitmap?,
    private val root: View,
    private val host: View,
) : Drawable() {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // Картинка рисуется шейдером сразу в скруглённый прямоугольник: без обрезки холста
    // по контуру (clipPath), которая на каждом кадре обходится видеокарте дороже.
    private val shader = blurred?.let {
        android.graphics.BitmapShader(
            it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
    }
    private var lastDx = Float.NaN
    private var lastDy = Float.NaN
    private var lastRw = 0
    private var lastRh = 0
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val hostLoc = IntArray(2)
    private val rootLoc = IntArray(2)
    private val rect = android.graphics.RectF()

    /**
     * Вызывается после раскладки экрана: перерисовываем панель, только если она
     * действительно сдвинулась относительно фона (а не на каждое изменение любого текста).
     */
    fun invalidateIfMoved() {
        if (shader == null) return
        host.getLocationInWindow(hostLoc)
        root.getLocationInWindow(rootLoc)
        val dx = hostLoc[0] - rootLoc[0] - host.translationX
        val dy = hostLoc[1] - rootLoc[1] - host.translationY
        if (dx != lastDx || dy != lastDy || root.width != lastRw || root.height != lastRh) {
            invalidateSelf()
        }
    }

    var radius = 0f
        set(v) { field = v; invalidateSelf() }

    /** Цвет поверх размытой картинки или сплошной цвет, если картинки нет. */
    fun setFill(color: Int) {
        fillPaint.color = color
        invalidateSelf()
    }

    fun setStroke(color: Int, widthPx: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = widthPx
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)

        val bmp = blurred
        val rw = root.width.toFloat()
        val rh = root.height.toFloat()
        if (bmp != null && shader != null && rw > 0f && rh > 0f) {
            host.getLocationInWindow(hostLoc)
            root.getLocationInWindow(rootLoc)
            // Тот же centerCrop, что у картинки на весь экран, сдвинутый к позиции панели.
            // Сдвиг самой панели (translation) не учитываем — фон под ней неподвижен.
            val dx = hostLoc[0] - rootLoc[0] - host.translationX
            val dy = hostLoc[1] - rootLoc[1] - host.translationY
            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            val scale = max(rw / bw, rh / bh)
            matrix.setScale(scale, scale)
            matrix.postTranslate((rw - bw * scale) / 2f - dx, (rh - bh * scale) / 2f - dy)
            shader.setLocalMatrix(matrix)
            bitmapPaint.shader = shader
            canvas.drawRoundRect(rect, radius, radius, bitmapPaint)
            lastDx = dx
            lastDy = dy
            lastRw = root.width
            lastRh = root.height
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (strokePaint.strokeWidth > 0f && strokePaint.alpha > 0) {
            val h = strokePaint.strokeWidth / 2f
            rect.inset(h, h)
            canvas.drawRoundRect(rect, max(0f, radius - h), max(0f, radius - h), strokePaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
