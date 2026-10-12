package com.example.vibe_code.data

import androidx.room.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Entity(indices = [Index(value = ["occurrenceId"], unique = true)])
data class ExpenseModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val amountMinor: Long, val dateTime: Long,
    val category: String = "Other", val direction: String = "DEBIT",
    val purpose: String = "GENERAL", val personId: Long? = null,
    val personName: String? = null, val notes: String = "",
    val status: String = "CONFIRMED", val occurrenceId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt
)
@Entity data class Person(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val deletedAt: Long? = null)
@Entity(indices = [Index("personId")]) data class Debt(@PrimaryKey(autoGenerate = true) val id: Long = 0, val personId: Long, val kind: String, val amountMinor: Long, val transactionId: Long)
@Entity(indices = [Index("debtId"), Index(value = ["transactionId"], unique = true)]) data class Settlement(@PrimaryKey(autoGenerate = true) val id: Long = 0, val debtId: Long, val transactionId: Long, val amountMinor: Long, val dateTime: Long)
@Entity data class RecurringSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String,
    val amountMinor: Long, val category: String = "Other", val mode: String,
    val cycle: String, val rangeStart: Long, val rangeEnd: Long,
    val expiry: Long? = null, val notifyMe: Boolean = false,
    val leadDays: Int = 1, val state: String = "ACTIVE",
    val nextCycle: Int = 0
)
@Entity(indices = [Index(value = ["scheduleId", "cycleIndex"], unique = true)])
data class BillingOccurrence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val scheduleId: Long,
    val cycleIndex: Int, val title: String, val amountMinor: Long,
    val category: String, val mode: String, val rangeStart: Long, val rangeEnd: Long,
    val status: String = "PENDING", val transactionId: Long? = null,
    val balanceRemindedAt: Long? = null, val lastRemindedAt: Long? = null
)

object FinanceRules {
    fun amount(text: String): Long {
        require(Regex("[0-9]+(\\.[0-9]{1,2})?").matches(text.trim())) { "Enter a positive amount with at most two decimal places" }
        val value = try { BigDecimal(text.trim()).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("Amount is too large") }
        require(value > 0) { "Amount must be greater than zero" }
        require(value <= 999999999999L) { "Amount must not exceed INR 9,999,999,999.99" }
        return value
    }
    fun range(schedule: RecurringSchedule, index: Int): Pair<LocalDate, LocalDate> {
        val start = LocalDate.ofEpochDay(schedule.rangeStart)
        val end = LocalDate.ofEpochDay(schedule.rangeEnd)
        return if (schedule.cycle == "WEEKLY") start.plusWeeks(index.toLong()) to end.plusWeeks(index.toLong())
        else start.plusMonths(index.toLong()) to end.plusMonths(index.toLong())
    }
    fun validate(schedule: RecurringSchedule) {
        require(schedule.title.isNotBlank()) { "Title is required" }
        require(schedule.amountMinor > 0) { "Amount must be positive" }
        require(schedule.cycle in listOf("WEEKLY", "MONTHLY")) { "Invalid cycle" }
        require(schedule.mode in listOf("BILL", "AUTOPAY")) { "Invalid schedule mode" }
        require(schedule.rangeEnd >= schedule.rangeStart) { "End date must be on or after start date" }
        require(schedule.expiry == null || schedule.expiry >= schedule.rangeStart) { "Expiry must be on or after start date" }
        // A Gregorian calendar repeats every 400 years. Check monthly clamping across that period.
        val checks = if (schedule.cycle == "WEEKLY") 1 else 4800
        for (i in 0 until checks) require(range(schedule, i).second < range(schedule, i + 1).first) { "Payment ranges must not overlap the next cycle" }
    }
    fun remaining(debt: Debt, settlements: List<Settlement>): Long = debt.amountMinor - settlements.filter { it.debtId == debt.id }.sumOf { it.amountMinor }
    fun counted(expense: ExpenseModel) = expense.status != "FAILED"
    fun inMonth(expense: ExpenseModel, month: YearMonth, zone: java.time.ZoneId) = YearMonth.from(java.time.Instant.ofEpochMilli(expense.dateTime).atZone(zone)) == month
}
