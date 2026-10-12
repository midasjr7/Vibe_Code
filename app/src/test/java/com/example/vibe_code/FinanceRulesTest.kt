package com.example.vibe_code

import com.example.vibe_code.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FinanceRulesTest {
    private fun schedule(start: String, end: String, cycle: String = "MONTHLY") = RecurringSchedule(
        title = "Rent", amountMinor = 10000, mode = "AUTOPAY", cycle = cycle,
        rangeStart = LocalDate.parse(start).toEpochDay(), rangeEnd = LocalDate.parse(end).toEpochDay())

    @Test fun moneyIsExact() {
        assertEquals(10L, FinanceRules.amount("0.10"))
        assertEquals(123456L, FinanceRules.amount("1234.56"))
        listOf("", "0", "-1", "1.001", "NaN", "1e3", "9223372036854775808").forEach { text ->
            assertTrue("Reject $text", runCatching { FinanceRules.amount(text) }.isFailure)
        }
    }
    @Test fun monthlyAnchorsDoNotDriftAfterFebruary() {
        val value = schedule("2024-01-31", "2024-02-02")
        FinanceRules.validate(value)
        assertEquals(LocalDate.parse("2024-02-29") to LocalDate.parse("2024-03-02"), FinanceRules.range(value, 1))
        assertEquals(LocalDate.parse("2024-03-31"), FinanceRules.range(value, 2).first)
    }
    @Test fun arbitraryRangesAndWeeklyCrossMonthRanges() {
        val value = schedule("2026-10-29", "2026-11-02", "WEEKLY")
        FinanceRules.validate(value)
        assertEquals(LocalDate.parse("2026-11-05") to LocalDate.parse("2026-11-09"), FinanceRules.range(value, 1))
    }
    @Test fun rejectInvalidOrOverlappingRanges() {
        listOf(schedule("2026-10-10", "2026-10-09"), schedule("2026-10-01", "2026-11-01"),
            schedule("2026-10-01", "2026-10-08", "WEEKLY"), schedule("2026-01-31", "2026-02-28")).forEach {
            assertTrue(runCatching { FinanceRules.validate(it) }.isFailure)
        }
    }
    @Test fun settlementBalancesAreSeparate() {
        val borrowed = Debt(id = 1, personId = 1, kind = "BORROWED", amountMinor = 10000, transactionId = 1)
        val lent = Debt(id = 2, personId = 1, kind = "LENT", amountMinor = 20000, transactionId = 2)
        val repayments = listOf(Settlement(debtId = 1, transactionId = 3, amountMinor = 2500, dateTime = 0))
        assertEquals(7500L, FinanceRules.remaining(borrowed, repayments))
        assertEquals(20000L, FinanceRules.remaining(lent, repayments))
    }
    @Test fun unconfirmedIncludedAndFailedExcluded() {
        val entry = ExpenseModel(title = "Autopay", amountMinor = 100, dateTime = 0, status = "AWAITING")
        assertTrue(FinanceRules.counted(entry))
        assertFalse(FinanceRules.counted(entry.copy(status = "FAILED")))
    }
    @Test fun monthUsesLocalTimezone() {
        val entry = ExpenseModel(title = "Late expense", amountMinor = 100, dateTime = Instant.parse("2026-10-31T20:00:00Z").toEpochMilli())
        assertTrue(FinanceRules.inMonth(entry, YearMonth.of(2026, 11), ZoneId.of("Asia/Kolkata")))
        assertFalse(FinanceRules.inMonth(entry, YearMonth.of(2026, 10), ZoneId.of("Asia/Kolkata")))
    }
}
