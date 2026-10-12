package com.example.vibe_code

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vibe_code.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LedgerState(val expenses: List<ExpenseModel> = emptyList(), val people: List<Person> = emptyList(),
    val debts: List<Debt> = emptyList(), val settlements: List<Settlement> = emptyList(),
    val schedules: List<RecurringSchedule> = emptyList(), val occurrences: List<BillingOccurrence> = emptyList())
class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as ExpenseApplication).repository
    private val dao = repository.dao
    private val money = combine(dao.expenses(), dao.people(), dao.debts(), dao.settlements()) { e, p, d, s -> LedgerState(e, p, d, s) }
    val state = combine(money, dao.schedules(), dao.occurrences()) { ledger, schedules, occurrences -> ledger.copy(schedules = schedules, occurrences = occurrences) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerState())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun run(action: suspend ExpenseRepository.() -> Unit, completed: () -> Unit = {}) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                repository.action()
                completed()
                // Notification or reconciliation failures must never invite a duplicate save.
                try { repository.reconcile(); deliverReminders(getApplication(), repository) }
                catch (exception: Exception) { android.util.Log.w("ExpenseManager", "Background reconciliation will retry", exception) }
            }
            catch (exception: Exception) { error.value = exception.message ?: "Could not save this change" }
            finally { busy.value = false }
        }
    }
    fun refresh() = run({ reconcile() })
    init { refresh() }
}
