package se.bofr.dspi.ffi

import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.core.FilterParams

/** Pure mapping checks (no native library is loaded on the JVM). */
class FfiMappingTest {
    @Test
    fun filterParamsRoundTrip() {
        val p = FilterParams(filterType = 11, bypass = true, freq = 40f, q = 0.5f, gain = 20f, qp = 0.9f)
        assertEquals(p, p.toFfi().toDomain())
    }

    @Test
    fun unsignedBytesSurvive() {
        val ffi = se.bofr.dspi.ffi.uniffi.PinUse(pin = 200u, slot = 255u)
        val d = ffi.toDomain()
        assertEquals(200, d.pin)
        assertEquals(255, d.slot)
    }
}
