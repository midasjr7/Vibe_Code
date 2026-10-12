package com.example.vibe_code

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vibe_code.data.ExpenseDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ExpenseDatabase::class.java)
    @Test fun migrationPreservesFinancialRecords() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO Person (id, name, deletedAt) VALUES (1, 'Asha', NULL)")
            execSQL("INSERT INTO ExpenseModel (id, title, amountMinor, dateTime, category, direction, purpose, personId, personName, notes, status, occurrenceId, createdAt, updatedAt) VALUES (1, 'Loan', 10000, 1, 'Other', 'CREDIT', 'BORROWED', 1, 'Asha', '', 'CONFIRMED', NULL, 1, 1)")
            execSQL("INSERT INTO Debt (id, personId, kind, amountMinor, transactionId) VALUES (1, 1, 'BORROWED', 10000, 1)")
            close()
        }
        helper.runMigrationsAndValidate("migration-test", 2, true, ExpenseDatabase.MIGRATION_1_2).apply {
            query("SELECT amountMinor, personName FROM ExpenseModel WHERE id = 1").use {
                assertTrue(it.moveToFirst()); assertEquals(10000L, it.getLong(0)); assertEquals("Asha", it.getString(1))
            }
            query("SELECT amountMinor FROM Debt WHERE id = 1").use { assertTrue(it.moveToFirst()); assertEquals(10000L, it.getLong(0)) }
            close()
        }
    }
}
