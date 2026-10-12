package com.example.vibe_code.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.example.vibe_code.*
import com.example.vibe_code.data.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val Green = Color(0xFF146B58)
private val Red = Color(0xFFBA4545)
private fun money(amount: Long) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(BigDecimal.valueOf(amount, 2))
private fun amountText(amount: Long) = BigDecimal.valueOf(amount, 2).toPlainString()
private fun label(value: String) = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun timestamp(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private val DateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })
private val OptionalDateSaver = Saver<LocalDate?, String>(save = { it?.toString() ?: "" }, restore = { it.takeIf(String::isNotEmpty)?.let(LocalDate::parse) })
private val TimeSaver = Saver<LocalTime, String>(save = { it.toString() }, restore = { LocalTime.parse(it) })
@Composable private fun rememberedDate(initial: LocalDate) = rememberSaveable(stateSaver = DateSaver) { mutableStateOf(initial) }
@Composable private fun rememberedOptionalDate(initial: LocalDate?) = rememberSaveable(stateSaver = OptionalDateSaver) { mutableStateOf(initial) }
@Composable private fun rememberedTime(initial: LocalTime) = rememberSaveable(stateSaver = TimeSaver) { mutableStateOf(initial) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseApp(model: ExpenseViewModel, openBills: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("preferences", Context.MODE_PRIVATE) }
    var theme by rememberSaveable { mutableStateOf(prefs.getString("theme", "System") ?: "System") }
    var tab by rememberSaveable { mutableIntStateOf(if (openBills) 2 else prefs.getInt("homeTab", 0).coerceIn(0, 4)) }
    val dark = theme == "Dark" || theme == "System" && isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF88D9BD), secondary = Color(0xFFB4CDBE))
        else lightColorScheme(primary = Green, secondary = Color(0xFF55756A), background = Color(0xFFF4F7F3), surface = Color(0xFFFCFDF9))
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var expenseDialog by remember { mutableStateOf<ExpenseModel?>(null) }
    var addingExpense by rememberSaveable { mutableStateOf(false) }
    var scheduleDialog by remember { mutableStateOf<RecurringSchedule?>(null) }
    var addingSchedule by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAvailable(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopAppBar(title = { Column { Text("Expense Manager", fontWeight = FontWeight.Bold); Text("Your money, on your device", style = MaterialTheme.typography.labelMedium) } }) },
            bottomBar = { NavigationBar {
                listOf("Overview", "Entries", "Bills", "People", "Settings").forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◔", "↕", "▤", "♙", "⚙")[index]) }, label = { Text(name) })
                }
            } },
            floatingActionButton = { if (tab == 0 || tab == 1 || tab == 2) ExtendedFloatingActionButton(
                onClick = { if (tab == 2) addingSchedule = true else addingExpense = true }) { Text(if (tab == 2) "+ Bill / autopay" else "+ Add entry") } }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    0 -> Overview(state, prefs)
                    1 -> Entries(state, model, { expenseDialog = it })
                    2 -> Bills(state, model, { scheduleDialog = it }, requestNotifications)
                    3 -> People(state, model)
                    4 -> SettingsScreen(theme, { theme = it; prefs.edit().putString("theme", it).apply() }, prefs, requestNotifications)
                }
            }
        }
        if (error != null) AlertDialog(onDismissRequest = { model.error.value = null }, title = { Text("Could not complete change") },
            text = { Text(error!!) }, confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("OK") } })
        if (addingExpense || expenseDialog != null) ExpenseForm(expenseDialog, state.people.filter { it.deletedAt == null }, busy,
            { addingExpense = false; expenseDialog = null }) { value -> model.run({ saveExpense(value) }) { addingExpense = false; expenseDialog = null } }
        if (addingSchedule || scheduleDialog != null) ScheduleForm(scheduleDialog, busy,
            { addingSchedule = false; scheduleDialog = null }) { value ->
            model.run({ saveSchedule(value) }) { addingSchedule = false; scheduleDialog = null; if (value.notifyMe) requestNotifications() }
        }
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
@Composable private fun Empty(text: String) { Text(text, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Bar(title: String, amount: Long, maximum: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title); Text(money(amount), fontWeight = FontWeight.Medium) }
        LinearProgressIndicator(progress = { if (maximum == 0L) 0f else (amount.toDouble() / maximum).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(9.dp))
    }
}
@Composable private fun Overview(state: LedgerState, prefs: android.content.SharedPreferences) {
    var monthText by rememberSaveable { mutableStateOf(prefs.getString("overviewMonth", YearMonth.now().toString()) ?: YearMonth.now().toString()) }
    val month = runCatching { YearMonth.parse(monthText) }.getOrDefault(YearMonth.now())
    val zone = ZoneId.systemDefault()
    val entries = state.expenses.filter { FinanceRules.counted(it) && FinanceRules.inMonth(it, month, zone) }
    val debits = entries.filter { it.direction == "DEBIT" }
    val out = debits.sumOf { it.amountMinor }
    val income = entries.filter { it.direction == "CREDIT" }.sumOf { it.amountMinor }
    fun changeMonth(value: YearMonth) { monthText = value.toString(); prefs.edit().putString("overviewMonth", monthText).apply() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { changeMonth(month.minusMonths(1)) }) { Text("‹ Previous") }
            Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")), fontWeight = FontWeight.Bold)
            TextButton(onClick = { changeMonth(month.plusMonths(1)) }) { Text("Next ›") }
        } }
        item { Section("Monthly overview") {
            Text(money(out), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Total outflow · includes lending and repayments")
            Text("Money in  ${money(income)}", color = MaterialTheme.colorScheme.primary)
            Text("Net flow  ${money(income - out)}")
            val awaiting = debits.filter { it.status == "AWAITING" }.sumOf { it.amountMinor }
            if (awaiting > 0) Text("Includes ${money(awaiting)} awaiting deduction confirmation", color = MaterialTheme.colorScheme.error)
        } }
        item { Section("Category outflows") {
            if (debits.isEmpty()) Empty("Add your first entry to see the graph.")
            debits.groupBy { it.category }.mapValues { it.value.sumOf { e -> e.amountMinor } }.forEach { (category, amount) -> Bar(category, amount, out) }
        } }
        item { Section("Weekly outflows · Monday start") {
            val firstMonday = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekly = generateSequence(firstMonday) { it.plusWeeks(1) }.takeWhile { it <= month.atEndOfMonth() }.map { start ->
                val amount = debits.filter { val date = Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate(); date >= start && date < start.plusWeeks(1) }.sumOf { it.amountMinor }
                Triple(start.coerceAtLeast(month.atDay(1)), start.plusDays(6).coerceAtMost(month.atEndOfMonth()), amount)
            }.toList()
            weekly.forEach { (start, end, amount) -> Bar("${start.dayOfMonth}–${end.dayOfMonth} ${month.month.name.take(3).lowercase()}", amount, weekly.maxOfOrNull { it.third } ?: 0) }
        } }
        item { Section("Outstanding money") {
            val borrowed = state.debts.filter { it.kind == "BORROWED" }.sumOf { FinanceRules.remaining(it, state.settlements) }
            val lent = state.debts.filter { it.kind == "LENT" }.sumOf { FinanceRules.remaining(it, state.settlements) }
            Text("You owe  ${money(borrowed)}")
            Text("Owed to you  ${money(lent)}")
        } }
    }
}

