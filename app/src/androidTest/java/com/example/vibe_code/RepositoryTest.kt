package com.example.vibe_code

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.vibe_code.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: ExpenseDatabase
    private lateinit var repository: ExpenseRepository
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExpenseDatabase::class.java).build()
        repository = ExpenseRepository(db)
    }
    @After fun close() { db.close() }
    private fun schedule(mode: String = "AUTOPAY") = RecurringSchedule(title = "Subscription", amountMinor = 50000,
        mode = mode, cycle = "MONTHLY", rangeStart = LocalDate.parse("2026-01-29").toEpochDay(),
        rangeEnd = LocalDate.parse("2026-02-02").toEpochDay(), expiry = LocalDate.parse("2026-03-29").toEpochDay())

    @Test fun missedCyclesAreUniqueAndRespectExpiry() = runBlocking {
        repository.saveSchedule(schedule())
        repository.reconcile(LocalDate.parse("2026-05-01"))
        repository.reconcile(LocalDate.parse("2026-05-01"))
        assertEquals(3, db.dao().allOccurrences().size)
        assertEquals(3, db.dao().expenses().first().size)
        assertTrue(db.dao().expenses().first().all { it.status == "AWAITING" })
    }
    @Test fun failureThenSuccessUpdatesSameEntryAndDeletionStaysSkipped() = runBlocking {
        repository.saveSchedule(schedule())
        repository.reconcile(LocalDate.parse("2026-01-29"))
        val occurrence = db.dao().allOccurrences().first()
        repository.payment(occurrence.id, false, 50000, 1)
        assertFalse(FinanceRules.counted(db.dao().expenses().first().single()))
        repository.payment(occurrence.id, true, 45000, 2)
        assertEquals(1, db.dao().expenses().first().size)
        assertEquals(45000L, db.dao().expenses().first().single().amountMinor)
        assertTrue(runCatching { repository.payment(occurrence.id, true, 45000, 2) }.isFailure)
        repository.deleteExpense(db.dao().expenses().first().single().id)
        repository.reconcile(LocalDate.parse("2026-01-29"))
        assertTrue(db.dao().expenses().first().isEmpty())
        assertEquals("SKIPPED", db.dao().allOccurrences().single().status)
    }
    @Test fun billRequiresConfirmationAndUsesActualAmount() = runBlocking {
        repository.saveSchedule(schedule("BILL"))
        repository.reconcile(LocalDate.parse("2026-01-29"))
        assertTrue(db.dao().expenses().first().isEmpty())
        repository.payment(db.dao().allOccurrences().single().id, true, 42000, 100)
        assertEquals(42000L, db.dao().expenses().first().single().amountMinor)
        assertEquals(100L, db.dao().expenses().first().single().dateTime)
    }
    @Test fun partialThenFullSettlementAndDeletePreservesHistory() = runBlocking {
        repository.addPerson("Asha")
        val person = db.dao().people().first().single()
        repository.saveExpense(ExpenseModel(title = "Borrowed", amountMinor = 10000, dateTime = 1, purpose = "BORROWED", personId = person.id))
        repository.saveExpense(ExpenseModel(title = "Lent", amountMinor = 20000, dateTime = 2, purpose = "LENT", personId = person.id))
        val borrowed = db.dao().personDebts(person.id).first { it.kind == "BORROWED" }
        repository.settle(borrowed.id, person.id, 2500, 3)
        assertTrue(runCatching { repository.settle(borrowed.id, person.id, 8000, 4) }.isFailure)
        assertEquals(7500L, FinanceRules.remaining(borrowed, db.dao().allSettlements()))
        repository.settlePerson(person.id, 5, true)
        assertNotNull(db.dao().person(person.id)!!.deletedAt)
        assertTrue(db.dao().personDebts(person.id).all { FinanceRules.remaining(it, db.dao().allSettlements()) == 0L })
        val entries = db.dao().expenses().first()
        assertEquals(5, entries.size)
        assertTrue(entries.all { it.personName == "Asha" })
        assertEquals(10000L, entries.filter { it.purpose == "REPAYMENT" && it.direction == "DEBIT" }.sumOf { it.amountMinor })
        assertEquals(20000L, entries.filter { it.purpose == "REPAYMENT" && it.direction == "CREDIT" }.sumOf { it.amountMinor })
    }
    @Test fun editingScheduleDoesNotChangeExistingOccurrence() = runBlocking {
        repository.saveSchedule(schedule())
        repository.reconcile(LocalDate.parse("2026-01-29"))
        val before = db.dao().allOccurrences().single()
        val old = db.dao().allSchedules().single()
        repository.saveSchedule(old.copy(amountMinor = 70000))
        repository.reconcile(LocalDate.parse("2026-03-01"))
        assertEquals(before, db.dao().allOccurrences().first { it.id == before.id })
        assertEquals(70000L, db.dao().allOccurrences().first { it.cycleIndex == 1 }.amountMinor)
    }
    @Test fun cancelledScheduleRetainsHistoryAndStopsGeneration() = runBlocking {
        repository.saveSchedule(schedule())
        repository.reconcile(LocalDate.parse("2026-01-29"))
        repository.scheduleState(db.dao().allSchedules().single().id, "CANCELLED")
        repository.reconcile(LocalDate.parse("2026-05-01"))
        assertEquals(1, db.dao().allOccurrences().size)
        assertEquals(1, db.dao().expenses().first().size)
    }
    @Test fun pausePreventsDeductionForAheadOfTimeOccurrence() = runBlocking {
        val today = LocalDate.now()
        repository.saveSchedule(schedule().copy(rangeStart = today.plusDays(1).toEpochDay(), rangeEnd = today.plusDays(2).toEpochDay(), expiry = null))
        repository.reconcile(today)
        val id = db.dao().allSchedules().single().id
        repository.scheduleState(id, "PAUSED")
        repository.reconcile(today.plusDays(2))
        assertTrue(db.dao().expenses().first().isEmpty())
        repository.scheduleState(id, "ACTIVE")
        repository.reconcile(today.plusDays(1))
        assertEquals(1, db.dao().expenses().first().size)
    }
    @Test fun notificationsOffKeepPendingBillsAndPermissionUnavailableKeepsState() = runBlocking {
        repository.saveSchedule(schedule("BILL").copy(notifyMe = false))
        repository.reconcile(LocalDate.parse("2026-01-29"))
        deliverReminders(ApplicationProvider.getApplicationContext(), repository)
        assertEquals("PENDING", db.dao().allOccurrences().single().status)
        assertNull(db.dao().allOccurrences().single().lastRemindedAt)
    }
    @Test fun repaymentDeletionReopensDebtAndSettledLoanEditIsRejected() = runBlocking {
        repository.addPerson("Asha")
        val person = db.dao().people().first().single()
        repository.saveExpense(ExpenseModel(title = "Loan", amountMinor = 10000, dateTime = 1, purpose = "LENT", personId = person.id))
        val debt = db.dao().personDebts(person.id).single()
        repository.settle(debt.id, person.id, 10000, 2)
        val loan = db.dao().expense(debt.transactionId)!!
        assertTrue(runCatching { repository.saveExpense(loan.copy(amountMinor = 5000)) }.isFailure)
        val repayment = db.dao().allSettlements().single().transactionId
        repository.deleteExpense(repayment)
        assertEquals(10000L, FinanceRules.remaining(debt, db.dao().allSettlements()))
    }
    @Test fun repaymentCorrectionsUpdateDebtAndRejectOverpayment() = runBlocking {
        repository.addPerson("Asha")
        val person = db.dao().people().first().single()
        repository.saveExpense(ExpenseModel(title = "Loan", amountMinor = 10000, dateTime = 1, purpose = "BORROWED", personId = person.id))
        val debt = db.dao().personDebts(person.id).single()
        repository.settle(debt.id, person.id, 5000, 2)
        val payment = db.dao().allSettlements().single().transactionId
        repository.correctPayment(payment, 7500, 3)
        assertEquals(2500L, FinanceRules.remaining(debt, db.dao().allSettlements()))
        assertEquals(7500L, db.dao().expense(payment)!!.amountMinor)
        assertEquals(3L, db.dao().allSettlements().single().dateTime)
        assertTrue(runCatching { repository.correctPayment(payment, 10001, 4) }.isFailure)
        assertEquals(7500L, db.dao().expense(payment)!!.amountMinor)
    }
    @Test fun scheduledCorrectionKeepsOccurrenceAndConfirmationState() = runBlocking {
        repository.saveSchedule(schedule())
        repository.reconcile(LocalDate.parse("2026-01-29"))
        val before = db.dao().expenses().first().single()
        repository.correctPayment(before.id, 70000, 10)
        val after = db.dao().expense(before.id)!!
        assertEquals(before.occurrenceId, after.occurrenceId)
        assertEquals("AWAITING", after.status)
        repository.reconcile(LocalDate.parse("2026-01-29"))
        assertEquals(1, db.dao().expenses().first().size)
        assertEquals(70000L, db.dao().expenses().first().single().amountMinor)
    }
    @Test fun resumingBillPreservesAlreadyPendingOverdueBill() = runBlocking {
        val today = LocalDate.now()
        repository.saveSchedule(schedule("BILL").copy(rangeStart = today.minusDays(10).toEpochDay(), rangeEnd = today.minusDays(8).toEpochDay(), expiry = today.toEpochDay()))
        repository.reconcile(today)
        val id = db.dao().allSchedules().single().id
        repository.scheduleState(id, "PAUSED")
        repository.scheduleState(id, "ACTIVE")
        assertEquals("PENDING", db.dao().allOccurrences().single().status)
    }
}
