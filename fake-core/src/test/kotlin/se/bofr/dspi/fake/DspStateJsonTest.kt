package se.bofr.dspi.fake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.core.DspiConstants

class DspStateJsonTest {
    @Test
    fun roundTripsTheDemoState() {
        val s = demoDspState()
        val back = DspStateJson.parse(DspStateJson.write(s))
        val a = DspStateJson.toMap(s)
        val b = DspStateJson.toMap(back)
        for (k in a.keys) assertEquals(k, a[k].toString(), b[k].toString())
        val none = ByteArray(0) // DspState compares bulkRaw by reference
        assertEquals(s.copy(bulkRaw = none), back.copy(bulkRaw = none))
        assertArrayEquals(s.bulkRaw, back.bulkRaw)
    }

    /** A hand-written excerpt in the fixture's shape (camelCase, nested records, missing fields). */
    @Test
    fun readsAnInlineSample() {
        val json =
            """
            {"platformId":1,"numChannels":17,"numInputChannels":8,"numOutputChannels":9,"formatVersion":32,
             "fwMajor":1,"fwMinor":1,"fwPatch":6,"fwBeta":5,"compat":1,"masterVolumeDb":-6.5,"userMute":true,
             "filters":[[{"filterType":1,"bypass":false,"freq":1200.0,"q":2.0,"gain":-3.5,"qp":0.707}]],
             "channelNames":["Left","Right"],
             "uart":{"enabled":true,"txPin":16,"rxPin":17,"notify":false,"baud":115200},
             "bulkRaw":"AAEC"}
            """.trimIndent()
        val s = DspStateJson.parse(json)
        assertEquals(1, s.platformId)
        assertEquals(9, s.numOutputChannels)
        assertEquals(DspiConstants.COMPAT_OK, s.compat)
        assertEquals(-6.5f, s.masterVolumeDb)
        assertEquals(true, s.userMute)
        assertEquals(1200f, s.filters[0][0].freq)
        assertEquals("Left", s.channelNames[0])
        assertEquals(115_200L, s.uart.baud)
        assertArrayEquals(byteArrayOf(0, 1, 2), s.bulkRaw)
        // Missing fields keep the core's defaults
        assertEquals(83f, s.loudnessRefSpl)
    }

    @Test
    fun bulkRawAsByteArrayAndWrappedState() {
        val s = DspStateJson.parse("""{"state":{"bulkRaw":[1,255,3],"crossfeedItd":0}}""")
        assertArrayEquals(byteArrayOf(1, -1, 3), s.bulkRaw)
        assertEquals(false, s.crossfeedItd)
    }

    @Test
    fun fixtureFactoryUsesTheText() {
        val core = FakeDspiCore.fromFixture("""{"platformId":0,"numChannels":7,"numInputChannels":2,"numOutputChannels":5,"compat":1}""")
        assertEquals(5, core.getState().numOutputChannels)
    }
}
