package com.ems.connectx.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Received from the owner-managed EMS catalog. The APK contains no carrier dial code. */
data class CarrierBalanceConfig(
    val name: String,
    val balanceCode: String,
    val balancePattern: String = ""
) {
    companion object {
        // Accept *1#, *123# or *123*45#; never a number, URL or free-form command.
        fun isSafeCode(code: String): Boolean = code.length in 3..25 &&
            Regex("^\\*(?=[0-9*]*[0-9])[0-9*]+#$").matches(code)
    }

    fun hasSafeCode(): Boolean = isSafeCode(balanceCode)
}

/** Fail closed: an unlabeled or ambiguous number is not a SIM balance.
 * An optional owner pattern must capture the *entire* numeric amount in group 1.
 * No raw reply is sent to EMS. */
object BalanceReplyParser {
    private const val amount = "([\\p{Nd}]+(?:[.,][\\p{Nd}]+)*)"
    private val defaults = listOf(
        Regex("""(?i)\b(?:available\s+)?balance\s*(?:is|:|=)?\s*(?:৳|tk\.?|taka|bdt)?\s*$amount(?![\p{Nd}]|[.,][\p{Nd}])"""),
        Regex("""(?i)(?<![-+])(?:৳|\btk\.?|\btaka\b|\bbdt\b)\s*$amount(?![\p{Nd}]|[.,][\p{Nd}])""")
    )
    private val signedCurrency = Regex("""(?i)[-+]\s*(?:৳|\btk\.?|\btaka\b|\bbdt\b)\s*[\p{Nd}]""")
    private val quotaLabel = Regex("""(?i)\b(?:sms|texts?|messages?)\s+(?:balance|remaining|left)\b""")
    private val unsafePattern = Regex("""\([^)]*[+*{][^)]*\)[+*{]""")
    private val plainNumber = Regex("^[0-9]{1,8}(?:\\.[0-9]{1,2})?$")
    private val groupedComma = Regex("^[0-9]{1,3}(?:,[0-9]{3})+$")
    private val groupedDot = Regex("^[0-9]{1,3}(?:\\.[0-9]{3})+$")

    fun balance(reply: String?, pattern: String? = null): String? {
        if (reply.isNullOrBlank()) return null
        val text = reply.take(240)
        if (signedCurrency.containsMatchIn(text) ||
            (pattern.isNullOrBlank() && quotaLabel.containsMatchIn(text))) return null
        val patterns = if (pattern.isNullOrBlank()) defaults else {
            if (pattern.length > 160 || unsafePattern.containsMatchIn(pattern)) return null
            listOf(try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { return null })
        }
        val found = mutableListOf<String>()
        try {
            for (regex in patterns) for (match in regex.findAll(text).take(8)) {
                val group = match.groups[1] ?: continue
                val start = group.range.first
                val end = group.range.last
                // Owner patterns must not grab only the first part of "1,234.50".
                if (text.getOrNull(start - 1)?.isDigit() == true ||
                    text.getOrNull(end + 1)?.isDigit() == true ||
                    (text.getOrNull(start - 1) in listOf(',', '.') && text.getOrNull(start - 2)?.isDigit() == true) ||
                    (text.getOrNull(end + 1) in listOf(',', '.') && text.getOrNull(end + 2)?.isDigit() == true) ||
                    text.getOrNull(start - 1) in listOf('-', '+')) return null
                found += group.value.trim()
            }
        } catch (_: Exception) { return null }
        val raw = found.filter { it.isNotBlank() }.distinct().singleOrNull() ?: return null
        val ascii = raw.map { c ->
            val n = Character.digit(c, 10)
            if (n in 0..9) ('0'.code + n).toChar() else c
        }.joinToString("")
        val normalized = when {
            ascii.contains(',') && ascii.contains('.') -> {
                val decimal = if (ascii.lastIndexOf('.') > ascii.lastIndexOf(',')) '.' else ','
                val parts = ascii.split(decimal)
                if (parts.size != 2 || parts[1].length !in 1..2) return null
                val thousands = if (decimal == '.') ',' else '.'
                val valid = if (thousands == ',') groupedComma else groupedDot
                if (!valid.matches(parts[0])) return null
                parts[0].replace(thousands.toString(), "") + "." + parts[1]
            }
            ascii.contains(',') || ascii.contains('.') -> {
                val sep = if (ascii.contains(',')) ',' else '.'
                val parts = ascii.split(sep)
                // A single separator followed by three digits is ambiguous:
                // it could be grouping or a 3-decimal balance. Never guess.
                if (parts.size != 2 || parts[1].length !in 1..2) return null
                parts[0] + "." + parts[1]
            }
            else -> ascii
        }
        if (!plainNumber.matches(normalized)) return null
        return runCatching {
            BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP).toPlainString()
        }.getOrNull()
    }
}
