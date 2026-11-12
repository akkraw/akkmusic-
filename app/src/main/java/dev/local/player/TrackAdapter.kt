package dev.local.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * Список треков. Используется и для всей библиотеки, и для содержимого плейлиста
 * (там включаются «ручки» для перетаскивания).
 */
class TrackAdapter(
    private val onClick: (position: Int) -> Unit,
    private val onLongClick: (position: Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    var tracks: List<Track> = emptyList()
        private set
    private var currentId: Long? = null
    private var playCounts: Map<Long, Int> = emptyMap()
    private var showHandles = false
    private var rounded = true

    /** Скруглённая плашка с отступами или прямая полоса во всю ширину. */
    @SuppressLint("NotifyDataSetChanged")
    fun setRounded(value: Boolean) {
        if (rounded == value) return
        rounded = value
        notifyDataSetChanged()
    }

    /** Новый список; [counts] — заодно обновить числа прослушиваний одной перерисовкой. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Track>, counts: Map<Long, Int>? = null, dragHandles: Boolean = false) {
        tracks = list
        if (counts != null) playCounts = counts
        showHandles = dragHandles
        notifyDataSetChanged()
    }

    /** Перестановка во время перетаскивания (сохранение делает вызывающий код). */
    fun move(from: Int, to: Int) {
        val list = tracks.toMutableList()
        list.add(to, list.removeAt(from))
        tracks = list
        notifyItemMoved(from, to)
    }

    /**
     * Обновляет числа прослушиваний, не меняя порядок списка.
     * Перерисовываются только строки, у которых число действительно изменилось.
     */
    fun setPlayCounts(counts: Map<Long, Int>) {
        val old = playCounts
        if (old === counts) return
        playCounts = counts
        for (i in tracks.indices) {
            val id = tracks[i].id
            if ((old[id] ?: 0) != (counts[id] ?: 0)) notifyItemChanged(i, PAYLOAD_PLAYS)
        }
    }

    fun setCurrent(mediaId: String?) {
        val id = mediaId?.toLongOrNull()
        if (id == currentId) return
        val oldId = currentId
        currentId = id
        // Строку не пересоздаём — только меняем подсветку
        if (oldId != null) indexOf(oldId).let { if (it >= 0) notifyItemChanged(it, PAYLOAD_CURRENT) }
        if (id != null) indexOf(id).let { if (it >= 0) notifyItemChanged(it, PAYLOAD_CURRENT) }
    }

    private fun indexOf(id: Long): Int {
        for (i in tracks.indices) if (tracks[i].id == id) return i
        return -1
    }

    override fun getItemCount() = tracks.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        val holder = Holder(view)
        // Обработчики вешаем один раз на строку, а не при каждой прокрутке
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        view.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
            true
        }
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = tracks[position]
        holder.title.text = t.title
        holder.subtitle.text = t.subtitle
        holder.duration.text = formatTime(t.durationMs)
        bindPlays(holder, t)
        bindCurrent(holder, t)
        // Фон с отступами (inset) подменяет собственные отступы строки —
        // поэтому после смены фона возвращаем исходные отступы из разметки
        if (holder.rounded != rounded) {
            holder.rounded = rounded
            holder.itemView.setBackgroundResource(
                if (rounded) R.drawable.item_bg_rounded else R.drawable.item_bg
            )
            holder.itemView.setPadding(holder.padL, holder.padT, holder.padR, holder.padB)
        }
        val handle = if (showHandles) View.VISIBLE else View.GONE
        if (holder.handle.visibility != handle) holder.handle.visibility = handle
    }

    /** Частичное обновление: меняется только счётчик или подсветка, остальное не трогаем. */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val t = tracks[position]
        if (PAYLOAD_PLAYS in payloads) bindPlays(holder, t)
        if (PAYLOAD_CURRENT in payloads) bindCurrent(holder, t)
    }

    private fun bindPlays(holder: Holder, t: Track) {
        val n = playCounts[t.id] ?: 0
        if (holder.shownPlays != n) {
            holder.shownPlays = n
            holder.plays.text = n.toString()
        }
    }

    private fun bindCurrent(holder: Holder, t: Track) {
        val playing = t.id == currentId
        holder.itemView.isActivated = playing
        holder.title.setTextColor(if (playing) holder.accentColor else holder.normalColor)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val plays: TextView = view.findViewById(R.id.trackPlays)
        val handle: ImageView = view.findViewById(R.id.dragHandle)

        // Цвета темы достаём один раз на строку, а не при каждой привязке
        val accentColor: Int = MaterialColors.getColor(title, R.attr.akkAccentText)
        val normalColor: Int =
            MaterialColors.getColor(title, com.google.android.material.R.attr.colorOnSurface)

        // Что сейчас показано — чтобы не делать лишнюю работу
        var rounded: Boolean? = null
        var shownPlays = -1

        // Отступы строки из item_track.xml, запомненные до того, как фон их перезапишет
        val padL = view.paddingLeft
        val padT = view.paddingTop
        val padR = view.paddingRight
        val padB = view.paddingBottom
    }

    private companion object {
        const val PAYLOAD_PLAYS = "plays"
        const val PAYLOAD_CURRENT = "current"
    }
}

/** Время «м:сс» / «ч:мм:сс». Собирается вручную: String.format заметно медленнее. */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val sb = StringBuilder(8)
    if (h > 0) {
        sb.append(h).append(':')
        if (m < 10) sb.append('0')
    }
    sb.append(m).append(':')
    if (s < 10) sb.append('0')
    sb.append(s)
    return sb.toString()
}
