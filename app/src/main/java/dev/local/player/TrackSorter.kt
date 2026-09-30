package dev.local.player

import java.text.Collator
import java.util.Locale

/** Варианты сортировки вкладки «Треки». */
enum class TrackSort {
    /** Исполнитель → альбом → номер трека (как было изначально) */
    ARTIST,
    /** По названию: сначала английские (A–Z), потом русские (А–Я) */
    TITLE_EN_FIRST,
    /** По названию: сначала русские (А–Я), потом английские (A–Z) */
    TITLE_RU_FIRST,
    /** Чаще всего слушаемые сверху */
    MOST_PLAYED;

    companion object {
        fun fromName(name: String?): TrackSort = entries.find { it.name == name } ?: ARTIST
    }
}

object TrackSorter {

    // Русская локаль правильно сравнивает и кириллицу (включая «ё»), и латиницу, без учёта регистра
    private val collator: Collator = Collator.getInstance(Locale("ru")).apply {
        strength = Collator.SECONDARY
    }

    private enum class Script { LATIN, CYRILLIC, OTHER }

    private fun scriptOf(title: String): Script {
        val c = title.firstOrNull { it.isLetterOrDigit() } ?: return Script.OTHER
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.LATIN -> Script.LATIN
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            else -> Script.OTHER
        }
    }

    /** Названия без первого «мусорного» символа: «(Intro)» и «"Кукушка"» сортируются по буквам. */
    private fun key(title: String): String = title.trimStart { !it.isLetterOrDigit() }

    fun sort(tracks: List<Track>, sort: TrackSort, playCounts: Map<Long, Int>): List<Track> {
        val byTitle = Comparator<Track> { a, b -> collator.compare(key(a.title), key(b.title)) }
        return when (sort) {
            // Библиотека уже приходит отсортированной по исполнителю/альбому
            TrackSort.ARTIST -> tracks

            TrackSort.TITLE_EN_FIRST -> tracks.sortedWith(
                compareBy<Track> {
                    when (scriptOf(it.title)) {
                        Script.LATIN -> 0; Script.CYRILLIC -> 1; Script.OTHER -> 2
                    }
                }.then(byTitle)
            )

            TrackSort.TITLE_RU_FIRST -> tracks.sortedWith(
                compareBy<Track> {
                    when (scriptOf(it.title)) {
                        Script.CYRILLIC -> 0; Script.LATIN -> 1; Script.OTHER -> 2
                    }
                }.then(byTitle)
            )

            TrackSort.MOST_PLAYED -> tracks.sortedWith(
                compareByDescending<Track> { playCounts[it.id] ?: 0 }.then(byTitle)
            )
        }
    }
}
