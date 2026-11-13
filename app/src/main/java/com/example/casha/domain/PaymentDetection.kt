package com.example.casha.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PaymentDetection(val amount:Long?,val date:LocalDate?,val reference:String?,val reviewNote:String)
object PaymentDetector {
    private val monthDateFormatters=listOf(
        DateTimeFormatter.ofPattern("MMM d, yyyy",Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d, yyyy",Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy",Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy",Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MM/dd/yyyy",Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd",Locale.ENGLISH)
    )

    private fun detectDate(text:String):LocalDate? {
        val iso=Regex("\\b(20\\d{2}-\\d{2}-\\d{2})\\b").find(text)?.groupValues?.get(1)
        if(iso!=null) runCatching { return LocalDate.parse(iso) }
        val candidates=buildList {
            Regex("(?i)\\b([A-Z]{3,9}\\s+\\d{1,2},\\s*20\\d{2})\\b").findAll(text).forEach { add(it.groupValues[1].replace(Regex("\\s+")," ")) }
            Regex("(?i)\\b(\\d{1,2}\\s+[A-Z]{3,9}\\s+20\\d{2})\\b").findAll(text).forEach { add(it.groupValues[1].replace(Regex("\\s+")," ")) }
            Regex("\\b(\\d{2}/\\d{2}/20\\d{2})\\b").findAll(text).forEach { add(it.groupValues[1]) }
            Regex("\\b(20\\d{2}/\\d{2}/\\d{2})\\b").findAll(text).forEach { add(it.groupValues[1]) }
        }
        for(raw in candidates) {
            for(formatter in monthDateFormatters) {
                val parsed=runCatching { LocalDate.parse(raw,formatter) }.getOrNull()
                if(parsed!=null) return parsed
            }
        }
        return null
    }

    /** Prefer explicit payable totals; never choose cash tendered, change, item counts or identifiers. */
    fun receiptTotal(text: String): Long? {
        val label = Regex("(?i)\\b(grand\\s+total|total\\s+(?:amount\\s+due|due|payable)|amount\\s+(?:due|payable)|net\\s+total|total)(?![a-z]|\\s*(?:items?|quantity|qty|savings|discount|tax)\\b)\\s*[:=]?\\s*")
        val amount = Regex("(?i)(?<![0-9.,])(?:(?:PHP|₱|P)\\s*)?((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\.[0-9]{2})(?![0-9.,])")
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidates = mutableListOf<Pair<Int, Long>>()
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            if (Regex("(?i)\\b(sub\\s*total|subtotal)\\b").containsMatchIn(line)) return@forEachIndexed
            val tail = line.substring(match.range.last + 1)
            val values = amount.findAll(tail).mapNotNull { parseMoney(it.groupValues[1].replace(",", "")) }.toList()
            val value = values.singleOrNull() ?: if (tail.isBlank()) {
                // OCR sometimes emits the total value on the next line. Only accept an isolated amount.
                lines.getOrNull(index + 1)?.takeIf { amount.matches(it) }?.let { amount.matchEntire(it) }
                    ?.groupValues?.get(1)?.replace(",", "")?.let { parseMoney(it) }
            } else null
            if (value != null) candidates += (if (match.groupValues[1].equals("total", true)) 1 else 2) to value
        }
        val priority = candidates.maxOfOrNull { it.first } ?: return null
        return candidates.filter { it.first == priority }.map { it.second }.distinct().singleOrNull()
    }

    fun parse(text:String):PaymentDetection {
        val money=Regex("(?i)(?:PHP|₱|P(?=\\s*\\d)|amount\\s*[:=]?|total\\s*[:=]?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
            .findAll(text).mapNotNull { parseMoney(it.groupValues[1].replace(",","")) }.distinct().toList()
        val date=detectDate(text)
        val reference=Regex("(?i)(?:reference(?:\\s*(?:no|number))?|ref(?:\\s*no)?|transaction\\s*(?:id|no))\\.?\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9 -]{4,35})")
            .find(text)?.groupValues?.get(1)?.trim()?.replace(" ","")
        val total = receiptTotal(text)
        return PaymentDetection(total ?: money.singleOrNull(),date,reference,
            when { total != null -> "Receipt total detected. Check the total and date; cash tendered and change are not spending."
                money.size>1 -> "Several amounts were found. Enter the payment amount yourself."
                money.isEmpty() -> "No reliable amount found. Enter it manually."
                date==null -> "Check the amount and enter the payment date. The image/alert is only a suggestion."
                else -> "Review the amount, date and loan before recording a payment." })
    }
}
