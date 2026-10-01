package dev.local.player

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Контейнер, который можно стянуть вниз пальцем. Перехватывает только явные
 * вертикальные движения вниз — горизонтальные (ползунок, свайп по обложке) не трогает.
 */
class DragDismissLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Отпустили достаточно низко / резко — закрыть. */
    var onDismiss: (() -> Unit)? = null

    /** Вернуть на место (отпустили слишком рано). */
    var onSettle: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (dy > slop * 2 && dy > abs(dx) * 1.5f) {
                    dragging = true
                    downY = ev.rawY
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Касание пустого места (не кнопки) — тоже можно тянуть
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (!dragging) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dy > slop * 2 && dy > abs(dx) * 1.5f) {
                        dragging = true
                        downY = ev.rawY
                    }
                }
                if (dragging) translationY = (ev.rawY - downY).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(ev)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    if (translationY > height * 0.22f || vy > 1800f) onDismiss?.invoke()
                    else onSettle?.invoke()
                }
                dragging = false
            }
        }
        return true
    }
}
