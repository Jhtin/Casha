package com.example.casha.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object InterestEstimate {
    fun total(principal:Long,annualRateBps:Int,count:Int,frequency:Frequency,reducing:Boolean):Long {
        require(principal in 1..Loan.MAX_AMOUNT && annualRateBps in 0..100_000 && count in 1..120)
        val periods=when(frequency){Frequency.MONTHLY->12;Frequency.WEEKLY->52;Frequency.FORTNIGHTLY->26;Frequency.SEMIMONTHLY->24}
        val mc=MathContext.DECIMAL128
        val rate=BigDecimal(annualRateBps).divide(BigDecimal(10_000L*periods),mc)
        val p=BigDecimal(principal)
        val total=if(rate.signum()==0)p else if(!reducing)p.multiply(BigDecimal.ONE.add(rate.multiply(BigDecimal(count))))
            else {val factor=BigDecimal.ONE.add(rate).pow(count,mc);p.multiply(rate,mc).multiply(factor,mc).divide(factor.subtract(BigDecimal.ONE),mc).multiply(BigDecimal(count))}
        return total.setScale(0,RoundingMode.HALF_UP).longValueExact().also {require(it in principal..Loan.MAX_AMOUNT)}
    }
}
