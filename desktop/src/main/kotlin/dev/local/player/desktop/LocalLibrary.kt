package dev.local.player.desktop

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Чтение музыки из выбранной папки: теги через jaudiotagger, рекурсивно. */
object LocalLibrary {

    private val EXTENSIONS = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma")

    init {
        // jaudiotagger очень шумит в консоль — приглушаем
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun scan(folder: String?): List<Track> {
        val root = folder?.let { File(it) } ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val tracks = mutableListOf<Track>()
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .forEach { f -> read(f)?.let { tracks += it } }
        return tracks.sortedWith(
            compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() })
        )
    }

    private fun read(f: File): Track? {
        val id = idFromPath(f.absolutePath)
        return try {
            val audio = AudioFileIO.read(f)
            val tag = audio.tag
            val header = audio.audioHeader
            fun field(key: FieldKey): String? =
                try { tag?.getFirst(key)?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }

            Track(
                id = id,
                title = field(FieldKey.TITLE) ?: f.nameWithoutExtension,
                artist = field(FieldKey.ARTIST) ?: "Неизвестный исполнитель",
                album = field(FieldKey.ALBUM) ?: "Неизвестный альбом",
                durationMs = (header?.trackLength ?: 0) * 1000L,
                trackNo = field(FieldKey.TRACK)?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
                remote = false,
                sourcePath = f.absolutePath,
            )
        } catch (e: Exception) {
            // Файл без читаемых тегов — всё равно показываем по имени
            Track(
                id = id,
                title = f.nameWithoutExtension,
                artist = "Неизвестный исполнитель",
                album = f.parentFile?.name ?: "Неизвестный альбом",
                durationMs = 0,
                remote = false,
                sourcePath = f.absolutePath,
            )
        }
    }

    /** Встроенная обложка файла в виде байтов, или null. */
    fun coverBytes(path: String): ByteArray? = try {
        AudioFileIO.read(File(path)).tag?.firstArtwork?.binaryData
    } catch (e: Exception) {
        null
    }

    /** Положительный стабильный id из пути (не пересечётся с серверными — те отрицательные). */
    private fun idFromPath(path: String): Long {
        var h = 1125899906842597L
        for (c in path) h = 31 * h + c.code
        return h and Long.MAX_VALUE
    }
}
