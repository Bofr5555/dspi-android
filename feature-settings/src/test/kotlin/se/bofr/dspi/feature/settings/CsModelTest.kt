package se.bofr.dspi.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsModelTest {
    private val snapshot = """
        {"supported":true,"capsVersion":21,"maxBindings":16,
         "types":[{},{},{},{},{},{},{},{},{},{},{}],
         "nouns":[],
         "status":{"dirty":true,"activeMask":5,"slotStatus":[0,0,3]},
         "bindings":[{"type":4,"noun":0,"action":0,"gpio0":2,"gpio1":3},{"type":0},{"type":9,"noun":68,"gpio0":22,"gpio1":255}],
         "names":["Volume knob","",""],
         "groups":[{"targetKind":2,"memberMask":3,"name":"Mains"},{"targetKind":0,"memberMask":0,"name":""}],
         "macros":[{"name":"","steps":[{"noun":6,"action":5}]}],
         "ext":{"macroRunning":255},
         "auxState":[0,0,1],"auxLevel":[0,0,25600]}
    """.trimIndent()

    @Test
    fun parsesTheCoreSnapshot() {
        val m = CsModel.parse(snapshot)
        assertTrue(m.supported)
        assertEquals(21, m.capsVersion)
        assertTrue(m.dirty)
        assertTrue(m.isSlotActive(0))
        assertFalse(m.isSlotActive(1))
        assertEquals(CsModel.TYPE_ENCODER, m.bindings[0].type)
        assertEquals(3, m.bindings[0].gpio1)
        assertEquals("Volume knob", m.slotName(0))
        assertEquals("Aux 3", m.auxName(2))
        assertEquals("Mains", m.groupName(0))
        assertEquals("Macro 1", m.macroName(0))
        assertTrue(m.groupsSupported && m.macrosSupported && m.auxSupported)
        assertEquals(1, m.auxState[2])
    }

    @Test
    fun emptyOrUnsupportedIsUnsupported() {
        assertFalse(CsModel.parse("").supported)
        assertFalse(CsModel.parse("""{"supported":false}""").supported)
        assertFalse(CsModel.parse("not json").supported)
    }

    @Test
    fun namesFollowTheMacOsConsole() {
        assertEquals("Rotary Encoder", CsModel.typeName(CsModel.TYPE_ENCODER))
        assertEquals("Clear Clipping", CsModel.nounName(8, CsModel.TYPE_BUTTON))
        assertEquals("Clipping", CsModel.nounName(8, CsModel.TYPE_LED))
        assertEquals("Running Macro", CsModel.nounName(52, CsModel.TYPE_LED))
        assertEquals("Up", CsModel.actionName(2, 56))
        assertEquals("Follow position", CsModel.actionName(6, 0))
    }

    @Test
    fun operationsAreTheCoresJson() {
        assertEquals("""{"op":"name","index":2,"name":"Amp"}""", CsModel.nameOp(2, "Amp"))
        assertEquals("""{"op":"auxState","index":1,"on":1}""", CsModel.auxStateOp(1, true))
        assertEquals("""{"op":"macroFire","index":0}""", CsModel.macroFireOp(0))
        assertTrue(CsModel.clearBindingOp(3).contains("\"type\":0"))
    }
}
