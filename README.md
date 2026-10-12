# Expense Manager

Offline Android expense manager built with Kotlin and Jetpack Compose. Financial records are stored in Room; preferences are stored in SharedPreferences. The app has no Internet permission, login, bank connection, or cloud/device-transfer backup.

## Using the app

- **Overview:** choose a month to see debit/credit totals, category outflows, Monday-start weekly graphs, unconfirmed autopay amounts, and separate borrowed/lent balances.
- **Entries:** record general debits/credits or borrowed/lent amounts. Choose a person for borrowed/lent entries. Amounts use exact integer paise.
- **Bills:** select both range dates yourself and choose weekly or monthly recurrence. Bills become expenses when marked paid. Autopay expenses are recorded at the range start and await deduction confirmation; failed deductions are excluded from totals.
- **People:** record partial repayments or mark all remaining amounts settled. Settlement-and-delete creates repayment transactions atomically and hides the person while preserving their name and history.
- **Settings:** change theme and start screen; inspect notification availability. Notify me is controlled per schedule. With notifications off, pending payments remain in the app.

## Recurrence and corrections

Both original range endpoints shift by calendar months or seven days. Month-end dates clamp without drifting the original anchor. Ranges must not overlap the next cycle. Expiry includes a cycle starting on the expiry date.

Schedule edits retain existing occurrences and advance from the existing cycle cursor; the displayed dates are the original recurrence anchors. Edits cannot overlap generated payments. Pause stops future deductions and reminders. Resume skips cycles elapsed while paused. Cancel stops future cycles and preserves already-recorded history; a banking mandate must be cancelled outside this app.

Deleting an automatically generated expense marks that occurrence skipped so reconciliation cannot recreate it. Editing a repayment updates its debt balance atomically and rejects overpayment. Editing a scheduled payment retains its confirmation state and occurrence link. Deleting a repayment reopens the associated debt. Settled debt opening entries must not be changed until repayments are removed. Financial history for deleted people is preserved.

## Background work and storage

WorkManager checks every 12 hours; opening the app also reconciles due cycles. Android can delay background execution. Balance reminders default to one day before the range; deduction confirmation reminders follow its end and repeat at most weekly. Overdue reminders are grouped. Balance reminders are advisory; the app cannot inspect account balances or execute payments.

Room schemas are exported in `app/schemas`. Database version 2 migrates version 1 without destroying records. Future schema changes require an explicit migration and a preservation test. Cloud and device-transfer backup exclusions apply to financial records and preferences. Clearing app data or uninstalling removes local records.

Defaults: INR only, Monday-start weeks, Grocery/Other categories. Receipt attachments, currency conversion, export, and bank integrations are outside this version.

## Build and tests

Use JDK 21 and the configured Android SDK:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

The connected suite requires an Android emulator or device. Tests cover money validation, arbitrary/cross-month ranges, short months, duplicate prevention, confirmation/failure/deletion, settlements, pause/cancel, notification opt-out, schema migration, and screen navigation.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

