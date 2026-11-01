package se.bofr.dspi.feature.settings

// Copy of :bridge Json.kt (internal there; modules share no code) for the
// control-surfaces JSON model. Consolidation candidate for :ui-common.
/**
 * A small JSON reader/writer, so the module needs no third-party library
 * (docs/SUPPLY-CHAIN.md). Objects become `LinkedHashMap<String, Any?>`,
 * arrays `List<Any?>`, numbers `Long` (integral) or `Double`, plus String,
 * Boolean and null. [write] accepts the same shapes plus Int/Float/Number,
 * ByteArray-free collections and arrays of primitives.
 */
internal object CsJson {
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

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Float -> num(sb, v.toDouble())
            is Double -> num(sb, v)
            is Number -> sb.append(v.toLong())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is IntArray -> write(sb, v.toList())
            is FloatArray -> write(sb, v.toList())
            is BooleanArray -> write(sb, v.toList())
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d)
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

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
internal fun Any?.asNum(): Number? = this as? Number

internal fun Map<String, Any?>.int(k: String): Int? = (this[k] as? Number)?.toInt() ?: (this[k] as? Boolean)?.let { if (it) 1 else 0 }

internal fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()

internal fun Map<String, Any?>.float(k: String): Float? = (this[k] as? Number)?.toFloat()

internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean ?: (this[k] as? Number)?.let { it.toInt() != 0 }

internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String

internal fun Map<String, Any?>.list(k: String): List<Any?>? = this[k] as? List<Any?>

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>
