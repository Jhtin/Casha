package com.example.casha

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.LoanStore
import com.example.casha.domain.*
import com.example.casha.ui.CashaApp
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "workflow_test_${java.util.UUID.randomUUID()}"
    private val store get() = LoanStore(context, name)

    @After fun cleanUp() { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun oldRecordsLoadAndReversalSurvivesReload() {
        val legacy = """[{"id":"old-loan","party":"Legacy lender","mode":"I_OWE","total":10000,"count":2,"firstDue":"2026-01-31","frequency":"MONTHLY","payments":[{"id":"old-payment","installment":0,"amount":1000,"date":"2026-01-01"}]}]"""
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("loans", legacy).commit()
        val original = store.load().single()
        assertEquals(9000L, original.remaining)
        assertTrue(original.reversals.isEmpty())
        val reversed = original.reverse("old-payment", LocalDate.of(2026, 1, 2), "Duplicate entry")
        assertTrue(store.save(listOf(reversed)))
        assertEquals(reversed, store.load().single())
        assertEquals(10_000L, store.load().single().remaining)
    }

    @Test fun addPayReverseAndRenameThroughScreens() {
        compose.setContent { MaterialTheme { CashaApp(store) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Add loan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add loan").assertIsEnabled().performClick()
        compose.onNodeWithText("Person or lender").performTextInput("Test lender")
        compose.onNodeWithText("Total payable (PHP)").performTextInput("100.00")
        compose.onNodeWithText("Installments (1–120)").performTextReplacement("3")
        compose.onNodeWithTag("loan-form").performScrollToNode(hasText("First due: YYYY-MM-DD"))
        compose.onNodeWithText("First due: YYYY-MM-DD").performTextReplacement("2026-02-15")
        compose.onNodeWithContentDescription("Choose First due: YYYY-MM-DD").performClick()
        compose.onNodeWithText("Use date").performClick()
        compose.onNodeWithText("First due: YYYY-MM-DD").assertTextContains("2026-02-15")
        compose.onNodeWithTag("loan-form").performScrollToNode(hasText("15th & month-end"))
        compose.onNodeWithText("15th & month-end").performClick()
        compose.onNodeWithText("Save loan").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Test lender").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Test lender").onFirst().performClick()
        compose.onNodeWithText("Record payment").performClick()
        compose.onNodeWithText("Payment (PHP)").performTextInput("10.00")
        compose.onNodeWithText("Review payment").performClick()
        compose.onNodeWithText("Save payment").performClick()
        compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Payment history"))
        compose.onNodeWithText("Payment history").assertExists()
        compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Reverse payment"))
        compose.onNodeWithText("Reverse payment").performClick()
        compose.onNodeWithText("Reason").performTextInput("Wrong amount")
        compose.onNodeWithText("Confirm reversal").performClick()
        compose.waitUntil(5000) { store.load().single().reversals.size == 1 }
        compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Reversed", substring = true))
        compose.onNodeWithText("Reversed", substring = true).assertExists()
        compose.onNodeWithTag("loan-details-list").performScrollToNode(hasText("Manage loan"))
        compose.onNodeWithText("Manage loan").performClick()
        compose.onNodeWithText("Edit loan").performClick()
        compose.onNodeWithText("Total payable (PHP)").assertIsNotEnabled()
        compose.onNodeWithText("Person or lender").performTextReplacement("Renamed lender")
        compose.onNodeWithText("Save loan").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Renamed lender").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val saved = store.load().single()
            assertEquals("Renamed lender", saved.party)
            assertEquals(Frequency.SEMIMONTHLY, saved.frequency)
            assertEquals(10_000L, saved.remaining)
            assertEquals(1, saved.payments.size)
            assertEquals(1, saved.reversals.size)
        }
    }
}
