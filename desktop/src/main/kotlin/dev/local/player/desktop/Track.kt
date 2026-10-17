package dev.local.player.desktop

/**
 * Трек: либо локальный файл (sourcePath), либо поток с сервера (streamUrl).
 * id стабильный: для файла — из пути, для сервера — из его строкового id.
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int = 0,
    val remote: Boolean = false,
    /** Абсолютный путь к файлу (локальный трек) или null. */
    val sourcePath: String? = null,
    /** HTTP-ссылка на поток (серверный трек) или null. */
    val streamUrl: String? = null,
    /** HTTP-ссылка на обложку (серверный трек) или null. */
    val coverUrl: String? = null,
) {
    /** Что отдать движку воспроизведения: путь к файлу или ссылку на поток. */
    val mrl: String get() = sourcePath ?: streamUrl ?: ""
}
