package dev.dotnote.app

/**
 * Allocation-light JSON reader for note documents. Opening a large note previously built a full
 * org.json object tree, boxed every coordinate and parsed each number from a substring. This
 * cursor reads values directly from the source text instead. It accepts standard JSON only.
 */
class JsonCursor(private val s: String) {
    var pos = 0
        private set

    fun fail(message: String = "Malformed note JSON"): Nothing =
        throw IllegalArgumentException("$message at $pos")

    private fun ws() {
        while (pos < s.length) {
            val c = s[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
        }
    }

    fun peek(): Char {
        ws()
        if (pos >= s.length) fail("Unexpected end of note JSON")
        return s[pos]
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("Expected '$c'")
        pos++
    }

    fun beginObject() = expect('{')

    fun beginArray() = expect('[')

    /** Returns the next key of the current object, or null at its end. */
    fun nextKey(first: Boolean): String? {
        val c = peek()
        if (c == '}') {
            pos++
            return null
        }
        if (!first) {
            if (c != ',') fail("Expected ','")
            pos++
        }
        val key = string()
        expect(':')
        return key
    }

    /** True while the current array has another element; consumes separators and the end. */
    fun hasNext(first: Boolean): Boolean {
        val c = peek()
        if (c == ']') {
            pos++
            return false
        }
        if (!first) {
            if (c != ',') fail("Expected ','")
            pos++
        }
        return true
    }

    fun end() {
        ws()
        if (pos != s.length) fail("Trailing note JSON")
    }

    fun isNull(): Boolean {
        if (peek() == 'n' && s.startsWith("null", pos)) {
            pos += 4
            return true
        }
        return false
    }

    fun string(): String {
        if (peek() != '"') fail("Expected a string")
        pos++
        val start = pos
        // Fast path: no escapes.
        while (pos < s.length) {
            val c = s[pos]
            if (c == '"') return s.substring(start, pos++)
            if (c == '\\') break
            if (c < ' ') fail("Control character in string")
            pos++
        }
        val out = StringBuilder(pos - start + 64).append(s, start, pos)
        while (pos < s.length) {
            // Copy unescaped runs in bulk (Base64 ink is full of escaped '/').
            val run = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c == '"' || c == '\\' || c < ' ') break
                pos++
            }
            if (pos > run) out.append(s, run, pos)
            if (pos >= s.length) break
            val c = s[pos++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (pos >= s.length) fail()
                    when (val e = s[pos++]) {
                        '"',
                        '\\',
                        '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) fail()
                            out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("Invalid escape")
                    }
                }
                c < ' ' -> fail("Control character in string")
                else -> out.append(c)
            }
        }
        fail("Unterminated string")
    }

    /** Skips a string without building it; escapes are still checked. */
    fun skipString() {
        if (peek() != '"') fail("Expected a string")
        pos++
        while (pos < s.length) {
            val c = s[pos++]
            when {
                c == '"' -> return
                c == '\\' -> {
                    if (pos >= s.length) fail()
                    when (s[pos++]) {
                        '"',
                        '\\',
                        '/',
                        'b',
                        'f',
                        'n',
                        'r',
                        't' -> {}
                        'u' -> {
                            if (pos + 4 > s.length) fail()
                            for (k in pos until pos + 4) if (s[k].digitToIntOrNull(16) == null) fail()
                            pos += 4
                        }
                        else -> fail("Invalid escape")
                    }
                }
                c < ' ' -> fail("Control character in string")
            }
        }
        fail("Unterminated string")
    }

    /** Skips a number using JSON's grammar without converting it. */
    private fun skipNumber() {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        var digits = false
        while (pos < s.length && s[pos] in '0'..'9') {
            pos++
            digits = true
        }
        if (pos < s.length && s[pos] == '.') {
            pos++
            while (pos < s.length && s[pos] in '0'..'9') {
                pos++
                digits = true
            }
        }
        if (!digits) {
            pos = start
            fail("Expected a number")
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            val exponent = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == exponent) fail("Expected a number")
        }
    }

    fun boolean(): Boolean {
        val c = peek()
        if (c == 't' && s.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (c == 'f' && s.startsWith("false", pos)) {
            pos += 5
            return false
        }
        // org.json accepted quoted booleans; keep reading files it wrote or accepted.
        if (c == '"') {
            val text = string()
            if (text.equals("true", true)) return true
            if (text.equals("false", true)) return false
        }
        fail("Expected a boolean")
    }

    private var integral = false
    private var longValue = 0L

    /** Reads a number as the correctly rounded double, matching Double.parseDouble. */
    fun number(): Double {
        val c = peek()
        if (c == '"') {
            // org.json coerced numeric strings; keep the same leniency for existing files.
            val text = string().trim()
            val value = text.toDoubleOrNull() ?: fail("Expected a number")
            integral = text.toLongOrNull()?.also { longValue = it } != null
            return value
        }
        val start = pos
        var negative = false
        if (c == '-') {
            negative = true
            pos++
        }
        var mantissa = 0L
        var digits = 0
        var exponent = 0
        var exact = true
        var sawDigit = false
        while (pos < s.length && s[pos] in '0'..'9') {
            sawDigit = true
            if (digits < 18) {
                mantissa = mantissa * 10 + (s[pos] - '0')
                if (mantissa != 0L) digits++
            } else {
                exponent++
                exact = false
            }
            pos++
        }
        var fraction = false
        if (pos < s.length && s[pos] == '.') {
            fraction = true
            pos++
            while (pos < s.length && s[pos] in '0'..'9') {
                sawDigit = true
                if (digits < 18) {
                    mantissa = mantissa * 10 + (s[pos] - '0')
                    if (mantissa != 0L) digits++
                    exponent--
                } else if (s[pos] != '0') exact = false
                pos++
            }
        }
        if (!sawDigit) fail("Expected a number")
        var expPart = false
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            expPart = true
            pos++
            var expNegative = false
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                expNegative = s[pos] == '-'
                pos++
            }
            var e = 0
            var expDigits = false
            while (pos < s.length && s[pos] in '0'..'9') {
                expDigits = true
                if (e < 100000) e = e * 10 + (s[pos] - '0')
                pos++
            }
            if (!expDigits) fail("Expected a number")
            exponent += if (expNegative) -e else e
        }
        integral = !fraction && !expPart && exact
        if (integral) {
            longValue = if (negative) -mantissa else mantissa
            return longValue.toDouble()
        }
        // Clinger's fast path is exact when both the mantissa and power of ten are exact doubles.
        if (exact && mantissa < (1L shl 53) && exponent in -22..22) {
            val m = mantissa.toDouble()
            val value = if (exponent >= 0) m * POWERS[exponent] else m / POWERS[-exponent]
            return if (negative) -value else value
        }
        return s.substring(start, pos).toDouble()
    }

    /** Integer coercion with org.json semantics: integers wrap, decimals saturate. */
    fun int(): Int {
        val value = number()
        return if (integral) longValue.toInt() else value.toInt()
    }

    fun finite(): Float = number().toFloat().also { if (!it.isFinite()) fail("Number is not finite") }

    fun skip() {
        when (peek()) {
            '{' -> {
                beginObject()
                var first = true
                while (nextKey(first) != null) {
                    first = false
                    skip()
                }
            }
            '[' -> {
                beginArray()
                var first = true
                while (hasNext(first)) {
                    first = false
                    skip()
                }
            }
            '"' -> skipString()
            't',
            'f' -> boolean()
            'n' -> if (!isNull()) fail()
            else -> skipNumber()
        }
    }

    /** Returns the exact source text of the next value. */
    fun raw(): String {
        ws()
        val start = pos
        skip()
        return s.substring(start, pos)
    }

    private companion object {
        val POWERS = DoubleArray(23).also { p ->
            p[0] = 1.0
            for (i in 1 until p.size) p[i] = p[i - 1] * 10
        }
    }
}

/** Writes JSON text exactly as Android's org.json did, so saved files keep the same bytes. */
object JsonText {
    fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"',
                '\\',
                '/' -> out.append('\\').append(c)
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000c' -> out.append("\\f")
                else ->
                    if (c.code <= 0x1f) {
                        out.append("\\u00")
                        out.append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                    } else out.append(c)
            }
        }
        out.append('"')
    }

    fun number(out: StringBuilder, value: Float) {
        require(value.isFinite()) { "Number is not finite" }
        val whole = value.toLong()
        if (whole.toFloat() == value && value.toDouble() == whole.toDouble()) out.append(whole)
        else out.append(value)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
