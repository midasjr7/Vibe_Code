package com.example.vibe_code

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import com.example.vibe_code.data.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ExpenseApplication : Application() {
    val repository by lazy { ExpenseRepository(ExpenseDatabase.get(this)) }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("payments", "Bills and autopay", NotificationManager.IMPORTANCE_DEFAULT))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("payment-reconciliation", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PaymentWorker>(12, TimeUnit.HOURS).build())
    }
}
class PaymentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repository = (applicationContext as ExpenseApplication).repository
        repository.reconcile()
        deliverReminders(applicationContext, repository)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
fun notificationsAvailable(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

suspend fun deliverReminders(context: Context, repository: ExpenseRepository) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    if (!notificationsAvailable(context)) return
    repository.db.withTransaction {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toEpochDay()
        val schedules = repository.dao.allSchedules().associateBy { it.id }
        val lines = mutableListOf<String>()
        for (item in repository.dao.allOccurrences()) {
            val schedule = schedules[item.scheduleId] ?: continue
            if (!schedule.notifyMe || schedule.state != "ACTIVE" || item.status !in listOf("PENDING", "AWAITING")) continue
            val balance = item.mode == "AUTOPAY" && today < item.rangeStart && today >= item.rangeStart - schedule.leadDays && item.balanceRemindedAt == null
            val pending = if (item.mode == "AUTOPAY") today > item.rangeEnd else today >= item.rangeStart
            val repeatDue = item.lastRemindedAt == null || now - item.lastRemindedAt >= TimeUnit.DAYS.toMillis(7)
            if (balance || pending && repeatDue) {
                lines += when { balance -> "${item.title}: maintain balance before ${LocalDate.ofEpochDay(item.rangeStart)}"
                    item.mode == "AUTOPAY" -> "${item.title}: did the deduction succeed?"
                    else -> "${item.title}: bill pending" }
                repository.dao.updateOccurrence(item.copy(balanceRemindedAt = if (balance) now else item.balanceRemindedAt,
                    lastRemindedAt = if (pending) now else item.lastRemindedAt))
            }
        }
        if (lines.isNotEmpty()) {
            val intent = Intent(context, MainActivity::class.java).putExtra("openBills", true)
            val pendingIntent = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, "payments").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${lines.size} payment reminder${if (lines.size == 1) "" else "s"}")
                .setContentText(lines.first()).setStyle(NotificationCompat.InboxStyle().also { style -> lines.take(6).forEach { style.addLine(it) } })
                .setContentIntent(pendingIntent).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(context).notify(100, notification) }
            catch (exception: SecurityException) {
                // Roll back reminder timestamps if permission changed during delivery.
                throw IllegalStateException("Notification permission changed; reminders remain pending", exception)
            }
        }
    }
}
