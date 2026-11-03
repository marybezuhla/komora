package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.net.ReleaseNotes

class ReleaseNotesTest {
    @Test
    fun plainTextWithoutCodeOrChecksums() {
        val body = """
            **Тестова версія.** Завантажте .apk нижче.

            ## Що змінилось
            - Нові теми
            * [Іконки](https://example.org) застосунків


            SHA-256:
            ```
            3b212bbd8d0bf95463d4bdf7ee6632c67e049212e83e963244bf3814a8f62d14  komora-0.1.0.apk
            ```
        """.trimIndent()
        assertEquals(
            "Тестова версія. Завантажте .apk нижче.\n\nЩо змінилось\n• Нові теми\n• Іконки застосунків",
            ReleaseNotes.clean(body),
        )
    }

    @Test
    fun emptyAndLong() {
        assertEquals("", ReleaseNotes.clean(null))
        assertEquals("", ReleaseNotes.clean("  \n "))
        val long = ReleaseNotes.clean("а".repeat(5000))
        assertTrue(long.length <= ReleaseNotes.MAX_CHARS + 1)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun noHtmlPicturesOrDirectionMarks() {
        val body = "<!-- hidden -->\n<details><summary>Більше</summary>\nТекст\u202Eзворотний\n![pic](https://x/y.png)\n</details>"
        assertEquals("Більше\nТекстзворотний", ReleaseNotes.clean(body))
    }
}