@Composable private fun Entries(state: LedgerState, model: ExpenseViewModel, edit: (ExpenseModel) -> Unit) {
    var delete by remember { mutableStateOf<ExpenseModel?>(null) }
    var correction by remember { mutableStateOf<ExpenseModel?>(null) }
    var filter by rememberSaveable { mutableStateOf("All") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Choices("Show", listOf("All", "Debit", "Credit"), filter) { filter = it } }
        val entries = state.expenses.filter { filter == "All" || it.direction == filter.uppercase() }
        if (entries.isEmpty()) item { Empty("No entries yet. Add an expense, income, or borrowed/lent amount.") }
        items(entries, key = { it.id }) { entry -> Section(entry.title) {
            Text("${if (entry.direction == "DEBIT") "−" else "+"}${money(entry.amountMinor)}", style = MaterialTheme.typography.titleLarge,
                color = if (entry.direction == "DEBIT") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("${entry.category} · ${label(entry.purpose)} · ${Instant.ofEpochMilli(entry.dateTime).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))}")
            entry.personName?.let { Text("Person: $it") }
            if (entry.notes.isNotBlank()) Text(entry.notes)
            if (entry.status != "CONFIRMED") Text(if (entry.status == "FAILED") "Failed · excluded from totals" else "Awaiting deduction confirmation", color = MaterialTheme.colorScheme.error)
            val preserved = state.people.any { it.id == entry.personId && it.deletedAt != null }
            Row {
                if (!preserved) TextButton(onClick = { if (entry.occurrenceId != null || entry.purpose == "REPAYMENT") correction = entry else edit(entry) }) { Text("Edit") }
                if (!preserved) TextButton(onClick = { delete = entry }) { Text("Delete") }
                if (entry.occurrenceId != null) Text("Scheduled payment", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
            }
        } }
    }
    correction?.let { entry -> PaymentForm("Correct payment · ${entry.title}", entry.amountMinor, { correction = null }, entry.dateTime) { amount, time ->
        model.run({ correctPayment(entry.id, amount, time) }) { correction = null }
    } }
    delete?.let { entry -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete entry?") },
        text = { Text("Delete ${entry.title} (${money(entry.amountMinor)})? Repayment deletion reopens the debt. A scheduled payment will be marked skipped.") },
        confirmButton = { TextButton(onClick = { model.run({ deleteExpense(entry.id) }) { delete = null } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
}

@Composable private fun Bills(state: LedgerState, model: ExpenseViewModel, edit: (RecurringSchedule) -> Unit, requestNotifications: () -> Unit) {
    var payment by remember { mutableStateOf<BillingOccurrence?>(null) }
    var cancel by remember { mutableStateOf<RecurringSchedule?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Bills & autopay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Your date ranges define each cycle. Autopay records expenses automatically; you confirm the deduction.") }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = history, onCheckedChange = { history = it }); Text("Show resolved payment history", Modifier.padding(start = 10.dp)) } }
        val occurrences = state.occurrences.filter { history || it.status !in listOf("PAID", "SKIPPED") }
        if (occurrences.isEmpty()) item { Empty("No pending payments. Add a bill or autopay schedule.") }
        items(occurrences, key = { "occurrence-${it.id}" }) { item -> Section(item.title) {
            Text("${money(item.amountMinor)} · ${label(item.mode)}")
            state.expenses.find { it.id == item.transactionId }?.let { if (it.amountMinor != item.amountMinor) Text("Recorded amount: ${money(it.amountMinor)}") }
            Text("${LocalDate.ofEpochDay(item.rangeStart)} → ${LocalDate.ofEpochDay(item.rangeEnd)}")
            val status = when { item.status == "AWAITING" -> "Awaiting deduction confirmation"
                item.status == "PENDING" && item.mode == "AUTOPAY" -> "Upcoming autopay · maintain balance"
                item.status == "PENDING" && today > item.rangeEnd -> "Overdue bill"
                item.status == "PENDING" -> "Pending bill"; else -> label(item.status) }
            Text(status, color = MaterialTheme.colorScheme.primary)
            if (item.status == "AWAITING" && today <= item.rangeEnd) Text("Confirmation is requested after the range ends; you can confirm early.", style = MaterialTheme.typography.bodySmall)
            if (item.status !in listOf("PAID", "SKIPPED") && (item.mode == "BILL" || item.transactionId != null)) Row {
                TextButton(onClick = { payment = item }) { Text(if (item.mode == "AUTOPAY") "Deduction succeeded" else "Mark paid") }
                if (item.mode == "AUTOPAY" && item.status != "FAILED") TextButton(onClick = { model.run({ payment(item.id, false, item.amountMinor, System.currentTimeMillis()) }) }) { Text("Failed") }
            }
        } }
        item { Text("Schedules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(state.schedules, key = { "schedule-${it.id}" }) { schedule -> Section(schedule.title) {
            Text("${label(schedule.mode)} · ${label(schedule.cycle)} · ${money(schedule.amountMinor)}")
            Text("Anchor: ${LocalDate.ofEpochDay(schedule.rangeStart)} → ${LocalDate.ofEpochDay(schedule.rangeEnd)}")
            val expired = schedule.expiry?.let { it < today } == true
            Text(if (expired) "Expired" else label(schedule.state))
            schedule.expiry?.let { Text("Expiry: ${LocalDate.ofEpochDay(it)}") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = schedule.notifyMe, onCheckedChange = { enabled -> model.run({ notifySchedule(schedule.id, enabled) }) { if (enabled) requestNotifications() } })
                Text("Notify me", Modifier.padding(start = 10.dp))
            }
            if (schedule.state != "CANCELLED") Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { edit(schedule) }) { Text("Edit future cycles") }
                if (!expired) TextButton(onClick = { model.run({ scheduleState(schedule.id, if (schedule.state == "ACTIVE") "PAUSED" else "ACTIVE") }) }) { Text(if (schedule.state == "ACTIVE") "Pause" else "Resume") }
                TextButton(onClick = { cancel = schedule }) { Text("Cancel") }
            }
        } }
    }
    payment?.let { item -> PaymentForm("${if (item.mode == "AUTOPAY") "Confirm deduction" else "Mark bill paid"} · ${item.title}", item.amountMinor,
        { payment = null }) { amount, time -> model.run({ payment(item.id, true, amount, time) }) { payment = null } } }
    cancel?.let { schedule -> AlertDialog(onDismissRequest = { cancel = null }, title = { Text("Cancel future cycles?") },
        text = { Text("Future entries and reminders for ${schedule.title} will stop. Existing payments stay in your history. Cancel the bank mandate separately if needed.") },
        confirmButton = { TextButton(onClick = { model.run({ scheduleState(schedule.id, "CANCELLED") }) { cancel = null } }) { Text("Cancel schedule") } },
        dismissButton = { TextButton(onClick = { cancel = null }) { Text("Keep") } }) }
}

@Composable private fun People(state: LedgerState, model: ExpenseViewModel) {
    var add by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var settle by remember { mutableStateOf<Debt?>(null) }
    var personAction by remember { mutableStateOf<Pair<Person, Boolean>?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("People & settlements", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { add = true }) { Text("+ Person") }
        } }
        val people = state.people.filter { it.deletedAt == null }
        if (people.isEmpty()) item { Empty("Add a person, then use Add entry to record borrowed or lent money.") }
        items(people, key = { it.id }) { person -> Section(person.name) {
            val debts = state.debts.filter { it.personId == person.id }
            val owe = debts.filter { it.kind == "BORROWED" }.sumOf { FinanceRules.remaining(it, state.settlements) }
            val owed = debts.filter { it.kind == "LENT" }.sumOf { FinanceRules.remaining(it, state.settlements) }
            Text("You owe: ${money(owe)} · Owed to you: ${money(owed)}")
            debts.filter { FinanceRules.remaining(it, state.settlements) > 0 }.forEach { debt ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${label(debt.kind)}: ${money(FinanceRules.remaining(debt, state.settlements))}", Modifier.weight(1f))
                    TextButton(onClick = { settle = debt }) { Text("Repay / settle") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (owe > 0 || owed > 0) TextButton(onClick = { personAction = person to false }) { Text("Mark settlement done") }
                TextButton(onClick = { personAction = person to true }) { Text("Delete person") }
            }
            val settlements = state.settlements.filter { s -> debts.any { it.id == s.debtId } }
            if (settlements.isNotEmpty()) Text("${settlements.size} settlement(s) recorded · history is kept in Entries", style = MaterialTheme.typography.bodySmall)
        } }
        val deleted = state.people.count { it.deletedAt != null }
        if (deleted > 0) item { Text("$deleted deleted person(s). Their names and settlements remain in transaction history.", style = MaterialTheme.typography.bodySmall) }
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Add person") }, text = { Field("Name", name) { name = it } },
        confirmButton = { TextButton(onClick = { model.run({ addPerson(name) }) { add = false; name = "" } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { add = false }) { Text("Cancel") } })
    settle?.let { debt -> PaymentForm("Record settlement · ${label(debt.kind)}", FinanceRules.remaining(debt, state.settlements), { settle = null }) { amount, time ->
        model.run({ settle(debt.id, debt.personId, amount, time) }) { settle = null }
    } }
    personAction?.let { (person, delete) ->
        val debts = state.debts.filter { it.personId == person.id }
        val owe = debts.filter { it.kind == "BORROWED" }.sumOf { FinanceRules.remaining(it, state.settlements) }
        val owed = debts.filter { it.kind == "LENT" }.sumOf { FinanceRules.remaining(it, state.settlements) }
        FullSettlementForm(person.name, owe, owed, delete, { personAction = null }) { time -> model.run({ settlePerson(person.id, time, delete) }) { personAction = null } }
    }
}

@Composable private fun SettingsScreen(theme: String, changeTheme: (String) -> Unit, prefs: android.content.SharedPreferences, request: () -> Unit) {
    val context = LocalContext.current
    var home by remember { mutableStateOf(listOf("Overview", "Entries", "Bills", "People", "Settings")[prefs.getInt("homeTab", 0).coerceIn(0, 4)]) }
    var notifications by remember { mutableStateOf(notificationsAvailable(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifications = notificationsAvailable(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section("Preferences") {
            Choices("Theme", listOf("System", "Light", "Dark"), theme, changeTheme)
            Selector("Start screen", listOf("Overview", "Entries", "Bills", "People", "Settings"), home) { home = it; prefs.edit().putInt("homeTab", listOf("Overview", "Entries", "Bills", "People", "Settings").indexOf(it)).apply() }
            Text("Currency: INR · Weeks start Monday")
            LaunchedEffect(Unit) { prefs.edit().putString("currency", "INR").apply() }
        }
        Section("Notifications") {
            Text(if (notifications) "Device notifications are available. Enable Notify me on each schedule." else "Notifications are unavailable. Pending payments still appear in Bills.")
            TextButton(onClick = request) { Text("Enable notification permission") }
            TextButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) { Text("Open device notification settings") }
            Text("Reminders can be delayed by Android. Opening the app checks missed cycles and payments.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Local storage") {
            Text("Records are stored only on this device using Room. Preferences use SharedPreferences. Cloud backup and device-transfer backup are disabled.")
            Text("Uninstalling or clearing app data removes your records.")
        }
    }
}

@Composable private fun Field(title: String, value: String, decimal: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(title) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun Choices(title: String, options: List<String>, selected: String, change: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { FilterChip(selected = selected == it, onClick = { change(it) }, label = { Text(label(it)) }) }
        }
    }
}
@Composable private fun Selector(title: String, options: List<String>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$title: $selected ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false }) }
        }
    }
}
@Composable private fun DateField(title: String, value: LocalDate?, change: (LocalDate) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val initial = value ?: LocalDate.now()
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth()) { Text("$title: ${value ?: "Choose date"}") }
}
@Composable private fun TimeField(value: LocalTime, change: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, minute -> change(LocalTime.of(hour, minute)) }, value.hour, value.minute, true).show() }, modifier = Modifier.fillMaxWidth()) {
        Text("Time: ${value.format(DateTimeFormatter.ofPattern("HH:mm"))}")
    }
}
@Composable private fun FormDialog(title: String, close: () -> Unit, save: () -> Unit, busy: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }, confirmButton = { TextButton(onClick = save, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun ExpenseForm(original: ExpenseModel?, people: List<Person>, busy: Boolean, close: () -> Unit, save: (ExpenseModel) -> Unit) {
    var title by rememberSaveable { mutableStateOf(original?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(original?.let { amountText(it.amountMinor) } ?: "") }
    var category by rememberSaveable { mutableStateOf(original?.category ?: "Other") }
    var direction by rememberSaveable { mutableStateOf(original?.direction ?: "DEBIT") }
    var purpose by rememberSaveable { mutableStateOf(original?.purpose ?: "GENERAL") }
    var personId by rememberSaveable { mutableStateOf(original?.personId) }
    var notes by rememberSaveable { mutableStateOf(original?.notes ?: "") }
    val initial = original?.let { Instant.ofEpochMilli(it.dateTime).atZone(ZoneId.systemDefault()) } ?: ZonedDateTime.now()
    var date by rememberedDate(initial.toLocalDate())
    var time by rememberedTime(initial.toLocalTime().withSecond(0).withNano(0))
    var validation by remember { mutableStateOf<String?>(null) }
    FormDialog(if (original == null) "Add entry" else "Edit entry", close, {
        try {
            require(title.isNotBlank()) { "Title is required" }
            save((original ?: ExpenseModel(title = title, amountMinor = 1, dateTime = 0)).copy(title = title, amountMinor = FinanceRules.amount(amount),
                dateTime = timestamp(date, time), category = category, direction = direction, purpose = purpose, personId = personId, notes = notes))
        } catch (e: IllegalArgumentException) { validation = e.message }
    }, busy) {
        Field("Title", title) { title = it }
        Field("Amount (INR)", amount, true) { amount = it }
        Choices("Category", listOf("Grocery", "Other"), category) { category = it }
        Choices("Purpose", listOf("GENERAL", "BORROWED", "LENT"), purpose) { purpose = it; if (it == "BORROWED") direction = "CREDIT"; if (it == "LENT") direction = "DEBIT" }
        if (purpose == "GENERAL") Choices("Money flow", listOf("DEBIT", "CREDIT"), direction) { direction = it }
        else Text(if (purpose == "BORROWED") "Borrowed money is a credit; repayment is a debit." else "Lent money is a debit; repayment is a credit.")
        PersonSelector(people, personId) { personId = it }
        if (purpose != "GENERAL" && people.isEmpty()) Text("Add a person from People first.", color = MaterialTheme.colorScheme.error)
        DateField("Date", date) { date = it }
        TimeField(time) { time = it }
        Field("Notes (optional)", notes) { notes = it }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
@Composable private fun PersonSelector(people: List<Person>, selected: Long?, change: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Person: ${people.find { it.id == selected }?.name ?: "None (optional)"} ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { change(null); expanded = false })
            people.forEach { person -> DropdownMenuItem(text = { Text(person.name) }, onClick = { change(person.id); expanded = false }) }
        }
    }
}

@Composable private fun ScheduleForm(original: RecurringSchedule?, busy: Boolean, close: () -> Unit, save: (RecurringSchedule) -> Unit) {
    var title by rememberSaveable { mutableStateOf(original?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(original?.let { amountText(it.amountMinor) } ?: "") }
    var category by rememberSaveable { mutableStateOf(original?.category ?: "Other") }
    var mode by rememberSaveable { mutableStateOf(original?.mode ?: "BILL") }
    var cycle by rememberSaveable { mutableStateOf(original?.cycle ?: "MONTHLY") }
    var start by rememberedOptionalDate(original?.let { LocalDate.ofEpochDay(it.rangeStart) })
    var end by rememberedOptionalDate(original?.let { LocalDate.ofEpochDay(it.rangeEnd) })
    var expiry by rememberedOptionalDate(original?.expiry?.let { LocalDate.ofEpochDay(it) })
    var notify by rememberSaveable { mutableStateOf(original?.notifyMe ?: false) }
    var lead by rememberSaveable { mutableStateOf((original?.leadDays ?: 1).toString()) }
    var validation by remember { mutableStateOf<String?>(null) }
    FormDialog(if (original == null) "Add bill / autopay" else "Edit future cycles", close, {
        try {
            require(start != null && end != null) { "Choose both dates of the payment range" }
            val leadDays = lead.toIntOrNull() ?: throw IllegalArgumentException("Enter reminder lead days")
            require(leadDays in 0..30) { "Reminder lead time must be 0–30 days" }
            val value = RecurringSchedule(id = original?.id ?: 0, title = title, amountMinor = FinanceRules.amount(amount), category = category,
                mode = mode, cycle = cycle, rangeStart = start!!.toEpochDay(), rangeEnd = end!!.toEpochDay(), expiry = expiry?.toEpochDay(), notifyMe = notify, leadDays = leadDays)
            FinanceRules.validate(value)
            save(value)
        } catch (e: IllegalArgumentException) { validation = e.message }
    }, busy) {
        Field("Title", title) { title = it }
        Field("Expected amount (INR)", amount, true) { amount = it }
        Choices("Category", listOf("Grocery", "Other"), category) { category = it }
        Choices("Payment mode", listOf("BILL", "AUTOPAY"), mode) { mode = it }
        Choices("Cycle", listOf("WEEKLY", "MONTHLY"), cycle) { cycle = it }
        Text("Choose your first cycle's range. Both dates repeat according to the selected cycle.", style = MaterialTheme.typography.bodySmall)
        DateField("Range start", start) { start = it }
        DateField("Range end", end) { end = it }
        DateField("Expiry (optional)", expiry) { expiry = it }
        if (expiry != null) TextButton(onClick = { expiry = null }) { Text("Remove expiry") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(notify, onCheckedChange = { notify = it }); Text("Notify me", Modifier.padding(start = 10.dp)) }
        if (mode == "AUTOPAY") Field("Balance reminder: days before range", lead, true) { lead = it }
        Text("Notifications off: pending payments remain in the app.", style = MaterialTheme.typography.bodySmall)
        if (original != null) Text("Generated occurrences keep their original range and amount. Your edits change only future cycles.", style = MaterialTheme.typography.bodySmall)
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun PaymentForm(title: String, initialAmount: Long, close: () -> Unit, initialDateTime: Long = System.currentTimeMillis(), save: (Long, Long) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(amountText(initialAmount)) }
    val initial = Instant.ofEpochMilli(initialDateTime).atZone(ZoneId.systemDefault())
    var date by rememberedDate(initial.toLocalDate())
    var time by rememberedTime(initial.toLocalTime().withSecond(0).withNano(0))
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(title, close, {
        try { save(FinanceRules.amount(amount), timestamp(date, time)) } catch (e: IllegalArgumentException) { error = e.message }
    }) {
        Field("Actual amount (INR)", amount, true) { amount = it }
        DateField("Payment date", date) { date = it }
        TimeField(time) { time = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
@Composable private fun FullSettlementForm(name: String, owe: Long, owed: Long, delete: Boolean, close: () -> Unit, save: (Long) -> Unit) {
    var date by rememberedDate(LocalDate.now())
    var time by rememberedTime(LocalTime.now().withSecond(0).withNano(0))
    FormDialog(if (delete) "Settle & delete $name" else "Mark settlement done · $name", close, { save(timestamp(date, time)) }) {
        Text("Remaining borrowed: ${money(owe)}\nRemaining lent: ${money(owed)}")
        if (owe > 0 || owed > 0) Text("Saving records a debit for borrowed repayments and a credit for lent repayments. These amounts are settled separately.")
        if (delete) Text("The person will leave your active list. Their name and financial history are preserved.")
        DateField("Settlement date", date) { date = it }
        TimeField(time) { time = it }
    }
}
