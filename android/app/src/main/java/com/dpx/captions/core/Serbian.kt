package com.dpx.captions.core

/**
 * Whisper's Serbian output comes back in either script, depending on what the training data looked
 * like. The Cyrillic -> Latin mapping is 1:1 and lossless, so everything is normalised to Latin
 * after the fact instead of trusting the model to pick a script.
 */
object Serbian {
    private val digraphs = mapOf(
        'Љ' to "Lj", 'љ' to "lj",
        'Њ' to "Nj", 'њ' to "nj",
        'Џ' to "Dž", 'џ' to "dž",
    )

    private val singles = mapOf(
        'А' to "A", 'а' to "a", 'Б' to "B", 'б' to "b", 'В' to "V", 'в' to "v",
        'Г' to "G", 'г' to "g", 'Д' to "D", 'д' to "d", 'Ђ' to "Đ", 'ђ' to "đ",
        'Е' to "E", 'е' to "e", 'Ж' to "Ž", 'ж' to "ž", 'З' to "Z", 'з' to "z",
        'И' to "I", 'и' to "i", 'Ј' to "J", 'ј' to "j", 'К' to "K", 'к' to "k",
        'Л' to "L", 'л' to "l", 'М' to "M", 'м' to "m", 'Н' to "N", 'н' to "n",
        'О' to "O", 'о' to "o", 'П' to "P", 'п' to "p", 'Р' to "R", 'р' to "r",
        'С' to "S", 'с' to "s", 'Т' to "T", 'т' to "t", 'Ћ' to "Ć", 'ћ' to "ć",
        'У' to "U", 'у' to "u", 'Ф' to "F", 'ф' to "f", 'Х' to "H", 'х' to "h",
        'Ц' to "C", 'ц' to "c", 'Ч' to "Č", 'ч' to "č", 'Ш' to "Š", 'ш' to "š",
    )

    fun isCyrillic(text: String): Boolean = text.any { it.code in 0x0400..0x04FF }

    fun cyrillicToLatin(text: String): String {
        if (!isCyrillic(text)) return text
        val out = StringBuilder(text.length + 4)
        for (ch in text) {
            out.append(digraphs[ch] ?: singles[ch] ?: ch.toString())
        }
        return out.toString()
    }
}
