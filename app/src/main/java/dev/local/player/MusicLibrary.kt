package dev.local.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import java.text.Collator
import java.util.Locale

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    /** Название из файла до переименования в приложении (для поиска и сброса). */
    val originalTitle: String = title,
    /** Номер в альбоме (диск * 1000 + трек) — для сортировки общего списка. */
    val trackNo: Int = 0,
    /** Обложка с сервера; null — трек с телефона. */
    val remoteCoverUrl: String? = null,
) {
    val renamed: Boolean get() = title != originalTitle

    /** Трек с сервера Navidrome (id отрицательные, см. NavidromeClient.localId). */
    val isRemote: Boolean get() = id < 0

    val artworkUri: Uri
        get() = remoteCoverUrl?.let { Uri.parse(it) }
            ?: ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

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
 * Библиотека: треки с телефона (MediaStore) и/или с сервера Navidrome —
 * смотря что выбрано в настройках. Тяжёлая работа — не на главном потоке.
 */
object MusicLibrary {

    /** Ошибка сервера при последней загрузке (null — всё хорошо или сервер не используется). */
    @Volatile
    var serverError: String? = null
        private set

    private val collator: Collator = Collator.getInstance(Locale("ru")).apply {
        strength = Collator.SECONDARY
    }

    fun load(context: Context, edits: TrackEdits? = null): List<Track> {
        val settings = ServerSettings(context)
        val source = settings.source
        val local = if (source.usesPhone && hasAudioPermission(context)) loadLocal(context, edits)
            else emptyList()

        serverError = null
        if (!source.usesServer) return local
        val client = settings.client() ?: run {
            serverError = "Сервер не подключён"
            return local
        }
        val remote = try {
            applyEdits(client.songs(), edits)
        } catch (e: ServerException) {
            serverError = e.message
            emptyList()
        }
        // Общий список: исполнитель → альбом → номер трека, как у локальной медиатеки
        return sortByArtist(local + remote)
    }

    private fun sortByArtist(tracks: List<Track>): List<Track> = tracks.sortedWith(
        Comparator<Track> { a, b -> collator.compare(a.artist, b.artist) }
            .then { a, b -> collator.compare(a.album, b.album) }
            .thenBy { it.trackNo }
    )

    private fun applyEdits(tracks: List<Track>, edits: TrackEdits?): List<Track> {
        if (edits == null) return tracks
        val hidden = edits.hiddenIds()
        val overrides = edits.overrides()
        return tracks.filter { it.id !in hidden }
            .map { t -> overrides[t.id]?.let { t.copy(title = it) } ?: t }
    }

    fun hasAudioPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadLocal(context: Context, edits: TrackEdits?): List<Track> {
        val hidden = edits?.hiddenIds() ?: emptySet()
        val overrides = edits?.overrides() ?: emptyMap()
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
            MediaStore.Audio.Media.TRACK,
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
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    if (id in hidden) continue // «удалённые» из приложения не показываем
                    val original = c.getString(titleCol).orUnknown("Без названия")
                    tracks += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        title = overrides[id] ?: original,
                        artist = c.getString(artistCol).orUnknown("Неизвестный исполнитель"),
                        album = c.getString(albumCol).orUnknown("Неизвестный альбом"),
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                        originalTitle = original,
                        trackNo = c.getInt(trackCol),
                    )
                }
            }
        return tracks
    }

    private fun String?.orUnknown(fallback: String): String =
        if (this.isNullOrBlank() || this == "<unknown>") fallback else this
}
