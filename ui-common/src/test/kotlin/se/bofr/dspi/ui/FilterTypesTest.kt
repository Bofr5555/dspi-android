package se.bofr.dspi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTypesTest {
    @Test
    fun namesMatchMacOs() {
        assertEquals("High Cut (12dB)", FilterType.LOW_PASS.displayName)
        assertEquals("LR4 Low Pass", FilterType.LR4_LP.displayName)
        assertEquals("BW1 High Pass", FilterType.BW1_HP.displayName)
        assertEquals("BES8 High Pass", FilterType.BES8_HP.displayName)
        assertEquals("LR4 LP", FilterType.LR4_LP.shortLabel)
        assertEquals("NO", FilterType.NOTCH.dashboardCode)
        assertEquals("LS1", FilterType.LOW_SHELF1.dashboardCode)
        assertEquals("6 dB/oct", FilterType.LOW_PASS1.orderLabel)
    }

    @Test
    fun crossoverDecomposition() {
        for (t in FilterType.entries.filter { it.isCrossover }) {
            val fam = t.crossoverFamily!!
            assertEquals(t, fam.filterType(t.crossoverOrder, t.crossoverIsLowPass!!))
        }
        assertEquals(8, FilterType.BW8_LP.crossoverOrder)
        assertEquals(6, FilterType.BES6_HP.crossoverOrder)
        assertNull(CrossoverFamily.LINKWITZ_RILEY.filterType(3, true))
        assertNull(FilterType.PEAKING.crossoverIsLowPass)
    }

    @Test
    fun usesGainAndQ() {
        assertTrue(FilterType.PEAKING.usesQ)
        assertFalse(FilterType.LOW_SHELF1.usesQ)
        assertFalse(FilterType.LINKWITZ_TRANSFORM.usesGain)
        assertFalse(FilterType.LR2_LP.usesQ)
        assertTrue(FilterType.NOTCH.usesQ)
    }

    @Test
    fun availableTypesHideLinkwitzOnInputs() {
        assertFalse(FilterType.LINKWITZ_TRANSFORM in FilterType.availableFilterTypes(includeLinkwitz = false))
        assertTrue(FilterType.LINKWITZ_TRANSFORM in FilterType.availableFilterTypes())
        assertTrue(FilterType.availableFilterTypes().none { it.isCrossover })
        assertEquals(FilterType.FLAT, FilterType.crossoverTypes.first())
    }

    @Test
    fun retypedSeedsNeutralLinkwitzAndDropsFp() {
        val pk = BandFields(FilterType.PEAKING, 5f, 30f, 6f, 0.707f)
        val lt = pk.retyped(FilterType.LINKWITZ_TRANSFORM)
        assertEquals(10f, lt.freq)
        assertEquals(20f, lt.q)
        assertEquals(10f, lt.gain)
        assertEquals(20f, lt.qp)
        assertEquals(0f, lt.linkwitzDcBoostDb, 1e-6f)
        val back = lt.copy(gain = 25f).retyped(FilterType.PEAKING)
        assertEquals(0f, back.gain)
        assertEquals(6f, pk.retyped(FilterType.LOW_SHELF).gain)
    }

    @Test
    fun linkwitzBoostAndDirty() {
        val applied = BandFields(FilterType.LINKWITZ_TRANSFORM, 50f, 0.9f, 25f, 0.707f)
        assertEquals(40f * kotlin.math.log10(2f), applied.linkwitzDcBoostDb, 1e-4f)
        assertFalse(applied.copy(gain = 25.01f).linkwitzDiffers(applied))
        assertTrue(applied.copy(gain = 26f).linkwitzDiffers(applied))
        assertFalse(applied.copy(qp = 0.707f + 0.001f).linkwitzDiffers(applied))
    }

    @Test
    fun crossoverRowEdits() {
        val lr4 = BandFields(FilterType.LR4_HP, 80f, 0.707f, 0f, 0.707f)
        assertEquals(FilterType.BW4_HP, lr4.withFamily(CrossoverFamilyOption(CrossoverFamily.BUTTERWORTH))!!.type)
        val bw3 = lr4.copy(type = FilterType.BW3_LP)
        // order 3 is not an LR order: closest wins (2 before 4 on a tie).
        assertEquals(FilterType.LR2_LP, bw3.withFamily(CrossoverFamilyOption(CrossoverFamily.LINKWITZ_RILEY))!!.type)
        assertEquals(FilterType.LR4_LP, lr4.withLowPass(true)!!.type)
        assertEquals(FilterType.LR8_HP, lr4.withOrder(8)!!.type)
        assertEquals(FilterType.FLAT, lr4.withFamily(CrossoverFamilyOption.OFF)!!.type)
        // From Off: LP by default, order snaps to the family's closest.
        assertEquals(
            FilterType.BES2_LP,
            BandFields(FilterType.FLAT, 1000f, 0.7f, 0f, 0.7f).withFamily(CrossoverFamilyOption(CrossoverFamily.BESSEL))!!.type
        )
        assertEquals(
            listOf("Off", "Linkwitz-Riley", "Butterworth", "Bessel"),
            CrossoverFamilyOption.available(FilterType.crossoverTypes).map { it.label }
        )
    }
}
