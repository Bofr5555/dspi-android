package se.bofr.dspi.usb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source LINT, not a runtime guard: proves there is no literal
 * `setConfiguration(` / `.setInterface(` and that every `claimInterface(...)`
 * passes a literal `false` (UAC itf 0/1 belong to snd-usb-audio, DESIGN.md
 * §2.1). The runtime guards are `findVendorInterface` (tested in
 * VendorInterfaceTest) and the `check(intf.id == VENDOR_INTERFACE)` in
 * AndroidUsbLink.
 */
class AudioInterfaceGuardTest {
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun sourcesFound() = assertTrue(sources.isNotEmpty())

    @Test
    fun neverSetConfigurationOrForceClaim() {
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertTrue("${f.name}: setConfiguration", !code.contains("setConfiguration("))
            assertTrue("${f.name}: setInterface", !code.contains(".setInterface("))
            Regex("""claimInterface\(([^)]*)\)""").findAll(code).forEach {
                assertTrue("${f.name}: ${it.value} must pass force=false", it.groupValues[1].trim().endsWith("false"))
            }
        }
    }
}
