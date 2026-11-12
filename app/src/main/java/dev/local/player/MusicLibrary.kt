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
import java.text.CollationKey
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

    /** Вторая строка в списке — собирается один раз, а не при каждой прокрутке. */
    val subtitle: String by lazy(LazyThreadSafetyMode.NONE) { "$artist · $album" }

    /**
     * Всё, по чему ищем, одной строкой: нижний регистр, «ё» = «е».
     * Считается один раз на трек, а не на каждую набранную букву.
     */
    val searchKey: String by lazy(LazyThreadSafetyMode.NONE) {
        val sb = StringBuilder(title.length + artist.length + album.length + 3)
        sb.append(title).append('\n')
        if (renamed) sb.append(originalTitle).append('\n')
        sb.append(artist).append('\n').append(album)
        sb.toString().lowercase().replace('ё', 'е')
    }

    /** Ключ сортировки по названию (см. TrackSorter) — тоже считается один раз. */
    val titleSortKey: CollationKey by lazy(LazyThreadSafetyMode.NONE) { TrackSorter.titleKey(title) }

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

    /**
     * [shown] — треки для общего списка по выбранному источнику,
     * [all] — всё, что можно сыграть: плейлисты показывают скачанные на телефон
     * треки всегда, даже в режиме «Сервер».
     */
    class Result(val shown: List<Track>, val all: List<Track>)

    // Списки «как есть» (без скрытия и переименований), чтобы после правки трека
    // не перечитывать медиатеку и не качать заново всю библиотеку сервера.
    private var rawLocal: List<Track>? = null
    private var rawRemote: List<Track>? = null
    private var rawRemoteOwner: String? = null // чей это список: адрес сервера + логин

    /**
     * [refresh] = false — пересобрать список из уже загруженного (после переименования,
     * скрытия или возврата трека): без запроса к MediaStore и к серверу.
     */
    @Synchronized
    fun load(
        context: Context,
        edits: TrackEdits? = null,
        refresh: Boolean = true,
        refreshLocal: Boolean = refresh,
    ): Result {
        val settings = ServerSettings(context)
        val source = settings.source
        val local = localTracks(context, refreshLocal)

        if (!source.usesServer) {
            serverError = null
            return applyEdits(local, edits).let { Result(it, it) }
        }
        val remote = rawRemote?.takeUnless { refresh } ?: fetchRemote(context, settings)
        return merged(source, local, remote, edits)
    }

    /**
     * Мгновенный список до ответа сервера: телефон + библиотека сервера с прошлого раза
     * (из памяти или с диска). null — показывать пока нечего, ждём [load].
     */
    @Synchronized
    fun loadCached(context: Context, edits: TrackEdits? = null): Result? {
        val settings = ServerSettings(context)
        val source = settings.source
        if (!source.usesServer) return null
        val client = settings.client() ?: return null
        val remote = rawRemote?.takeIf { serverError == null && rawRemoteOwner == ServerCache.owner(client) }
            ?: ServerCache.read(context, client)
            ?: return null
        return merged(source, localTracks(context, refresh = true), remote, edits)
    }

    private fun localTracks(context: Context, refresh: Boolean): List<Track> {
        if (!hasAudioPermission(context)) return emptyList()
        return rawLocal?.takeUnless { refresh } ?: loadLocal(context).also { rawLocal = it }
    }

    private fun fetchRemote(context: Context, settings: ServerSettings): List<Track> {
        val client = settings.client()
        val tracks: List<Track> = if (client == null) {
            serverError = "Сервер не подключён"
            emptyList()
        } else try {
            val songs = client.fetchSongs()
            serverError = null
            ServerCache.write(context, client, songs)
            songs.map(client::track)
        } catch (e: ServerException) {
            serverError = e.message
            emptyList()
        }
        rawRemote = tracks
        rawRemoteOwner = client?.let { ServerCache.owner(it) }
        return tracks
    }

    private fun merged(
        source: LibrarySource, local: List<Track>, remote: List<Track>, edits: TrackEdits?,
    ): Result {
        // Общий список: исполнитель → альбом → номер трека, как у локальной медиатеки
        val all = sortByArtist(applyEdits(local + remote, edits))
        return Result(if (source.usesPhone) all else all.filter { it.isRemote }, all)
    }

    /**
     * Ключи сравнения считаем один раз на строку (исполнителей и альбомов намного меньше,
     * чем треков), дальше сортировка сравнивает готовые байты — в разы быстрее,
     * чем Collator.compare на каждой паре.
     */
    private fun sortByArtist(tracks: List<Track>): List<Track> {
        class Row(val track: Track, val artist: CollationKey, val album: CollationKey)

        val keys = HashMap<String, CollationKey>()
        fun key(s: String) = keys.getOrPut(s) { collator.getCollationKey(s) }
        val rows = tracks.map { Row(it, key(it.artist), key(it.album)) }
        return rows.sortedWith(
            Comparator<Row> { a, b -> a.artist.compareTo(b.artist) }
                .then { a, b -> a.album.compareTo(b.album) }
                .thenBy { it.track.trackNo }
        ).map { it.track }
    }

    private fun applyEdits(tracks: List<Track>, edits: TrackEdits?): List<Track> {
        if (edits == null) return tracks
        val hidden = edits.hiddenIds()
        val overrides = edits.overrides()
        if (hidden.isEmpty() && overrides.isEmpty()) return tracks
        val out = ArrayList<Track>(tracks.size)
        for (t in tracks) {
            if (t.id in hidden) continue // «удалённые» из приложения не показываем
            val title = overrides[t.id]
            out += if (title != null && title != t.title) t.copy(title = title) else t
        }
        return out
    }

    fun hasAudioPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadLocal(context: Context): List<Track> {
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

        val tracks = ArrayList<Track>()
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
                    val original = c.getString(titleCol).orUnknown("Без названия")
                    tracks += Track(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        title = original,
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
