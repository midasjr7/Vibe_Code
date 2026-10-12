package com.example.vibe_code.data

import androidx.room.withTransaction
import java.time.*

class ExpenseRepository(val db: ExpenseDatabase) {
    val dao = db.dao()
    suspend fun saveExpense(value: ExpenseModel) = db.withTransaction {
        require(value.title.isNotBlank() && value.amountMinor > 0) { "Title and positive amount are required" }
        require(value.direction in listOf("DEBIT", "CREDIT"))
        require(value.purpose in listOf("GENERAL", "BORROWED", "LENT")) { "Use People to record repayments" }
        val old = if (value.id != 0L) dao.expense(value.id) ?: error("Entry no longer exists") else null
        require(old?.occurrenceId == null) { "Update scheduled payments from Bills" }
        require(old?.purpose != "REPAYMENT") { "Delete a repayment to correct it, then record it again" }
        val debt = old?.let { dao.transactionDebt(it.id) }
        val settlements = dao.allSettlements()
        require(debt == null || settlements.none { it.debtId == debt.id }) { "A settled debt cannot be edited" }
        val person = value.personId?.let { dao.person(it) }
        require(value.personId == null || person?.deletedAt == null && person != null) { "Choose an active person" }
        require(value.purpose == "GENERAL" || person != null) { "Choose a person for borrowed or lent money" }
        val direction = when (value.purpose) { "BORROWED" -> "CREDIT"; "LENT" -> "DEBIT"; else -> value.direction }
        val entry = value.copy(title = value.title.trim(), direction = direction, personName = person?.name, updatedAt = System.currentTimeMillis())
        val id = if (entry.id == 0L) dao.insertExpense(entry) else { dao.updateExpense(entry); entry.id }
        if (debt != null) dao.deleteDebt(debt.id)
        if (entry.purpose != "GENERAL") dao.insertDebt(Debt(personId = person!!.id, kind = entry.purpose, amountMinor = entry.amountMinor, transactionId = id))
    }
    suspend fun deleteExpense(id: Long) = db.withTransaction {
        val entry = dao.expense(id) ?: return@withTransaction
        val person = entry.personId?.let { dao.person(it) }
        require(person?.deletedAt == null) { "History for a deleted person is preserved" }
        val debt = dao.transactionDebt(id)
        require(debt == null || dao.allSettlements().none { it.debtId == debt.id }) { "Delete settlements before deleting this debt" }
        if (debt != null) dao.deleteDebt(debt.id)
        dao.deleteSettlement(id)
        entry.occurrenceId?.let { occurrenceId -> dao.allOccurrences().find { it.id == occurrenceId }?.let { dao.updateOccurrence(it.copy(status = "SKIPPED", transactionId = null)) } }
        dao.deleteExpense(id)
    }
    suspend fun correctPayment(id: Long, amount: Long, dateTime: Long) = db.withTransaction {
        require(amount > 0) { "Amount must be positive" }
        val entry = dao.expense(id) ?: error("Entry no longer exists")
        require(entry.personId == null || dao.person(entry.personId)?.deletedAt == null) { "History for a deleted person is preserved" }
        require(entry.purpose == "REPAYMENT" || entry.occurrenceId != null) { "Use Edit entry for this transaction" }
        if (entry.purpose == "REPAYMENT") {
            val settlements = dao.allSettlements()
            val settlement = settlements.first { it.transactionId == id }
            val debt = dao.personDebts(entry.personId!!).first { it.id == settlement.debtId }
            require(amount <= FinanceRules.remaining(debt, settlements) + settlement.amountMinor) { "Settlement must not exceed the debt amount" }
            dao.updateSettlement(settlement.copy(amountMinor = amount, dateTime = dateTime))
        }
        dao.updateExpense(entry.copy(amountMinor = amount, dateTime = dateTime, updatedAt = System.currentTimeMillis()))
    }
    suspend fun addPerson(name: String) {
        require(name.isNotBlank()) { "Name is required" }
        dao.insertPerson(Person(name = name.trim()))
    }
    private suspend fun settleDebt(debt: Debt, amount: Long, dateTime: Long) {
        val remaining = FinanceRules.remaining(debt, dao.allSettlements())
        require(amount in 1..remaining) { "Settlement must not exceed the remaining amount" }
        val person = dao.person(debt.personId) ?: error("Person no longer exists")
        require(person.deletedAt == null)
        val id = dao.insertExpense(ExpenseModel(title = "Settlement · ${person.name}", amountMinor = amount, dateTime = dateTime,
            direction = if (debt.kind == "BORROWED") "DEBIT" else "CREDIT", purpose = "REPAYMENT", personId = person.id, personName = person.name))
        dao.insertSettlement(Settlement(debtId = debt.id, transactionId = id, amountMinor = amount, dateTime = dateTime))
    }
    suspend fun settle(debtId: Long, personId: Long, amount: Long, dateTime: Long) = db.withTransaction {
        settleDebt(dao.personDebts(personId).first { it.id == debtId }, amount, dateTime)
    }
    suspend fun settlePerson(personId: Long, dateTime: Long, delete: Boolean) = db.withTransaction {
        val person = dao.person(personId) ?: error("Person no longer exists")
        require(person.deletedAt == null)
        for (debt in dao.personDebts(personId)) {
            val remaining = FinanceRules.remaining(debt, dao.allSettlements())
            if (remaining > 0) settleDebt(debt, remaining, dateTime)
        }
        if (delete) dao.updatePerson(person.copy(deletedAt = System.currentTimeMillis()))
    }
    suspend fun saveSchedule(value: RecurringSchedule) = db.withTransaction {
        FinanceRules.validate(value)
        if (value.id == 0L) dao.insertSchedule(value.copy(title = value.title.trim())) else {
            val old = dao.allSchedules().first { it.id == value.id }
            require(old.state != "CANCELLED") { "Cancelled schedules cannot be edited" }
            val lastEnd = dao.allOccurrences().filter { it.scheduleId == value.id }.maxOfOrNull { it.rangeEnd }
            require(lastEnd == null || FinanceRules.range(value, old.nextCycle).first.toEpochDay() > lastEnd) {
                "The next edited range overlaps a generated payment. Choose a later range."
            }
            // Retain generated occurrences; advancing the cursor avoids duplicate cycles after an edit.
            dao.updateSchedule(value.copy(nextCycle = old.nextCycle, state = old.state))
        }
    }
    suspend fun scheduleState(id: Long, state: String) = db.withTransaction {
        val old = dao.allSchedules().first { it.id == id }
        require(old.state != "CANCELLED")
        var cursor = old.nextCycle
        if (old.state == "PAUSED" && state == "ACTIVE") {
            val today = LocalDate.now()
            while (FinanceRules.range(old, cursor).first < today) cursor++
            for (item in dao.allOccurrences().filter { it.scheduleId == id && it.mode == "AUTOPAY" && it.status == "PENDING" && it.rangeStart < today.toEpochDay() }) {
                dao.updateOccurrence(item.copy(status = "SKIPPED"))
            }
        }
        if (state == "CANCELLED") {
            for (item in dao.allOccurrences().filter { it.scheduleId == id && it.status == "PENDING" && it.rangeStart > LocalDate.now().toEpochDay() })
                dao.updateOccurrence(item.copy(status = "SKIPPED"))
        }
        dao.updateSchedule(old.copy(state = state, nextCycle = cursor))
    }
    suspend fun notifySchedule(id: Long, enabled: Boolean) = db.withTransaction {
        dao.allSchedules().first { it.id == id }.let { dao.updateSchedule(it.copy(notifyMe = enabled)) }
    }
    suspend fun reconcile(today: LocalDate = LocalDate.now()) = db.withTransaction {
        val existing = dao.allOccurrences().map { it.scheduleId to it.cycleIndex }.toMutableSet()
        for (schedule in dao.allSchedules().filter { it.state == "ACTIVE" }) {
            var cursor = schedule.nextCycle
            while (true) {
                val (start, end) = FinanceRules.range(schedule, cursor)
                if (schedule.expiry != null && start.toEpochDay() > schedule.expiry) break
                // Create the occurrence ahead of its range for the pre-payment reminder.
                if (start > today.plusDays(schedule.leadDays.toLong())) break
                if (existing.add(schedule.id to cursor)) {
                    dao.insertOccurrence(BillingOccurrence(scheduleId = schedule.id, cycleIndex = cursor, title = schedule.title,
                        amountMinor = schedule.amountMinor, category = schedule.category, mode = schedule.mode,
                        rangeStart = start.toEpochDay(), rangeEnd = end.toEpochDay()))
                }
                cursor++
            }
            if (cursor != schedule.nextCycle) dao.updateSchedule(schedule.copy(nextCycle = cursor))
        }
        val schedules = dao.allSchedules().associateBy { it.id }
        for (occurrence in dao.allOccurrences()) {
            if (occurrence.mode == "AUTOPAY" && occurrence.status == "PENDING" && occurrence.rangeStart <= today.toEpochDay()
                && schedules[occurrence.scheduleId]?.state == "ACTIVE") {
                val id = dao.insertExpense(ExpenseModel(title = occurrence.title, amountMinor = occurrence.amountMinor,
                    dateTime = LocalDate.ofEpochDay(occurrence.rangeStart).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    category = occurrence.category, status = "AWAITING", occurrenceId = occurrence.id))
                dao.updateOccurrence(occurrence.copy(status = "AWAITING", transactionId = id))
            }
        }
    }
    suspend fun payment(id: Long, success: Boolean, amount: Long, dateTime: Long) = db.withTransaction {
        val occurrence = dao.allOccurrences().first { it.id == id }
        require(occurrence.status !in listOf("PAID", "SKIPPED")) { "This payment is already resolved" }
        require(amount > 0)
        val old = occurrence.transactionId?.let { dao.expense(it) }
        val transactionId = if (success) {
            if (old == null) dao.insertExpense(ExpenseModel(title = occurrence.title, amountMinor = amount, dateTime = dateTime, category = occurrence.category, occurrenceId = id))
            else { dao.updateExpense(old.copy(amountMinor = amount, dateTime = dateTime, status = "CONFIRMED", updatedAt = System.currentTimeMillis())); old.id }
        } else {
            require(occurrence.mode == "AUTOPAY")
            if (old != null) dao.updateExpense(old.copy(status = "FAILED", updatedAt = System.currentTimeMillis()))
            old?.id
        }
        dao.updateOccurrence(occurrence.copy(status = if (success) "PAID" else "FAILED", transactionId = transactionId))
    }
}
