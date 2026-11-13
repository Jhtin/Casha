package com.example.casha

import com.example.casha.data.*
import com.example.casha.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CashaBackupCompatibilityTest {
    @Test fun renamedBackupRetainsLegacyFinancialPayloadAndImportsOlderExports() {
        val finance = FinanceStore(null, "synthetic_rebrand", FakeSharedPreferences())
        val loan = Loan(id = "rebrand_loan", party = "Synthetic lender", mode = LoanMode.I_OWE,
            total = 18400, count = 1, firstDue = LocalDate.of(2026, 11, 4), frequency = Frequency.MONTHLY)
        val repository = object : LoanRepository {
            override val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun load() = listOf(loan)
            override fun save(loans: List<Loan>, expected: List<Loan>?) = false
        }
        val backup = PortableBackup.encode(repository, finance)
        assertEquals("Casha", backup.getString("application"))
        assertEquals("Petot", backup.getString("assistant"))
        assertEquals(listOf(loan), PortableBackup.validate(backup, finance))
        backup.remove("application"); backup.remove("assistant")
        assertEquals(listOf(loan), PortableBackup.validate(backup, finance))
    }
}
