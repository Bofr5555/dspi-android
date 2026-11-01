package se.bofr.dspi.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** ChannelPalette = macOS ChannelPalette.swift (review F8). */
class ChannelPaletteTest {
    @Test
    fun inputsAreTheMacOsValuesInTheBridgeHexFormat() {
        assertEquals(
            listOf("#4A8FE3", "#F57373", "#73C78C", "#EDB34D", "#998CEB", "#E68CC7", "#66C7D1", "#CCB86B"),
            ChannelPalette.inputs.map(ChannelPalette::hex)
        )
    }

    @Test
    fun outsideTheModelIsTheAccentAndPdmIsPurple() {
        assertEquals(ChannelPalette.accent, ChannelPalette.input(8))
        assertEquals(ChannelPalette.accent, ChannelPalette.output(8))
        assertEquals(ChannelPalette.pdm, ChannelPalette.output(8, isPdm = true))
        // App ids: RP2040 has 5 outputs, its PDM is output 4 = app 6
        assertEquals(ChannelPalette.pdm, ChannelPalette.color(6, numOutputs = 5))
        assertEquals(ChannelPalette.outputs[4], ChannelPalette.color(6, numOutputs = 9))
        assertEquals(ChannelPalette.accent, ChannelPalette.color(42))
    }

    @Test
    fun fromHexReadsSixAndEightDigitsElseTheFallback() {
        assertEquals(ChannelPalette.inputs[0], ChannelPalette.fromHex("#4A8FE3"))
        assertEquals(ChannelPalette.pdm, ChannelPalette.fromHex("nope", ChannelPalette.pdm))
    }
}
