package se.bofr.dspi.app

import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.ui.DspiRoutes

class RoutesWiredTest {
    @Test
    fun allTopLevelRoutesAreDistinct() {
        val routes =
            listOf(
                DspiRoutes.HOME, DspiRoutes.CHANNEL, DspiRoutes.GRAPH, DspiRoutes.MATRIX, DspiRoutes.TOOLS,
                DspiRoutes.LIMITER,
                DspiRoutes.PRESETS, DspiRoutes.SETTINGS, DspiRoutes.ANALYSIS, DspiRoutes.FIRMWARE
            )
        assertEquals(routes.size, routes.toSet().size)
    }
}
