package dev.local.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
) {
    val artworkUri: Uri
        get() = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .build()
        )
        .build()

    companion object {
        private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

/**
 * Библиотека берётся из MediaStore — системного индекса медиафайлов.
 * Сам диск не сканируем: это медленно и упирается в ограничения scoped storage.
 */
object MusicLibrary {

    fun load(context: Context): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, " +
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, " +
            MediaStore.Audio.Media.TRACK

        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    tracks += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        title = c.getString(titleCol).orUnknown("Без названия"),
                        artist = c.getString(artistCol).orUnknown("Неизвестный исполнитель"),
                        album = c.getString(albumCol).orUnknown("Неизвестный альбом"),
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                    )
                }
            }
        return tracks
    }

    private fun String?.orUnknown(fallback: String): String =
        if (this.isNullOrBlank() || this == "<unknown>") fallback else this
}
