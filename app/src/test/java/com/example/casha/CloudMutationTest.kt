package com.example.casha

import com.example.casha.domain.*
import com.example.casha.data.CloudLoanDocument
import com.example.casha.data.syncFailureMessage
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CloudMutationTest {
    private val date = LocalDate.of(2026, 1, 1)
    private fun loan() = Loan(id = "loan-1", party = "Lender", mode = LoanMode.I_OWE, total = 10_000,
        count = 1, firstDue = date, frequency = Frequency.MONTHLY)
    @Test fun identifiesSingleValidatedLedgerChanges() {
        val initial = loan()
        val paid = initial.pay(0, 1000, "p1", date)
        val reversed = paid.reverse("p1", date, "Wrong amount")
        assertEquals(MutationKind.CREATE, CloudMutation(null, initial).kind)
        assertEquals(MutationKind.PAYMENT, CloudMutation(initial, paid).kind)
        assertEquals(MutationKind.REVERSAL, CloudMutation(paid, reversed).kind)
        assertEquals(MutationKind.EDIT, CloudMutation(initial, initial.copy(party = "Renamed")).kind)
    }
    @Test fun lostAcknowledgementDoesNotDuplicatePaymentEvenAfterReversal() {
        val original = loan()
        val paid = original.pay(0, 1000, "p1", date)
        assertTrue(CloudMutation(original, paid).alreadyApplied(paid.reverse("p1", date, "Wrong loan")))
        assertFalse(CloudMutation(original, paid).alreadyApplied(original))
    }
    @Test(expected = IllegalArgumentException::class) fun mixedPaymentAndTermChangeIsRejected() {
        val original = loan()
        CloudMutation(original, original.copy(total = 20_000).pay(0, 1000, "p1", date)).kind
    }
    @Test fun importReplaysReversalsBeforeReplacementPaymentsWithoutOverpaying() {
        val original = loan().pay(0, 10_000, "p1", date).reverse("p1", date, "Incorrect")
            .pay(0, 10_000, "p2", date)
        val imported = CloudMutation.importHistory(original)
        assertEquals(listOf(MutationKind.CREATE, MutationKind.PAYMENT, MutationKind.REVERSAL, MutationKind.PAYMENT), imported.map { it.kind })
        assertEquals(original, imported.last().after)
    }
    @Test fun cloudDocumentRoundTripsAndComputesLedgerAggregates() {
        val original = loan().pay(0, 1000, "p1", date).reverse("p1", date, "Incorrect")
        val document = CloudLoanDocument.encode(original, 3, "REVERSAL")
        assertEquals(original, CloudLoanDocument.decode(original.id, document))
        assertEquals(emptyMap<String,Long>(), document["paid"])
        assertEquals(mapOf("p1" to true), document["reversed"])
    }
    @Test(expected = IllegalArgumentException::class) fun createCannotSmuggleHistory() {
        CloudMutation(null, loan().pay(0, 1000, "p1", date)).kind
    }
    @Test fun disabledApiErrorHasAnActionableMessageWithoutServerDetails() {
        val message=syncFailureMessage(Exception("Cloud Firestore API has not been used in project private-project before or it is disabled"))
        assertTrue(message.contains("enable the Cloud Firestore API"))
        assertFalse(message.contains("private-project"))
    }
}
