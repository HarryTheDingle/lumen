package lumos.iptv

/** Decomposed UTC date and time components. */
data class UtcFields(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_DAY = 86_400L

private fun floorDiv(a: Long, b: Long): Long {
    var q = a / b
    if ((a xor b) < 0L && q * b != a) q -= 1L
    return q
}

private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 0
}

private fun isValidDateTime(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
): Boolean {
    if (month !in 1..12) return false
    val maxDay = daysInMonth(year, month)
    if (day !in 1..maxDay) return false
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return false
    return true
}

/** Converts UTC epoch milliseconds to [UtcFields] using Howard Hinnant civil calendar math. */
fun toUtcFields(epochMs: Long): UtcFields {
    val totalSeconds = floorDiv(epochMs, MILLIS_PER_SECOND)
    val days = floorDiv(totalSeconds, SECONDS_PER_DAY)
    val secOfDay = floorMod(totalSeconds, SECONDS_PER_DAY).toInt()

    val shifted = days + 719_468L
    val era = floorDiv(shifted, 146_097L)
    val doe = shifted - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val m = (mp + if (mp < 10L) 3L else -9L).toInt()
    val year = (y + if (m <= 2) 1L else 0L).toInt()

    val hour = secOfDay / 3600
    val minute = (secOfDay % 3600) / 60
    val second = secOfDay % 60
    return UtcFields(year = year, month = m, day = d, hour = hour, minute = minute, second = second)
}

/** Converts UTC civil calendar fields to epoch milliseconds using Howard Hinnant civil calendar math. */
fun fromUtcFields(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    second: Int = 0,
): Long {
    var yr = year.toLong()
    if (month <= 2) yr -= 1L
    val era = floorDiv(yr, 400L)
    val yoe = yr - era * 400L
    val doy = (153L * (month + if (month > 2) -3 else 9) + 2L) / 5L + day - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    val days = era * 146_097L + doe - 719_468L
    val totalSeconds = days * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second
    return totalSeconds * MILLIS_PER_SECOND
}

internal fun pad2(value: Int): String = value.toString().padStart(2, '0')

internal fun pad4(value: Int): String = value.toString().padStart(4, '0')

/** Formats UTC epoch milliseconds as Xtream timeshift timestamp `YYYY-MM-DD:HH-mm`. */
fun formatXtreamTimestamp(epochMs: Long): String {
    val f = toUtcFields(epochMs)
    return "${pad4(f.year)}-${pad2(f.month)}-${pad2(f.day)}:${pad2(f.hour)}-${pad2(f.minute)}"
}

private fun parseOffsetMinutes(offsetRaw: String): Int? {
    val trimmed = offsetRaw.trim()
    if (trimmed.isEmpty() || trimmed.equals("Z", ignoreCase = true) ||
        trimmed.equals("UTC", ignoreCase = true) || trimmed.equals("GMT", ignoreCase = true)
    ) {
        return 0
    }
    val sign = when (trimmed[0]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val digits = trimmed.substring(1).replace(":", "")
    val (hours, minutes) = when (digits.length) {
        2 -> (digits.toIntOrNull() ?: return null) to 0
        4 -> (digits.substring(0, 2).toIntOrNull() ?: return null) to (digits.substring(2, 4).toIntOrNull() ?: return null)
        else -> return null
    }
    if (hours !in 0..23 || minutes !in 0..59) return null
    return sign * (hours * 60 + minutes)
}

/**
 * Parses XMLTV timestamps (`YYYYMMDDHHmmss +0000`, `YYYYMMDDHHmmss -0500`, `YYYYMMDDHHmm`,
 * or without offset defaulting to UTC) into UTC epoch milliseconds.
 */
fun parseXmltvTime(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.length < 12) return null

    var digitEnd = 0
    while (digitEnd < trimmed.length && digitEnd < 14 && trimmed[digitEnd].isDigit()) {
        digitEnd++
    }
    if (digitEnd != 12 && digitEnd != 14) return null

    val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
    val month = trimmed.substring(4, 6).toIntOrNull() ?: return null
    val day = trimmed.substring(6, 8).toIntOrNull() ?: return null
    val hour = trimmed.substring(8, 10).toIntOrNull() ?: return null
    val minute = trimmed.substring(10, 12).toIntOrNull() ?: return null
    val second = if (digitEnd == 14) (trimmed.substring(12, 14).toIntOrNull() ?: return null) else 0
    if (!isValidDateTime(year, month, day, hour, minute, second)) return null

    val offsetStr = trimmed.substring(digitEnd).trim()
    val offsetMinutes = if (offsetStr.isEmpty()) 0 else (parseOffsetMinutes(offsetStr) ?: return null)
    return fromUtcFields(year, month, day, hour, minute, second) - offsetMinutes * MILLIS_PER_MINUTE
}

