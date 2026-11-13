package com.example.casha.domain

import java.time.LocalDate

object ReminderPolicy {
    fun stage(due:LocalDate,today:LocalDate,leadDays:Int):String? {
        require(leadDays in 0..7)
        return when {
            due.isBefore(today)->"Overdue"
            due==today->"Due today"
            !due.isAfter(today.plusDays(leadDays.toLong()))->"Due soon"
            else->null
        }
    }
}
