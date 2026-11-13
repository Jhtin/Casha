package com.example.casha
import com.example.casha.domain.*
import com.example.casha.ui.cashActivityEntries
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*
class CashActivityFilterTest {
 private val day=LocalDate.of(2026,11,3)
 private val profile=PaydayProfile(day.minusDays(2),day.plusDays(10),10000,0)
 private fun entry(id:String,date:LocalDate,note:String="")=CashEntry(id,false,100,date,note)
 @Test fun currentCutoffExcludesPastFutureAndNextCutoff(){
  val entries=listOf(entry("old",profile.start.minusDays(1)),entry("start",profile.start),entry("today",day),entry("future",day.plusDays(1)),entry("next",profile.next))
  assertEquals(listOf("today","start"),cashActivityEntries(entries,profile,false,today=day).map {it.id})
  assertEquals(5,cashActivityEntries(entries,profile,true,today=day).size)
 }
 @Test fun backdatedEntriesDoNotDisplaceNewerActivityAndTiesUseNewestInsertion(){
  val entries=listOf(entry("a",day),entry("b",day),entry("backdated",day.minusDays(1)))
  assertEquals(listOf("b","a","backdated"),cashActivityEntries(entries,profile,true,today=day).map {it.id})
  assertEquals(listOf("a","b","backdated"),entries.map {it.id})
 }
 @Test fun searchMatchesNotesAndDatesWithoutThirtyEntryLimit(){
  val entries=(0 until 45).map {entry("id$it",day.minusDays(it.toLong()),if(it==44)"Food groceries" else "Transport")}
  assertEquals(45,cashActivityEntries(entries,profile,true,today=day).size)
  assertEquals("id44",cashActivityEntries(entries,profile,true,"  FOOD  ",day).single().id)
  assertEquals("id0",cashActivityEntries(entries,profile,true,"2026-11-03",day).single().id)
 }
}
