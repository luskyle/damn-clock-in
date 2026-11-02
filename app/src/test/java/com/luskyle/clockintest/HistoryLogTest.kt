package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HistoryLogTest {
    @Test fun filtersByExactDateAndKeepsNewestFirst() {
        val entries = HistoryLog.entries("2026-11-02 12:00:00  新记录\n2026-11-02 00:00:00  零点\n2026-11-01 23:59:59  昨天")
        val today = HistoryLog.filter(entries, LocalDate.of(2026, 11, 2))
        assertEquals(2, today.size)
        assertTrue(today.first().text.endsWith("新记录"))
        assertTrue(today.last().text.endsWith("零点"))
        assertEquals(entries, HistoryLog.filter(entries, null))
        assertTrue(HistoryLog.filter(entries, LocalDate.of(2026, 10, 31)).isEmpty())
    }

    @Test fun multilineMessagesStayWithTheirOriginalDate() {
        val entries = HistoryLog.entries("2026-11-02 12:00:00  原因\n详细说明\n2026-11-01 12:00:00  昨天\n昨天的详细说明")
        assertEquals(2, entries.size)
        assertEquals("2026-11-02 12:00:00  原因\n详细说明",
            HistoryLog.filter(entries, LocalDate.of(2026, 11, 2)).single().text)
    }

    @Test fun undatedLegacyTextRemainsVisibleUnderAllDates() {
        val entries = HistoryLog.entries("旧记录\n更多旧内容\n2026-11-02 12:00:00  新记录")
        assertNull(entries.first().date)
        assertEquals(2, HistoryLog.filter(entries, null).size)
        assertEquals(1, HistoryLog.filter(entries, LocalDate.of(2026, 11, 2)).size)
    }

    @Test fun invalidTimestampIsNotAValidDateAndEmptyHistoryHasNoEntries() {
        assertNull(HistoryLog.entries("2026-02-30 12:00:00  非法日期").single().date)
        assertNull(HistoryLog.entries("2026-11-02 25:00:00  非法时间").single().date)
        assertTrue(HistoryLog.entries("\n \n").isEmpty())
        assertTrue(HistoryLog.entries("").isEmpty())
    }
}
