package dev.local.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.Holder>() {

    private var items: List<Playlist> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Playlist>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        val res = holder.itemView.resources
        holder.name.text = p.name
        holder.count.text =
            res.getQuantityString(R.plurals.track_count, p.trackIds.size, p.trackIds.size)
        bindCover(holder.cover, p.id)
        holder.itemView.setOnClickListener { onClick(p) }
        holder.itemView.setOnLongClickListener { onLongClick(p); true }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
        val count: TextView = view.findViewById(R.id.playlistCount)
        val cover: ImageView = view.findViewById(R.id.playlistCover)
    }
}

/** Обложка плейлиста со скруглёнными краями или значок по умолчанию. */
fun bindCover(view: ImageView, playlistId: Long) {
    val bmp = PlaylistCovers.thumb(view.context, playlistId)
    view.clipToOutline = true
    if (bmp != null) {
        androidx.core.widget.ImageViewCompat.setImageTintList(view, null)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    } else {
        androidx.core.widget.ImageViewCompat.setImageTintList(
            view,
            android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(view, R.attr.akkAccent)
            )
        )
        view.scaleType = ImageView.ScaleType.CENTER
        view.setImageResource(R.drawable.ic_playlist)
    }
}
