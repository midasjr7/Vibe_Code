package com.example.vibe_code

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun allScreensAreReachableWithoutNetwork() {
        compose.onNodeWithText("Expense Manager").assertIsDisplayed()
        compose.onNodeWithText("Entries", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Show").assertIsDisplayed()
        compose.onNodeWithText("Bills", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Bills & autopay").assertIsDisplayed()
        compose.onNodeWithText("People", useUnmergedTree = true).performClick()
        compose.onNodeWithText("People & settlements").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Preferences").assertIsDisplayed()
        compose.onNodeWithText("Overview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Monthly overview").assertIsDisplayed()
        val output = File(compose.activity.getExternalFilesDir(null), "overview.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun newScheduleRequiresUserToChooseBothRangeDates() {
        compose.onNodeWithText("Bills", useUnmergedTree = true).performClick()
        compose.onNodeWithText("+ Bill / autopay").performClick()
        compose.onNodeWithText("Range start: Choose date").assertExists()
        compose.onNodeWithText("Range end: Choose date").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Choose both dates of the payment range").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }
}
