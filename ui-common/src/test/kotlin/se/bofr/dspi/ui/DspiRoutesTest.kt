package se.bofr.dspi.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DspiRoutesTest {
    @Test
    fun channelRoute() = assertEquals("channel/3", DspiRoutes.channel(3))
}
