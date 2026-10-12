package com.example.vibe_code.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM ExpenseModel ORDER BY dateTime DESC") fun expenses(): Flow<List<ExpenseModel>>
    @Query("SELECT * FROM Person ORDER BY name") fun people(): Flow<List<Person>>
    @Query("SELECT * FROM Debt") fun debts(): Flow<List<Debt>>
    @Query("SELECT * FROM Settlement") fun settlements(): Flow<List<Settlement>>
    @Query("SELECT * FROM RecurringSchedule ORDER BY rangeStart") fun schedules(): Flow<List<RecurringSchedule>>
    @Query("SELECT * FROM BillingOccurrence ORDER BY rangeStart DESC") fun occurrences(): Flow<List<BillingOccurrence>>
    @Query("SELECT * FROM RecurringSchedule") suspend fun allSchedules(): List<RecurringSchedule>
    @Query("SELECT * FROM BillingOccurrence") suspend fun allOccurrences(): List<BillingOccurrence>
    @Query("SELECT * FROM ExpenseModel WHERE id = :id") suspend fun expense(id: Long): ExpenseModel?
    @Query("SELECT * FROM Person WHERE id = :id") suspend fun person(id: Long): Person?
    @Query("SELECT * FROM Debt WHERE personId = :personId") suspend fun personDebts(personId: Long): List<Debt>
    @Query("SELECT * FROM Debt WHERE transactionId = :id") suspend fun transactionDebt(id: Long): Debt?
    @Query("SELECT * FROM Settlement") suspend fun allSettlements(): List<Settlement>
    @Insert suspend fun insertExpense(value: ExpenseModel): Long
    @Update suspend fun updateExpense(value: ExpenseModel)
    @Query("DELETE FROM ExpenseModel WHERE id = :id") suspend fun deleteExpense(id: Long)
    @Insert suspend fun insertPerson(value: Person): Long
    @Update suspend fun updatePerson(value: Person)
    @Insert suspend fun insertDebt(value: Debt): Long
    @Update suspend fun updateDebt(value: Debt)
    @Query("DELETE FROM Debt WHERE id = :id") suspend fun deleteDebt(id: Long)
    @Insert suspend fun insertSettlement(value: Settlement): Long
    @Update suspend fun updateSettlement(value: Settlement)
    @Query("DELETE FROM Settlement WHERE transactionId = :id") suspend fun deleteSettlement(id: Long)
    @Insert suspend fun insertSchedule(value: RecurringSchedule): Long
    @Update suspend fun updateSchedule(value: RecurringSchedule)
    @Insert suspend fun insertOccurrence(value: BillingOccurrence): Long
    @Update suspend fun updateOccurrence(value: BillingOccurrence)
}

@Database(entities = [ExpenseModel::class, Person::class, Debt::class, Settlement::class, RecurringSchedule::class, BillingOccurrence::class], version = 2, exportSchema = true)
abstract class ExpenseDatabase : RoomDatabase() {
    abstract fun dao(): ExpenseDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_Debt_personId ON Debt (personId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_Settlement_debtId ON Settlement (debtId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Settlement_transactionId ON Settlement (transactionId)")
            }
        }
        @Volatile private var instance: ExpenseDatabase? = null
        fun get(context: Context): ExpenseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ExpenseDatabase::class.java, "expenses.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
