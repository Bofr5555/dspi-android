package se.bofr.dspi.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMapTest {
    private val rp2040 = ChannelMap(numInputChannels = 2, numOutputChannels = 5)
    private val rp2350 = ChannelMap(numInputChannels = 8, numOutputChannels = 9)

    @Test
    fun rp2040Mapping() {
        assertEquals(0, rp2040.wire(0))
        assertEquals(2, rp2040.wire(2)) // output 1
        assertEquals(6, rp2040.wire(6)) // output 5 (PDM)
        assertEquals(-1, rp2040.wire(7))
        assertEquals(-1, rp2040.wire(11))
        assertEquals(4, rp2040.appId(4))
    }

    @Test
    fun rp2350Mapping() {
        assertEquals(2, rp2350.wire(11)) // input 3
        assertEquals(7, rp2350.wire(16)) // input 8
        assertEquals(8, rp2350.wire(2)) // output 1
        assertEquals(16, rp2350.wire(10)) // output 9
        for (w in 0 until 17) assertEquals(w, rp2350.wire(rp2350.appId(w)))
        assertEquals(13, rp2350.inputAppId(4))
        assertEquals(4, rp2350.inputOf(13))
        assertEquals(8, rp2350.outputOf(10))
    }
}