private val ISO_REGEX = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d+))?(.*)$"""
)

/** Parses ISO-8601 and `YYYY-MM-DD HH:mm:ss` timestamps (defaulting missing offset to UTC) into UTC epoch milliseconds. */
fun parseIsoUtcMillis(raw: String): Long? {
    val match = ISO_REGEX.matchEntire(raw.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
    if (!isValidDateTime(year, month, day, hour, minute, second)) return null

    val frac = match.groupValues[7]
    val extraMs = if (frac.isEmpty()) 0L else frac.take(3).padEnd(3, '0').toLongOrNull() ?: return null
    val offsetStr = match.groupValues[8].trim()
    val offsetMinutes = if (offsetStr.isEmpty()) 0 else (parseOffsetMinutes(offsetStr) ?: return null)

    return fromUtcFields(year, month, day, hour, minute, second) + extraMs - offsetMinutes * MILLIS_PER_MINUTE
}

private fun base64Value(ch: Char): Int = when (ch) {
    in 'A'..'Z' -> ch.code - 'A'.code
    in 'a'..'z' -> ch.code - 'a'.code + 26
    in '0'..'9' -> ch.code - '0'.code + 52
    '+', '-' -> 62
    '/', '_' -> 63
    else -> -1
}

/**
 * Decodes Base64 UTF-8 strings safely, accepting missing `=` padding and falling back to [encoded]
 * when the input is not valid Base64/UTF-8 text (such as unencoded provider EPG titles).
 */
fun decodeBase64Utf8(encoded: String): String {
    if (encoded.isBlank()) return ""
    val stripped = buildString(encoded.length) {
        for (ch in encoded.trim()) {
            if (ch != '\r' && ch != '\n') append(ch)
        }
    }
    var padding = 0
    while (padding < 2 && stripped.length - padding > 0 && stripped[stripped.length - 1 - padding] == '=') {
        padding++
    }
    val bodyLen = stripped.length - padding
    if (bodyLen == 0) return ""
    if (bodyLen % 4 == 1) return encoded

    val out = ByteArray((bodyLen * 3) / 4)
    var outIdx = 0
    var i = 0
    while (i < bodyLen) {
        val v0 = base64Value(stripped[i])
        val v1 = base64Value(stripped[i + 1])
        if (v0 < 0 || v1 < 0) return encoded
        val v2 = if (i + 2 < bodyLen) base64Value(stripped[i + 2]).also { if (it < 0) return encoded } else -1
        val v3 = if (i + 3 < bodyLen) base64Value(stripped[i + 3]).also { if (it < 0) return encoded } else -1

        val b0 = ((v0 shl 2) or (v1 ushr 4)) and 0xFF
        out[outIdx++] = b0.toByte()
        if (v2 >= 0) {
            val b1 = (((v1 and 0x0F) shl 4) or (v2 ushr 2)) and 0xFF
            out[outIdx++] = b1.toByte()
            if (v3 >= 0) {
                val b2 = (((v2 and 0x03) shl 6) or v3) and 0xFF
                out[outIdx++] = b2.toByte()
            }
        }
        i += 4
    }

    val decoded = runCatching {
        out.copyOf(outIdx).decodeToString(throwOnInvalidSequence = true)
    }.getOrNull() ?: return encoded

    // Reject control characters that indicate raw plain text accidentally matching the Base64 alphabet.
    for (ch in decoded) {
        if (ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') return encoded
    }
    return decoded
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun isUnreservedUrlByte(b: Int): Boolean {
    val c = b and 0xFF
    return c in 'A'.code..'Z'.code ||
        c in 'a'.code..'z'.code ||
        c in '0'.code..'9'.code ||
        c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
}

/** Percent-encodes [value] as UTF-8 per RFC 3986 unreserved characters. */
fun urlEncode(value: String): String {
    val bytes = value.encodeToByteArray()
    return buildString(bytes.size) {
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            if (isUnreservedUrlByte(code)) {
                append(code.toChar())
            } else {
                append('%')
                append(HEX_DIGITS[code ushr 4])
                append(HEX_DIGITS[code and 0x0F])
            }
        }
    }
}
