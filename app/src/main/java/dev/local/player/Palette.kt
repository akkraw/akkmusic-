package dev.local.player

/**
 * Палитры: цвет акцента (все цветные элементы) и цвет текста.
 * Применяются наложением темы при создании экрана. Ресурсы сгенерированы:
 * res/values/palette.xml, values-night/palette.xml, values/palette_styles.xml.
 */
object Palette {

    data class Swatch(val key: String, val label: String, val colorRes: Int, val styleRes: Int)

    val accents = listOf(
        Swatch("sky", "Небесный", R.color.acc_sky, R.style.ThemeOverlay_AkkMusic_Accent_sky),
        Swatch("mint", "Мятный", R.color.acc_mint, R.style.ThemeOverlay_AkkMusic_Accent_mint),
        Swatch("lime", "Лаймовый", R.color.acc_lime, R.style.ThemeOverlay_AkkMusic_Accent_lime),
        Swatch("sun", "Солнечный", R.color.acc_sun, R.style.ThemeOverlay_AkkMusic_Accent_sun),
        Swatch("peach", "Персиковый", R.color.acc_peach, R.style.ThemeOverlay_AkkMusic_Accent_peach),
        Swatch("coral", "Коралловый", R.color.acc_coral, R.style.ThemeOverlay_AkkMusic_Accent_coral),
        Swatch("rose", "Розовый", R.color.acc_rose, R.style.ThemeOverlay_AkkMusic_Accent_rose),
        Swatch("lilac", "Сиреневый", R.color.acc_lilac, R.style.ThemeOverlay_AkkMusic_Accent_lilac),
        Swatch("lavender", "Лавандовый", R.color.acc_lavender, R.style.ThemeOverlay_AkkMusic_Accent_lavender),
        Swatch("silver", "Серебро", R.color.acc_silver, R.style.ThemeOverlay_AkkMusic_Accent_silver),
    )

    val texts = listOf(
        Swatch("white", "Белый", R.color.txt_white, R.style.ThemeOverlay_AkkMusic_Text_white),
        Swatch("warm", "Тёплый", R.color.txt_warm, R.style.ThemeOverlay_AkkMusic_Text_warm),
        Swatch("gray", "Серый", R.color.txt_gray, R.style.ThemeOverlay_AkkMusic_Text_gray),
        Swatch("blue", "Голубой", R.color.txt_blue, R.style.ThemeOverlay_AkkMusic_Text_blue),
        Swatch("green", "Терминал", R.color.txt_green, R.style.ThemeOverlay_AkkMusic_Text_green),
        Swatch("amber", "Янтарь", R.color.txt_amber, R.style.ThemeOverlay_AkkMusic_Text_amber),
        Swatch("pink", "Розовый", R.color.txt_pink, R.style.ThemeOverlay_AkkMusic_Text_pink),
    )

    fun accent(key: String?): Swatch = accents.find { it.key == key } ?: accents[0]
    fun text(key: String?): Swatch = texts.find { it.key == key } ?: texts[0]
}
