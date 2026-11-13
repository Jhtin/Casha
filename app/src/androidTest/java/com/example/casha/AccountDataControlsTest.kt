package com.example.casha

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.FinanceStore
import com.example.casha.domain.CashEntry
import com.example.casha.ui.AccountDataControls
import com.example.casha.ui.theme.CashaTheme
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountDataControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun privacyIsReadableOfflineWithoutOpeningAnExternalService() {
        var external = false
        compose.setContent { CashaTheme { AccountDataControls { external = true } } }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Casha privacy policy").assertIsDisplayed()
        compose.onNodeWithText("Developer: jtin", substring = true).assertExists()
        compose.onNodeWithText("Close privacy policy").performClick()
        assertFalse(external)
    }

    @Test fun deletionOnlyOpensAReviewableEmailAndRetainsFinancialRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test_deletion_request_${UUID.randomUUID()}"
        val finance = FinanceStore(context, scope)
        val cash = CashEntry("retained", true, 12345, LocalDate.now(), "Synthetic funding")
        assertTrue(finance.addCash(cash))
        var draft: Intent? = null
        try {
            compose.setContent { CashaTheme { AccountDataControls { draft = it } } }
            compose.onNodeWithText("Request account deletion").performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertNull(draft)
            compose.onNodeWithText("Request account deletion").performClick()
            compose.onNodeWithText("Open email draft").performClick()
            assertEquals(Intent.ACTION_SENDTO, draft?.action)
            assertTrue(draft?.dataString?.startsWith("mailto:jhustinegregorio07@gmail.com?") == true)
            assertTrue(draft?.dataString?.contains("subject=") == true)
            assertEquals(listOf(cash), finance.cashEntries())
        } finally { context.deleteSharedPreferences("finance_$scope") }
    }
}
