package dev.local.player.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.io.File

/** Шрифты, встроенные в ресурсы. Грузим через временные файлы — надёжно и в собранном приложении. */
object Fonts {

    data class Choice(val key: String, val label: String)

    val choices = listOf(
        Choice("system", "Системный"),
        Choice("mono", "JetBrains Mono"),
    )

    val mono: FontFamily? by lazy { loadMono() }

    /** Семейство для выбранного ключа; null — системный шрифт по умолчанию. */
    fun family(key: String?): FontFamily? = when (key) {
        "mono" -> mono
        else -> null
    }

    /** Моноширинный с запасным вариантом, для заголовка «akkmusic_». */
    fun monoOrFallback(): FontFamily = mono ?: FontFamily.Monospace

    private fun loadMono(): FontFamily? = try {
        FontFamily(
            Font(resFile("fonts/JetBrainsMono-Regular.ttf"), FontWeight.Normal),
            Font(resFile("fonts/JetBrainsMono-Medium.ttf"), FontWeight.Medium),
            Font(resFile("fonts/JetBrainsMono-Bold.ttf"), FontWeight.Bold),
        )
    } catch (e: Throwable) {
        null
    }

    private fun resFile(path: String): File {
        val ext = path.substringAfterLast('.', "ttf")
        val tmp = File.createTempFile("akk-font-", ".$ext").apply { deleteOnExit() }
        (Fonts::class.java.getResourceAsStream("/$path")
            ?: error("нет ресурса $path")).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        return tmp
    }
}
