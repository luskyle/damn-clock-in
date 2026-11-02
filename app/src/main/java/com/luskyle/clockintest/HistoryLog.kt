package com.luskyle.clockintest

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

data class HistoryEntry(val date: LocalDate?, val text: String)

object HistoryLog {
    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val prefix = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}  .*$""")

    fun entries(history: String): List<HistoryEntry> {
        val result = mutableListOf<HistoryEntry>()
        for (line in history.lineSequence()) {
            if (line.isBlank()) continue
            val date = if (prefix.matches(line)) {
                try {
                    LocalDateTime.parse(line.take(19), timestamp).toLocalDate()
                } catch (_: DateTimeParseException) {
                    null
                }
            } else null
            if (date != null || result.isEmpty()) result.add(HistoryEntry(date, line))
            else {
                val previous = result.last()
                result[result.lastIndex] = previous.copy(text = previous.text + "\n" + line)
            }
        }
        return result
    }

    fun filter(entries: List<HistoryEntry>, date: LocalDate?): List<HistoryEntry> =
        if (date == null) entries else entries.filter { it.date == date }
}
