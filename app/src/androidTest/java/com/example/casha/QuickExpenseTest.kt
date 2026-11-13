package com.example.casha

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.casha.data.*
import com.example.casha.ui.CashaApp
import com.example.casha.ui.theme.CashaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class QuickExpenseTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="quick_expense_${UUID.randomUUID()}"
    private val finance get()=FinanceStore(context,name)
    @After fun clean(){context.deleteSharedPreferences(name);context.deleteSharedPreferences("finance_$name")}
    @Test fun expenseShortcutSavesAmountOnlySupportsUndoAndCancelFromSettings() {
        compose.setContent {CashaTheme(darkTheme=false){CashaApp(LoanStore(context,name),name)}}
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5000){compose.onAllNodesWithText("Expense").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Add expense").assertIsDisplayed()
        compose.onNodeWithText("Import image").assertExists()
        compose.onNodeWithText("Take photo").assertExists()
        compose.onNodeWithText("Amount (PHP)").assertIsFocused().performTextInput("125.50")
        val folder=File(context.filesDir,"phase_previews").apply {mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let {bitmap->File(folder,"quick-expense.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        compose.onNodeWithText("Save expense").performClick()
        compose.waitUntil(5000){finance.cashEntries().size==1}
        compose.runOnIdle {val entry=finance.cashEntries().single();assertEquals(12550L,entry.amount);assertFalse(entry.income);assertEquals(LocalDate.now(),entry.date);assertEquals("",entry.note)}
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000){finance.cashEntries().size==2}
        compose.runOnIdle {assertEquals(0L,finance.cashEntries().sumOf {if(it.income)it.amount else -it.amount})}
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("12.345")
        compose.onNodeWithText("Save expense").performClick()
        compose.onNodeWithText("Enter a valid amount",substring=true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertEquals(2,finance.cashEntries().size)}
        compose.onNodeWithText("Expense").performClick()
        compose.onNodeWithText("Amount (PHP)").performTextInput("20")
        compose.onNodeWithText("Food").performClick()
        compose.onNodeWithText("Save expense").performClick()
        compose.waitUntil(5000){finance.cashEntries().size==3}
        compose.runOnIdle {assertEquals("Food",finance.cashEntries().last().note)}
    }
}
