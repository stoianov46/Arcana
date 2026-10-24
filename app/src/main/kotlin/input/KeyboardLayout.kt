package com.arcana.brain.app.input

/**
 * Cyrillic layouts (Ukrainian and Russian ЙЦУКЕН) put different letters on the keys that the app's shortcuts use, so
 * with such a layout active `n` would be `т`. Mapping each Cyrillic letter back to the QWERTY key in the same position
 * lets the shortcuts work without switching layout; text fields still receive the Cyrillic letters as typed.
 */
object KeyboardLayout {
    private val toLatin: Map<Char, Char> = buildMap {
        // Ukrainian "і" and Russian "ы" share the s key.
        val pairs = listOf(
            'й' to 'q', 'ц' to 'w', 'у' to 'e', 'к' to 'r', 'е' to 't', 'н' to 'y', 'г' to 'u', 'ш' to 'i', 'щ' to 'o', 'з' to 'p',
            'ф' to 'a', 'і' to 's', 'ы' to 's', 'в' to 'd', 'а' to 'f', 'п' to 'g', 'р' to 'h', 'о' to 'j', 'л' to 'k', 'д' to 'l',
            'ч' to 'x', 'с' to 'c', 'м' to 'v', 'и' to 'b', 'т' to 'n', 'ь' to 'm',
        )
        pairs.forEach { (cyr, lat) ->
            put(cyr, lat)
            put(cyr.uppercaseChar(), lat.uppercaseChar())
        }
    }

    /** The QWERTY letter on the same key as [char], or null when [char] is not a mapped Cyrillic letter. */
    fun latinFor(char: Char): Char? = toLatin[char]
}
