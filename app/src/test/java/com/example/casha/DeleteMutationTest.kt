package com.example.casha
import com.example.casha.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
class DeleteMutationTest {
 private val date=LocalDate.of(2026,1,1)
 private val loan=Loan(id="delete-loan",party="Wrong loan",mode=LoanMode.I_OWE,total=10000,count=1,firstDue=date,frequency=Frequency.MONTHLY)
 @Test fun onlyExactUnpaidSnapshotCanBeDeleted(){
  val deletion=CloudMutation(loan,loan,true)
  assertEquals(MutationKind.DELETE,deletion.kind);assertFalse(deletion.alreadyApplied(loan))
  assertTrue(runCatching {CloudMutation(null,loan,true).kind}.isFailure)
  assertTrue(runCatching {CloudMutation(loan,loan.copy(party="Changed"),true).kind}.isFailure)
 }
 @Test fun evenFullyReversedPaymentHistoryCannotBeDeleted(){
  val paid=loan.pay(0,1000,"payment",date)
  assertTrue(runCatching {CloudMutation(paid,paid,true).kind}.isFailure)
  val reversed=paid.reverse("payment",date,"Wrong amount")
  assertEquals(loan.total,reversed.remaining)
  assertTrue(runCatching {CloudMutation(reversed,reversed,true).kind}.isFailure)
 }
}
