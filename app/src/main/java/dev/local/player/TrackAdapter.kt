package dev.local.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
    private var currentId: String? = null
    private var playCounts: Map<Long, Int> = emptyMap()
    private var showHandles = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Track>, dragHandles: Boolean = false) {
        tracks = list
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

    /** Обновляет числа прослушиваний, не меняя порядок списка. */
    @SuppressLint("NotifyDataSetChanged")
    fun setPlayCounts(counts: Map<Long, Int>) {
        playCounts = counts
        notifyDataSetChanged()
    }

    fun setCurrent(mediaId: String?) {
        if (mediaId == currentId) return
        val old = tracks.indexOfFirst { it.id.toString() == currentId }
        val new = tracks.indexOfFirst { it.id.toString() == mediaId }
        currentId = mediaId
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    override fun getItemCount() = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return Holder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = tracks[position]
        holder.title.text = t.title
        holder.subtitle.text = "${t.artist} · ${t.album}"
        holder.duration.text = formatTime(t.durationMs)
        holder.plays.text = (playCounts[t.id] ?: 0).toString()
        holder.itemView.isActivated = t.id.toString() == currentId

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongClick(pos)
            true
        }

        holder.handle.visibility = if (showHandles) View.VISIBLE else View.GONE
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val plays: TextView = view.findViewById(R.id.trackPlays)
        val handle: ImageView = view.findViewById(R.id.dragHandle)
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
