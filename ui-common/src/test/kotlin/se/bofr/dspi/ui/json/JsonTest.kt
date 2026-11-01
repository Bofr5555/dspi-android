package se.bofr.dspi.ui.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test
    fun parsesTheShapesTheCoreWrites() {
        val m = Json.parseObject("""{"ok":true,"n":-3,"x":1.5e1,"s":"a\"b\\cé\n","l":[1,null,false,{}],"o":{"k":[]}}""")!!
        assertEquals(true, m.bool("ok"))
        assertEquals(-3, m.int("n"))
        assertEquals(15.0, m.double("x")!!, 0.0)
        assertEquals("a\"b\\cé\n", m.str("s"))
        assertEquals(listOf(1L, null, false, emptyMap<String, Any?>()), m.list("l"))
        assertEquals(mapOf("k" to emptyList<Any?>()), m["o"])
    }

    @Test
    fun rejectsBrokenText() {
        assertNull(Json.parseObject("""{"a":1"""))
        assertNull(Json.parseObject("""{"a":1} trailing"""))
        assertNull(Json.parseObject("[1,2]"))
        assertNull(Json.parseObject(""))
    }
}
