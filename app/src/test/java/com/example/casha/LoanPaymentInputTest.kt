package com.example.casha
import com.example.casha.ui.validateLoanPayment
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*
class LoanPaymentInputTest {
 private val today=LocalDate.of(2026,11,3)
 @Test fun partialAndExactInstallmentAmountsAreAccepted(){
  assertEquals(1000L,validateLoanPayment("10.00",today.toString(),170000,today)?.amount)
  assertEquals(170000L,validateLoanPayment("1700.00",today.toString(),170000,today)?.amount)
 }
 @Test fun invalidMoneyAndOverpaymentsAreRejected(){
  for(value in listOf("","0","-1","10.001","1700.01"))assertNull(validateLoanPayment(value,today.toString(),170000,today))
 }
 @Test fun invalidOrFutureDatesAreRejectedButBackdatingIsAllowed(){
  for(date in listOf("bad","2026-02-30","1800-01-01","2026-11-04"))assertNull(validateLoanPayment("10",date,170000,today))
  assertEquals(today.minusDays(1),validateLoanPayment("10",today.minusDays(1).toString(),170000,today)?.date)
 }
}
