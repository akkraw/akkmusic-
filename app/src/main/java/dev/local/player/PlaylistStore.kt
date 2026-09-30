package dev.local.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
)

/**
 * Плейлисты хранятся в JSON-файле во внутренней памяти приложения.
 * Треки запоминаются по id из MediaStore: если файл удалят с телефона,
 * он просто пропадёт из плейлиста при показе.
 */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")
    private val playlists: MutableList<Playlist> = load()

    fun all(): List<Playlist> = playlists.toList()

    fun get(id: Long): Playlist? = playlists.find { it.id == id }

    fun create(name: String): Playlist {
        val nextId = (playlists.maxOfOrNull { it.id } ?: 0L) + 1
        val p = Playlist(nextId, name, emptyList())
        playlists += p
        save()
        return p
    }

    fun rename(id: Long, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: Long) {
        playlists.removeAll { it.id == id }
        save()
    }

    /** Добавляет трек в конец. Возвращает false, если он там уже есть. */
    fun addTrack(id: Long, trackId: Long): Boolean {
        val p = get(id) ?: return false
        if (trackId in p.trackIds) return false
        update(id) { it.copy(trackIds = it.trackIds + trackId) }
        return true
    }

    fun removeTrack(id: Long, trackId: Long) =
        update(id) { it.copy(trackIds = it.trackIds - trackId) }

    /** Сохраняет новый порядок треков (после перетаскивания). */
    fun setTracks(id: Long, trackIds: List<Long>) = update(id) { it.copy(trackIds = trackIds) }

    private fun update(id: Long, change: (Playlist) -> Playlist) {
        val i = playlists.indexOfFirst { it.id == id }
        if (i < 0) return
        playlists[i] = change(playlists[i])
        save()
    }

    private fun load(): MutableList<Playlist> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("tracks")
                Playlist(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    trackIds = List(ids.length()) { j -> ids.getLong(j) },
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save() {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", JSONArray(p.trackIds))
            )
        }
        // Пишем во временный файл и подменяем: если процесс убьют посреди записи,
        // старые плейлисты не превратятся в битый файл.
        val tmp = File(file.parentFile, "playlists.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
