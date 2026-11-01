package se.bofr.dspi.ui.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsAreTheMacOsOnes() {
        val s = AppSettings()
        assertTrue(s.showGraphGlow)
        assertEquals(2.0, s.graphLineWidth, 0.0)
        assertEquals(0.5, s.graphGridOpacity, 0.0)
        assertEquals(50.0, s.graphDBRange, 0.0)
        assertEquals(15.0, s.graphMinFreq, 0.0)
        assertEquals(-90.0, s.rtaFloorDB, 0.0)
        assertEquals(6.0, s.rtaCeilingDB, 0.0)
        assertEquals(10, s.rtaFftOrder)
        assertEquals(300, s.rtaAvgMs)
        assertEquals(12, s.rtaPeakDecayDBs)
        assertEquals("auto", s.sidebarVolumeMode)
    }

    @Test
    fun smoothingIsTheLightAmountWhenOn() {
        assertEquals(0.35, AppSettings().rtaSmoothing, 0.0)
        assertEquals(0.0, AppSettings(rtaSmoothingOn = false).rtaSmoothing, 0.0)
    }

    @Test
    fun rtaOptionsClampToTheDevicesRange() {
        assertEquals(RtaOptions(10, 300, 12), AppSettings().rtaOptions())
        assertEquals(8, AppSettings(rtaFftOrder = 3).rtaOptions().fftOrder)
        assertEquals(10, AppSettings(rtaFftOrder = 14).rtaOptions().fftOrder)
        assertEquals(9, AppSettings(rtaFftOrder = 14).rtaOptions(8, 9).fftOrder)
        assertEquals(0xFFFF, AppSettings(rtaAvgMs = 100_000).rtaOptions().avgMs)
    }

    @Test
    fun rtaShowsReadsThePagesPair() {
        val s = AppSettings(rtaDashboardShowGraph = false, rtaChannelShowBars = true)
        assertFalse(s.rtaShows(RtaSpectrumView.GRAPH, onDashboard = true))
        assertTrue(s.rtaShows(RtaSpectrumView.BARS, onDashboard = false))
        assertTrue(s.withRtaShows(RtaSpectrumView.GRAPH, true, true).rtaDashboardShowGraph)
    }

    @Test
    fun preferencesUseTheMacOsKeyNames() {
        // Every macOS AppSettings @AppStorage key (DSPi_ConsoleApp.swift) + "settingsSelectedTab" (SettingsView)
        // + "tubeModellerAdvanced" (TubeModellerView)
        val macOs = listOf(
            "showGraphGlow", "graphLineWidth", "graphAnimationSpeed", "showPhase", "phaseUnwrapped",
            "showFrequencyLabels", "showDBLabels", "showFrequencyReadout", "showLevelReadout", "showFrequencyGrid",
            "showDBGrid", "graphGridOpacity", "graphDBRange", "graphDBCenter", "graphHeight", "graphMinFreq",
            "graphMaxFreq", "dashboardCardsPerRow", "popoutGraphFollowsSelection", "showDebugInfo",
            "rtaDashboardShowGraph", "rtaDashboardShowBars", "rtaChannelShowGraph", "rtaChannelShowBars",
            "rtaDashboardSource", "rtaDashboardOtherSide", "rtaBarColumns", "rtaBarHeight",
            "rtaChannelPagesShowSpectrum", "rtaGraphOpacity", "rtaFloorDB", "rtaCeilingDB", "rtaShowPeakHold",
            "rtaSmoothingOn", "rtaFftOrder", "rtaAvgMs", "rtaPeakDecayDBs", "sidebarVolumeMode", "settingsSelectedTab",
            // TubeModellerView.swift
            "tubeModellerAdvanced"
        )
        assertEquals(macOs, AppSettingsFields.keyNames)
    }

    @Test
    fun readFallsBackToDefaultsAndReadsStoredValues() {
        val p = mutablePreferencesOf(booleanPreferencesKey("showPhase") to true, doublePreferencesKey("graphDBRange") to 30.0)
        val s = AppSettingsFields.read(p)
        assertTrue(s.showPhase)
        assertEquals(30.0, s.graphDBRange, 0.0)
        assertEquals(AppSettings().graphMinFreq, s.graphMinFreq, 0.0)
    }

    @Test
    fun writeStoresOnlyChangedFields() {
        val p = mutablePreferencesOf()
        val old = AppSettings()
        AppSettingsFields.write(p, old, old.copy(rtaFftOrder = 9))
        assertEquals(9, p[intPreferencesKey("rtaFftOrder")])
        assertNull(p[booleanPreferencesKey("showGraphGlow")])
        assertEquals(1, p.asMap().size)
    }

    @Test
    fun roundTrip() {
        val p = mutablePreferencesOf()
        val s = AppSettings(showGraphGlow = false, graphMaxFreq = 5000.0, rtaDashboardSelectionKey = "out:3", rtaBarColumns = 4)
        AppSettingsFields.write(p, null, s)
        assertEquals(s, AppSettingsFields.read(p))
    }

    @Test
    fun inMemoryStoreUpdates() {
        val st = InMemoryAppSettingsStore()
        st.update { it.copy(showDebugInfo = true) }
        assertTrue(st.settings.value.showDebugInfo)
    }

    @Test
    fun legacyImportFillsOnlyMissingKeysWithFittingTypes() {
        val p = mutablePreferencesOf(stringPreferencesKey("sidebarVolumeMode") to "auto")
        val home = mapOf<String, Any?>("sidebarVolumeMode" to "master", "dashboardCardsPerRow" to 2)
        val analysis = mapOf<String, Any?>("rtaDashboardSource" to "in:0,1", "rtaFloorDB" to "bad", "dashboardCardsPerRow" to 3)
        val tools = mapOf<String, Any?>("tubeModellerAdvanced" to true, "unknownKey" to 1)
        AppSettingsFields.importLegacy(p, listOf(home, analysis, tools))
        val s = AppSettingsFields.read(p)
        assertEquals("the DataStore value wins", "auto", s.sidebarVolumeMode)
        assertEquals("first file wins", 2, s.dashboardCardsPerRow)
        assertEquals("in:0,1", s.rtaDashboardSelectionKey)
        assertEquals("a value of the wrong type is ignored", -90.0, s.rtaFloorDB, 0.0)
        assertTrue(s.tubeModellerAdvanced)
        assertEquals(true, p[AppSettingsFields.LEGACY_IMPORTED])
        assertNull(p[intPreferencesKey("unknownKey")])
    }

    @Test
    fun legacyImportConvertsNumbers() {
        val p = mutablePreferencesOf()
        AppSettingsFields.importLegacy(p, listOf(mapOf("graphDBRange" to 40f, "rtaAvgMs" to 200L)))
        assertEquals(40.0, p[doublePreferencesKey("graphDBRange")]!!, 0.0)
        assertEquals(200, p[intPreferencesKey("rtaAvgMs")])
    }
}
