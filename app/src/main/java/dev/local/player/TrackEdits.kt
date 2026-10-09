package dev.local.player

import android.content.Context

/**
 * Правки треков уровня приложения, по id из MediaStore. Сами файлы не трогаются:
 *  - hidden — «удалённые» из приложения треки (их можно вернуть из библиотеки);
 *  - titles — переименования (переопределение названия).
 * Хранится в SharedPreferences, как счётчики прослушиваний.
 */
class TrackEdits(context: Context) {

    private val hiddenPrefs =
        context.getSharedPreferences("hidden_tracks", Context.MODE_PRIVATE)
    private val titlePrefs =
        context.getSharedPreferences("track_titles", Context.MODE_PRIVATE)

    // ---------- Скрытие («удаление» из приложения) ----------

    fun isHidden(id: Long): Boolean = hiddenPrefs.contains(id.toString())

    fun hiddenIds(): Set<Long> =
        hiddenPrefs.all.keys.mapNotNull { it.toLongOrNull() }.toSet()

    fun hiddenCount(): Int = hiddenPrefs.all.size

    /** Запоминаем и заголовок, чтобы показать его в списке «удалённых» без перезагрузки медиатеки. */
    fun hide(id: Long, label: String) {
        hiddenPrefs.edit().putString(id.toString(), label).apply()
    }

    fun restore(id: Long) {
        hiddenPrefs.edit().remove(id.toString()).apply()
    }

    fun restoreAll() {
        hiddenPrefs.edit().clear().apply()
    }

    /** id → подпись (исполнитель · название) для экрана восстановления. */
    fun hiddenLabels(): Map<Long, String> =
        hiddenPrefs.all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            id to (v as? String ?: "")
        }.toMap()

    // ---------- Переименование ----------

    fun titleOverride(id: Long): String? = titlePrefs.getString(id.toString(), null)

    fun overrides(): Map<Long, String> =
        titlePrefs.all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            (v as? String)?.let { id to it }
        }.toMap()

    /** Пустое или совпадающее с оригиналом имя убирает переопределение. */
    fun rename(id: Long, title: String, original: String) {
        val t = title.trim()
        val e = titlePrefs.edit()
        if (t.isEmpty() || t == original) e.remove(id.toString()) else e.putString(id.toString(), t)
        e.apply()
    }

    fun clearTitle(id: Long) {
        titlePrefs.edit().remove(id.toString()).apply()
    }
}
