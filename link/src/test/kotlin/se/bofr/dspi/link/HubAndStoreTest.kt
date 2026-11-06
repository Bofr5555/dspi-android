package se.bofr.dspi.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubAndStoreTest {
    private fun txt(vararg kv: Pair<String, String>) = kv.associate { it.first to it.second.toByteArray() }

    @Test
    fun txtRecordOfASpecHub() {
        val hub = HubTxt.parse(
            "Studio Mac",
            txt(
                "v" to "1",
                "hid" to "6F1A2B3C-4D5E-4F60-8A9B-0C1D2E3F4A5B",
                "kind" to "console",
                "auth" to "pin",
                "n" to "2",
                "d" to "E46058388B1A2E2C,0123456789ABCDEF"
            ),
            "192.168.1.20",
            11915
        )!!
        assertEquals("6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b", hub.id)
        assertEquals("console", hub.kind)
        assertEquals(2, hub.deviceCount)
        assertEquals(listOf("E46058388B1A2E2C", "0123456789ABCDEF"), hub.serials)
        assertEquals("/dspi/v1", hub.path)
        assertEquals("192.168.1.20", hub.displayAddress)
        assertTrue(!hub.isOpen)
    }

    @Test
    fun txtRecordRules() {
        val base = arrayOf("v" to "1", "hid" to "x", "kind" to "bridge", "auth" to "none", "n" to "1")
        assertNull("no hid", HubTxt.parse("a", txt("v" to "1"), "h", 1))
        assertNull("no version", HubTxt.parse("a", txt("hid" to "x"), "h", 1))
        assertNull("TLS hubs are not supported", HubTxt.parse("a", txt(*base, "tls" to "1"), "h", 1))
        val h = HubTxt.parse("a", txt(*base, "path" to "/x", "zz" to "unknown"), "fe80::1", 8080)!!
        assertEquals("/x", h.path)
        assertTrue(h.isOpen)
        assertEquals("[fe80::1]:8080", h.displayAddress)
        assertEquals("Studio Mac", HubTxt.unescapeName("Studio\\032Mac"))
    }

    @Test
    fun manualHubsRoundTrip() {
        val hubs = listOf(ManualHub("192.168.1.20", 11915), ManualHub("fe80::1", 8080), ManualHub("studio.local", 1))
        assertEquals(hubs, decodeManual(encodeManual(hubs)))
        assertEquals(emptyList<ManualHub>(), decodeManual("junk\n0 x\n70000 y"))
        assertEquals("manual:h:1", LinkHub.manual("h", 1).id)
    }

    /** Reversible, not secret: only the store's framing is under test. */
    private class XorCipher : TokenCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }

        override fun decrypt(sealed: ByteArray) =
            if (sealed.firstOrNull() != 7.toByte()) null else sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private class MapKv : KeyValue {
        val m = HashMap<String, String>()

        override fun get(key: String) = m[key]

        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }

        override fun keys() = m.keys.toSet()
    }

    @Test
    fun tokensAreKeyedByHubIdAndSealed() {
        val kv = MapKv()
        val store = SealedLinkTokenStore(kv, XorCipher())
        store.setToken("6F1A", "Studio Mac", "Zm9vYmFy")
        store.setToken("0d6c", "DSPi Bridge 3F2A", "dG9r")
        assertEquals("Zm9vYmFy", store.token("6f1a"))
        assertTrue(kv.m.values.none { "Zm9vYmFy" in it })
        assertEquals(listOf(PairedHub("0d6c", "DSPi Bridge 3F2A"), PairedHub("6f1a", "Studio Mac")), store.paired())
        store.remove("6f1a")
        assertNull(store.token("6f1a"))
        kv.m["hub:0d6c"] = "not base64 !"
        assertNull("tampered entries read as no token", store.token("0d6c"))
    }
}
