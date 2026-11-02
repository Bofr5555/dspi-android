package se.bofr.dspi.feature.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every licence text the Licences screen opens ships in assets/licenses/ (NOTICE.md). */
class LicensesTest {
    private val dir = File("src/main/assets/licenses")

    @Test
    fun everyReferencedTextIsBundled() {
        (THIRD_PARTY.mapNotNull { it.text } + GPL3).distinct().forEach {
            val f = File(dir, it.asset)
            assertTrue("missing ${f.path}", f.isFile && f.length() > 500)
        }
    }

    @Test
    fun rustNoticesCoverLibusbAndUniffi() {
        val t = File(dir, RUST_CRATES.asset).readText()
        assertTrue(t.contains("libusb/COPYING"))
        assertTrue(t.contains("\nuniffi "))
        assertTrue(t.contains("\nrusb "))
    }

    @Test
    fun aboutCreditsBothAuthors() {
        assertTrue(ADDITIONAL_TERMS.first().contains("Weeb Labs") && ADDITIONAL_TERMS.first().contains("Bofr5555"))
    }
}
