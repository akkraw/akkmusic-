package dev.local.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val onClick: (position: Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var tracks: List<Track> = emptyList()
    private var currentId: String? = null

    fun submit(list: List<Track>) {
        tracks = list
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

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = tracks[position]
        holder.title.text = t.title
        holder.subtitle.text = "${t.artist} · ${t.album}"
        holder.duration.text = formatTime(t.durationMs)
        holder.itemView.isActivated = t.id.toString() == currentId
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        val duration: TextView = view.findViewById(R.id.trackDuration)
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
