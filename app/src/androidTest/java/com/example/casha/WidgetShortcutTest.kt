package com.example.casha

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.casha.data.FinanceStore
import com.example.casha.widget.CashaWidget
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After

class WidgetShortcutTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @After fun closeKeyboard(){androidx.test.espresso.Espresso.closeSoftKeyboard()}
    @Test fun widgetActionsOpenReviewFormsRejectOldAccountAndSurviveRecreation() {
        compose.waitForIdle()
        if(compose.onAllNodesWithText("Skip tutorial").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Skip tutorial").performClick()
        val scope=FinanceStore.scopeFor(FirebaseAuth.getInstance().currentUser?.uid)
        val finance=FinanceStore(compose.activity,scope)
        val before=finance.cashEntries()
        fun open(action:String,owner:String=scope) {
            compose.activityRule.scenario.onActivity {it.startActivity(CashaWidget.launchIntent(it,owner,action))}
            compose.waitForIdle()
        }
        open("cash")
        compose.onNodeWithText("Save cash entry").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Save cash entry").assertDoesNotExist()
        open("expense")
        compose.onNodeWithText("Add expense").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        open("expense","other-account")
        compose.onNodeWithText("Save expense").assertDoesNotExist()
        open("cash")
        compose.onNodeWithText("Save cash entry").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        open("payday")
        compose.onNodeWithText("Payday strategist").assertIsDisplayed()
        assertEquals(before,finance.cashEntries())
    }
}
