package com.example.casha

import com.example.casha.domain.PaymentDetector
import org.junit.Assert.*
import org.junit.Test

class ReceiptTotalTest {
    @Test fun selectsTotalDueInsteadOfCashChangeIdentifiersAndQuantity() {
        val text = """ORDER SLIP 0000135557
            95394954 22.00 44.00
            27642900 35.00 70.00
            76075665 35.00 70.00
            Line Items: 3 Quantity 6.00000
            TOTAL DUE 184.00
            CASH 200.00
            CHANGE 16.00
            DATE/TIME 2026-11-02 16:15:18""".trimIndent()
        assertEquals(18400L, PaymentDetector.parse(text).amount)
    }
    @Test fun supportsSplitLabelAndAmountRows() {
        assertEquals(18400L, PaymentDetector.parse("TOTAL DUE\n184.00\nCASH\n200.00\nCHANGE\n16.00").amount)
    }
    @Test fun avoidsSubtotalQuantityAndAmbiguousTotals() {
        assertNull(PaymentDetector.receiptTotal("SUBTOTAL 160.00\nTotal Quantity 6.00\nCASH 200.00\nCHANGE 16.00"))
        assertNull(PaymentDetector.receiptTotal("TOTAL DUE 184.00\nTOTAL DUE 194.00"))
        assertNull(PaymentDetector.parse("Sent PHP 100.00 Balance PHP 500.00").amount)
    }
    @Test fun prioritizesGrandTotalAndRejectsMalformedAmounts() {
        assertEquals(18400L, PaymentDetector.receiptTotal("Total 170.00\nGRAND TOTAL PHP 184.00\nCASH 200.00"))
        assertNull(PaymentDetector.receiptTotal("TOTAL DUE 184.001"))
    }
}
