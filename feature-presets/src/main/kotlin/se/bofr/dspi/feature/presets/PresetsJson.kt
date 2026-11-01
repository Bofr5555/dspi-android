// Local copy of :bridge's internal Json reader (bridge/.../Json.kt, itself a
// copy of :fake-core MiniJson): the modules share no code and :feature-presets
// may not add a JSON library (docs/SUPPLY-CHAIN.md). Read-only: the feature
// only parses the core's result JSON and the AutoEQ database.
// TODO(consolidation): move one public copy into :ui-common or :bridge.
package se.bofr.dspi.feature.presets

/**
 * Objects become `LinkedHashMap<String, Any?>`, arrays `List<Any?>`, numbers
 * `Long` (integral) or `Double`, plus String, Boolean and null.
 */
internal object PresetsJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("trailing characters at ${p.i}")
        return v
    }

    /** [parse], expecting an object; null when the text is not one. */
    fun parseObject(text: String): Map<String, Any?>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        parse(text) as? Map<String, Any?>
    }.getOrNull()

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                expect(':')
                ws()
                m[k] = value()
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                expect('}')
                return m
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l += value()
                ws()
                if (s[i] == ',') {
                    i++
                    continue
                }
                expect(']')
                return l
            }
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("expected '$c' at $i")
            i++
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

// Typed accessors over parsed JSON (missing / wrong type -> null).
internal fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt()

internal fun Map<String, Any?>.double(k: String): Double? = (this[k] as? Number)?.toDouble()

internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean ?: (this[k] as? Number)?.let { it.toInt() != 0 }

internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(k: String): List<Any?>? = this[k] as? List<Any?>
